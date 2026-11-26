"""Live dictation: WebSocket /stt/live.

Client -> server: one JSON header {"model": ...}, then binary frames of raw
PCM16 mono 16kHz as they are recorded, then JSON {"type": "end"}.
Server -> client: {"type": "partial", "stable": ..., "tentative": ...} while
audio arrives, then {"type": "final", "text": ...}.

Whisper has no native streaming, so the not-yet-settled window is simply
transcribed again and again. Once the window grows long it is cut at the
quietest point and that part is transcribed properly (beam 5) and frozen,
keeping each pass fast. On "end" only the last unfrozen piece is transcribed,
so the wait after stopping does not grow with the length of the dictation.

Audio is written to disk as it arrives and is never deleted automatically, so
a dictation survives a dropped connection or a failed transcription.
"""
import asyncio
import json
import os
import time
import wave

import numpy as np
from fastapi import WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool

LIVE_DIR = "/ai1/models/tts-stt-cache/stt-live"
RATE = 16000
MIN_NEW_S = 0.7       # new audio needed before another partial pass
CUT_AFTER_S = 15.0    # freeze part of the window once it is longer than this
CUT_EARLIEST_S = 8.0


def _samples(buf) -> np.ndarray:
    return np.frombuffer(bytes(buf), dtype=np.int16).astype(np.float32) / 32768.0


def _quietest_cut(window: np.ndarray) -> int:
    """Sample index of the quietest 0.2s between CUT_EARLIEST_S and the last second."""
    step, width = RATE // 20, RATE // 5
    starts = range(int(CUT_EARLIEST_S * RATE), len(window) - RATE, step)
    best = min(starts, key=lambda i: float(np.mean(window[i:i + width] ** 2)))
    return best + width // 2


def _common_prefix(a: list[str], b: list[str]) -> int:
    n = 0
    while n < min(len(a), len(b)) and a[n].strip(".,?!").lower() == b[n].strip(".,?!").lower():
        n += 1
    return n


def register_stt_live(app, engines, security_ok, max_bytes):
    @app.websocket("/stt/live")
    async def stt_live(websocket: WebSocket):
        client_host = websocket.client.host if websocket.client else None
        if not security_ok(client_host, websocket.headers):
            await websocket.close(code=4403)
            return
        await websocket.accept()

        async def send(obj):
            try:
                await websocket.send_json(obj)
            except Exception:
                pass  # client gone; the recording is still saved and transcribed

        try:
            header = await websocket.receive_json()
        except Exception:
            return
        engine = engines.get(header.get("model", "whisper-medium-gpu"))
        if engine is None:
            await send({"type": "error", "message": "unknown model"})
            await websocket.close()
            return

        day = os.path.join(LIVE_DIR, time.strftime("%Y%m%d"))
        os.makedirs(day, mode=0o700, exist_ok=True)
        os.chmod(LIVE_DIR, 0o700)
        base = os.path.join(day, time.strftime("%H%M%S") + f"-{time.time_ns() % 1000000:06d}")
        raw = open(base + ".wav", "wb")
        wav = wave.open(raw, "wb")
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)

        audio = bytearray()
        ended = asyncio.Event()

        async def receive():
            try:
                while True:
                    msg = await websocket.receive()
                    if msg["type"] == "websocket.disconnect":
                        break
                    data = msg.get("bytes")
                    if data:
                        if len(audio) + len(data) > max_bytes:
                            await send({"type": "error", "message": "audio too long"})
                            break
                        audio.extend(data)
                        wav.writeframes(data)  # also keeps the WAV header current
                        raw.flush()
                    elif msg.get("text") and json.loads(msg["text"]).get("type") == "end":
                        break
            except (WebSocketDisconnect, RuntimeError, ValueError):
                pass
            finally:
                ended.set()

        offset = 0           # samples already frozen
        frozen: list[str] = []

        async def partials():
            nonlocal offset
            seen = 0
            previous: list[str] = []
            while not ended.is_set():
                total = len(audio) // 2
                if not engine.ready or total - seen < MIN_NEW_S * RATE:
                    try:
                        await asyncio.wait_for(ended.wait(), 0.15)
                    except asyncio.TimeoutError:
                        pass
                    continue
                seen = total
                try:
                    window = _samples(audio[offset * 2:total * 2])
                    if len(window) > CUT_AFTER_S * RATE:
                        cut = _quietest_cut(window)
                        part = await run_in_threadpool(engine.transcribe_live, window[:cut], 5)
                        frozen.extend(part.split())
                        offset += cut
                        window = window[cut:]
                        previous = []
                    words = (await run_in_threadpool(engine.transcribe_live, window, 1)).split()
                except Exception as e:
                    print(f"[stt-live] partial pass failed: {e.__class__.__name__}: {e}")
                    continue
                n = _common_prefix(previous, words)
                previous = words
                if not ended.is_set():
                    await send({"type": "partial",
                                "stable": " ".join(frozen + words[:n]),
                                "tentative": " ".join(words[n:])})

        receiver = asyncio.create_task(receive())
        try:
            await partials()
            await receiver
        finally:
            wav.close()
            raw.close()

        try:
            tail = audio[offset * 2:len(audio) // 2 * 2]
            if len(tail) >= RATE // 5:  # under 0.1s is nothing but a chance to invent text
                frozen.extend((await run_in_threadpool(engine.transcribe_live, _samples(tail), 5)).split())
            text = " ".join(frozen)
            with open(base + ".txt", "w") as fh:
                fh.write(text + "\n")
            await send({"type": "final", "text": text})
        except Exception as e:
            print(f"[stt-live] final pass failed ({base}.wav is kept): {e.__class__.__name__}: {e}")
            await send({"type": "error", "message": f"transcription failed: {e}"})
        try:
            await websocket.close()
        except Exception:
            pass
