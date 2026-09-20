"""
TTS + STT server, split out of newsdigest-server.py (host3) onto ai1 (a
qemu VM on host1, 2026-09-19) so voice synthesis/dictation runs isolated
from host3 and gets Chatterbox a dedicated GPU instead of sharing host3's.
Feed-digest and agent-chat stayed on host3 - this process only ever does
audio in, audio out.

Runs both TTS engines resident in one process (Kokoro on CPU via
onnxruntime, Chatterbox on GPU via CUDA) plus three faster-whisper STT
sizes, same design as the server this was split from. See that file's
history (~/src/newsdigest-android/server/server.py on host3) for the
full reasoning behind the engine choices, caching, and streaming protocol
- unchanged here except for what moved.

Reachability: this process only ever sees connections from host1's own
nftables DNAT rule (10.176.54.16:8100 -> this VM's 8100) - host1's
ai1-netup.sh is what actually decides who can reach this at all. The
ALLOWED_SUBNET/X-Peer-Agent check below is defense in depth on top of
that, same reasoning as the WireGuard-tunnel version had for its own
network boundary.
"""
import asyncio
import hashlib
import ipaddress
import json
import os
import re
import sys
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse

from acronyms import expand_acronyms, expand_domains
from text_clean import markdown_to_speech


def _env_or_fatal(name):
    val = os.environ.get(name)
    if not val:
        sys.stderr.write(f"ai1-tts-stt-server: {name} must be set (see README) - refusing to start\n")
        raise SystemExit(1)
    return val


BIND_HOST = _env_or_fatal("TTS_STT_BIND_HOST")
BIND_PORT = int(os.environ.get("TTS_STT_BIND_PORT", "8100"))
ALLOWED_SUBNET = ipaddress.ip_network(_env_or_fatal("TTS_STT_ALLOWED_SUBNET"))
MODEL_DIR = Path.home() / ".cache" / "ai1-tts-stt-server"


@asynccontextmanager
async def lifespan(app: FastAPI):
    ENGINES["kokoro"].load()
    threading.Thread(target=_load_engine_background, args=(ENGINES["chatterbox"],), daemon=True).start()
    for stt_engine in STT_ENGINES.values():
        threading.Thread(target=_load_engine_background, args=(stt_engine,), daemon=True).start()
    yield


app = FastAPI(lifespan=lifespan)


# ---------------------------------------------------------------- security

def _source_allowed(client_host: str | None) -> bool:
    if client_host is None:
        return False
    try:
        addr = ipaddress.ip_address(client_host)
    except ValueError:
        return False
    return addr.is_loopback or addr in ALLOWED_SUBNET


def _security_ok(client_host: str | None, headers) -> bool:
    if not _source_allowed(client_host):
        return False
    if headers.get("origin") is not None:
        return False
    if headers.get("x-peer-agent") != "1":
        return False
    return True


@app.middleware("http")
async def security_middleware(request: Request, call_next):
    if request.method not in ("GET", "POST"):
        return JSONResponse({"error": "method not allowed"}, status_code=405)
    client_host = request.client.host if request.client else None
    if not _security_ok(client_host, request.headers):
        return JSONResponse({"error": "forbidden"}, status_code=403)
    return await call_next(request)


# ------------------------------------------------------------------ text

_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")


def split_sentences(text: str) -> list[str]:
    text = re.sub(r"\n{2,}", "\n\n", text.strip())
    parts = []
    for para in text.split("\n\n"):
        para = para.replace("\n", " ").strip()
        if not para:
            continue
        parts.extend(s.strip() for s in _SENTENCE_RE.split(para) if s.strip())
    return parts


def estimate_word_timings(sentence: str, duration_s: float) -> list[dict]:
    words = sentence.split()
    if not words:
        return []
    char_counts = [max(len(w), 1) for w in words]
    total_chars = sum(char_counts)
    cursor = 0.0
    timings = []
    for word, count in zip(words, char_counts):
        dur = duration_s * (count / total_chars)
        timings.append({
            "word": word,
            "start_ms": round(cursor * 1000),
            "end_ms": round((cursor + dur) * 1000),
        })
        cursor += dur
    return timings


def float_to_pcm16(samples: np.ndarray) -> bytes:
    clipped = np.clip(samples, -1.0, 1.0)
    return (clipped * 32767.0).astype(np.int16).tobytes()


# ------------------------------------------------------------- tts cache
#
# See newsdigest-server.py's original comment (host3) for the full
# reasoning - unchanged here, just a new cache directory under this
# service's own MODEL_DIR.

TTS_CACHE_DIR = MODEL_DIR / "tts-cache"
TTS_CACHE_MAX_BYTES = 2 * 1024 ** 3  # 2 GiB, evicted LRU by mtime


def _tts_cache_key(engine_name: str, voice: str | None, expanded_text: str) -> str:
    raw = f"{engine_name}\x00{voice or ''}\x00{expanded_text}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _tts_cache_paths(key: str) -> tuple[Path, Path]:
    return TTS_CACHE_DIR / f"{key}.pcm", TTS_CACHE_DIR / f"{key}.json"


def _tts_cache_load(key: str) -> tuple[bytes, dict] | None:
    pcm_path, meta_path = _tts_cache_paths(key)
    try:
        meta = json.loads(meta_path.read_text())
        pcm = pcm_path.read_bytes()
    except (OSError, ValueError):
        return None
    now = time.time()
    try:
        os.utime(pcm_path, (now, now))
        os.utime(meta_path, (now, now))
    except OSError:
        pass
    return pcm, meta


def _tts_cache_store(key: str, pcm: bytes, meta: dict) -> None:
    try:
        TTS_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        pcm_path, meta_path = _tts_cache_paths(key)
        pcm_path.write_bytes(pcm)
        meta_path.write_text(json.dumps(meta))
    except OSError:
        return
    _tts_cache_evict_if_needed()


def _tts_cache_evict_if_needed() -> None:
    try:
        entries = sorted(TTS_CACHE_DIR.glob("*.pcm"), key=lambda p: p.stat().st_mtime)
    except OSError:
        return
    total = sum(p.stat().st_size for p in entries)
    for p in entries:
        if total <= TTS_CACHE_MAX_BYTES:
            break
        try:
            total -= p.stat().st_size
            p.unlink(missing_ok=True)
            p.with_suffix(".json").unlink(missing_ok=True)
        except OSError:
            pass


# --------------------------------------------------------------- engines

class KokoroEngine:
    name = "kokoro"

    def __init__(self):
        self.ready = False
        self._kokoro = None
        self._lock = threading.Lock()

    def load(self):
        from kokoro_onnx import Kokoro
        self._kokoro = Kokoro(
            str(MODEL_DIR / "kokoro-v1.0.onnx"),
            str(MODEL_DIR / "voices-v1.0.bin"),
        )
        self.ready = True

    def voices(self) -> list[str]:
        if not self._kokoro:
            return []
        return [v for v in self._kokoro.get_voices() if v.startswith(("af_", "am_", "bf_", "bm_"))]

    def synthesize(self, text: str, voice: str | None) -> tuple[np.ndarray, int]:
        with self._lock:
            samples, sr = self._kokoro.create(text, voice=voice or "af_heart", speed=1.0, lang="en-us")
        return samples, sr


class ChatterboxEngine:
    name = "chatterbox"

    def __init__(self):
        self.ready = False
        self._model = None
        self._lock = threading.Lock()

    def load(self):
        import torch  # noqa: F401 - import here, not at module scope, so a CUDA hiccup can't block Kokoro from serving
        from chatterbox.tts import ChatterboxTTS
        self._model = ChatterboxTTS.from_pretrained(device="cuda")
        self.ready = True

    def voices(self) -> list[str]:
        return ["default"]

    def synthesize(self, text: str, voice: str | None) -> tuple[np.ndarray, int]:
        with self._lock:
            wav = self._model.generate(text)
        return wav.squeeze().numpy(), self._model.sr


ENGINES = {
    "kokoro": KokoroEngine(),
    "chatterbox": ChatterboxEngine(),
}


def _load_engine_background(engine):
    try:
        engine.load()
    except Exception as e:
        print(f"[ai1-tts-stt-server] {engine.name} failed to load: {e.__class__.__name__}: {e}")


# --------------------------------------------------------------- speech-to-text
#
# See newsdigest-server.py's original WhisperEngine comment (host3) for
# why faster-whisper, why three sizes, and why CPU - unchanged here.
# Whisper stays CPU-only even though this VM now has a real GPU: the
# ctranslate2/cuBLAS version mismatch that forced CPU on host3 is a
# ctranslate2-vs-CUDA packaging issue, not a host3-specific one, and
# nothing about moving hosts fixes it. Revisit only if ctranslate2 ships
# a build matching whatever CUDA this VM ends up on.

STT_MAX_BYTES = 16_000 * 2 * 120  # 16kHz, 16-bit mono, 2 minutes - generous for a dictated chat message


class WhisperEngine:
    def __init__(self, name: str, model_size: str, device: str, compute_type: str):
        self.name = name
        self.model_size = model_size
        self.device = device
        self.compute_type = compute_type
        self.ready = False
        self._model = None
        self._lock = threading.Lock()

    def load(self):
        from faster_whisper import WhisperModel
        self._model = WhisperModel(self.model_size, device=self.device, compute_type=self.compute_type)
        self.ready = True

    def transcribe(self, samples: np.ndarray) -> str:
        with self._lock:
            segments, _info = self._model.transcribe(samples, language="en", beam_size=1)
            return " ".join(seg.text.strip() for seg in segments).strip()

    def transcribe_streaming(self, samples: np.ndarray, on_segment) -> None:
        with self._lock:
            segments, _info = self._model.transcribe(samples, language="en", beam_size=1)
            for seg in segments:
                on_segment(seg)


# The original three-size A/B set (see host3's git history) was trimmed
# to just medium on 2026-09-19 - loading all three plus Kokoro plus
# Chatterbox didn't fit in this VM's RAM at the time (confirmed live,
# the service OOM-looped 97 times before settling on trimming this).
# small was added back 2026-09-20 ("is there anything we can do to
# speed up the transcription" - CPU whisper-medium has a real, roughly
# duration-independent floor per call, small is meaningfully faster at
# some accuracy cost) - the VM has since grown enough headroom (16GB,
# ~9GB free with both sizes plus Kokoro plus Chatterbox loaded) that
# this pairing is fine; medium is kept as the other apps' own default,
# re-add large here too if ever actually needed for comparison.
STT_ENGINES = {
    "whisper-small-cpu": WhisperEngine("whisper-small-cpu", "small", "cpu", "int8"),
    "whisper-medium-cpu": WhisperEngine("whisper-medium-cpu", "medium", "cpu", "int8"),
}


# ------------------------------------------------------------------ HTTP

@app.get("/status")
def status():
    out = {name: ("ready" if e.ready else "loading") for name, e in ENGINES.items()}
    out.update({name: ("ready" if e.ready else "loading") for name, e in STT_ENGINES.items()})
    return out


@app.get("/stt/models")
def stt_models():
    return {"models": list(STT_ENGINES.keys())}


@app.post("/stt/transcribe")
async def stt_transcribe(request: Request, model: str = "whisper-medium-cpu"):
    engine = STT_ENGINES.get(model)
    if engine is None:
        return JSONResponse({"error": f"unknown model '{model}'"}, status_code=404)
    if not engine.ready:
        return JSONResponse({"error": f"'{model}' is still loading"}, status_code=503)
    body = await request.body()
    if len(body) > STT_MAX_BYTES:
        return JSONResponse({"error": "audio too long"}, status_code=413)
    if len(body) < 4:
        return JSONResponse({"text": ""})
    samples = np.frombuffer(body, dtype=np.int16).astype(np.float32) / 32768.0
    text = await run_in_threadpool(engine.transcribe, samples)
    return JSONResponse({"text": text})


@app.websocket("/stt/stream")
async def stt_stream(websocket: WebSocket):
    client_host = websocket.client.host if websocket.client else None
    if not _security_ok(client_host, websocket.headers):
        await websocket.close(code=4403)
        return

    await websocket.accept()
    try:
        header = await websocket.receive_json()
        model = header.get("model", "whisper-medium-cpu")
        engine = STT_ENGINES.get(model)
        if engine is None:
            await websocket.send_json({"type": "error", "message": f"unknown model '{model}'"})
            return
        if not engine.ready:
            await websocket.send_json({"type": "error", "message": f"'{model}' is still loading"})
            return

        audio_bytes = await websocket.receive_bytes()
        if len(audio_bytes) > STT_MAX_BYTES:
            await websocket.send_json({"type": "error", "message": "audio too long"})
            return
        if len(audio_bytes) < 4:
            await websocket.send_json({"type": "done", "text": ""})
            return

        samples = np.frombuffer(audio_bytes, dtype=np.int16).astype(np.float32) / 32768.0

        total_duration_s = len(samples) / 16000.0

        loop = asyncio.get_event_loop()
        queue: asyncio.Queue = asyncio.Queue()

        def on_segment(seg):
            loop.call_soon_threadsafe(queue.put_nowait, ("segment", seg))

        def run():
            try:
                engine.transcribe_streaming(samples, on_segment)
                loop.call_soon_threadsafe(queue.put_nowait, ("done", None))
            except Exception as e:
                loop.call_soon_threadsafe(queue.put_nowait, ("error", str(e)))

        start_time = time.monotonic()
        threading.Thread(target=run, daemon=True).start()

        text_parts = []
        last_progress = 0.0
        while True:
            try:
                kind, payload = await asyncio.wait_for(queue.get(), timeout=0.7)
            except asyncio.TimeoutError:
                await websocket.send_json({
                    "type": "progress",
                    "progress": last_progress,
                    "text_so_far": " ".join(text_parts).strip(),
                    "elapsed": round(time.monotonic() - start_time, 1),
                })
                continue
            if kind == "segment":
                text_parts.append(payload.text.strip())
                last_progress = min(1.0, payload.end / total_duration_s) if total_duration_s > 0 else 1.0
                await websocket.send_json({
                    "type": "progress",
                    "progress": last_progress,
                    "text_so_far": " ".join(text_parts).strip(),
                    "elapsed": round(time.monotonic() - start_time, 1),
                })
            elif kind == "done":
                await websocket.send_json({"type": "done", "text": " ".join(text_parts).strip()})
                break
            else:
                await websocket.send_json({"type": "error", "message": payload})
                break
    except WebSocketDisconnect:
        pass


@app.get("/voices")
def voices(engine: str = "kokoro"):
    e = ENGINES.get(engine)
    if e is None:
        return JSONResponse({"error": "unknown engine"}, status_code=404)
    return {"engine": engine, "voices": e.voices()}


# ------------------------------------------------------------------- WS
#
# See newsdigest-server.py's original comments (host3) above TTS_LOOKAHEAD_CAP_MS
# and TTS_MAX_CONCURRENT_SYNTHESIS for the incidents that produced these
# values - unchanged here.
TTS_LOOKAHEAD_CAP_MS = 18_000
TTS_MAX_CONCURRENT_SYNTHESIS = 2
_tts_synthesis_semaphore = asyncio.Semaphore(TTS_MAX_CONCURRENT_SYNTHESIS)


@app.websocket("/tts/stream")
async def tts_stream(websocket: WebSocket):
    client_host = websocket.client.host if websocket.client else None
    if not _security_ok(client_host, websocket.headers):
        await websocket.close(code=4403)
        return

    await websocket.accept()
    try:
        req = await websocket.receive_json()
        text = (req.get("text") or "").strip()
        engine_name = req.get("engine", "kokoro")
        voice = req.get("voice")

        engine = ENGINES.get(engine_name)
        if not text:
            await websocket.send_json({"type": "error", "message": "empty text"})
            return
        if engine is None:
            await websocket.send_json({"type": "error", "message": f"unknown engine '{engine_name}'"})
            return
        if not engine.ready:
            await websocket.send_json({"type": "error", "message": f"'{engine_name}' is still loading"})
            return

        cleaned = markdown_to_speech(text)
        sentences = split_sentences(cleaned)

        played_ms = {"value": None}
        disconnected = asyncio.Event()

        async def receive_position_updates():
            try:
                while True:
                    msg = await websocket.receive_json()
                    if msg.get("type") == "position":
                        v = msg.get("played_ms")
                        if isinstance(v, (int, float)) and v >= 0:
                            played_ms["value"] = v
            except WebSocketDisconnect:
                pass
            finally:
                disconnected.set()

        position_task = asyncio.create_task(receive_position_updates())
        try:
            sent_ms = 0
            for sentence in sentences:
                while (
                    played_ms["value"] is not None
                    and sent_ms - played_ms["value"] > TTS_LOOKAHEAD_CAP_MS
                ):
                    if disconnected.is_set():
                        return
                    try:
                        await asyncio.wait_for(disconnected.wait(), timeout=0.25)
                    except asyncio.TimeoutError:
                        pass
                if disconnected.is_set():
                    return
                async with _tts_synthesis_semaphore:
                    if disconnected.is_set():
                        return
                    sent_ms += await synthesize_and_send(websocket, engine, engine_name, sentence, voice)

            await websocket.send_json({"type": "done"})
        finally:
            position_task.cancel()
    except WebSocketDisconnect:
        pass
    except Exception as e:
        try:
            await websocket.send_json({"type": "error", "message": f"{e.__class__.__name__}: {e}"})
        except Exception:
            pass
    finally:
        try:
            await websocket.close()
        except Exception:
            pass


async def synthesize_and_send(
    websocket: WebSocket, engine, engine_name: str, sentence: str, voice: str | None,
) -> int:
    expanded = expand_domains(expand_acronyms(sentence))
    cache_key = _tts_cache_key(engine_name, voice, expanded)
    cached = await run_in_threadpool(_tts_cache_load, cache_key)
    if cached is not None:
        pcm, meta = cached
        duration_ms = meta["duration_ms"]
        await websocket.send_json({
            "type": "sentence",
            "text": sentence,
            "sample_rate": meta["sample_rate"],
            "duration_ms": duration_ms,
            "synth_ms": 0,
            "words": estimate_word_timings(sentence, duration_ms / 1000),
        })
        await websocket.send_bytes(pcm)
        return duration_ms

    t0 = time.monotonic()
    samples, sr = await run_in_threadpool(engine.synthesize, expanded, voice)
    duration_s = len(samples) / sr
    duration_ms = round(duration_s * 1000)
    pcm = float_to_pcm16(samples)
    await run_in_threadpool(_tts_cache_store, cache_key, pcm, {"sample_rate": sr, "duration_ms": duration_ms})
    await websocket.send_json({
        "type": "sentence",
        "text": sentence,
        "sample_rate": sr,
        "duration_ms": duration_ms,
        "synth_ms": round((time.monotonic() - t0) * 1000),
        "words": estimate_word_timings(sentence, duration_s),
    })
    await websocket.send_bytes(pcm)
    return duration_ms


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=BIND_HOST, port=BIND_PORT)
