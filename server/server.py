"""
TTS + feed server for the RSS reader app. Runs both TTS engines resident
in one process (Kokoro on CPU via onnxruntime, Chatterbox on GPU via CUDA)
so the app can pick either from a settings toggle. WireGuard-only: bound
directly to the tunnel interface address (NEWSDIGEST_BIND_HOST), not
0.0.0.0 - unlike the phone's Companion app, a Linux host CAN bind to its
own tunnel interface, so off-tunnel traffic never reaches this socket at
the kernel level at all. The X-Peer-Agent header + Origin rejection below
is defense-in-depth beyond that: other peers sharing the same private
network also run browsers, and a page loaded there could otherwise be
tricked into hitting this port.

Protocol (WebSocket, not plain HTTP streaming): plain HTTP has no clean way
to interleave binary audio with per-sentence word-timing JSON in one
response without inventing a framing format. A WebSocket already frames
messages naturally, so each synthesized sentence arrives as a JSON message
(text, word timings, sample rate) immediately followed by a binary message
(that sentence's raw 16-bit PCM) - the app can start playing sentence 1
while sentence 2 is still synthesizing, and highlight words using the
timing metadata.

Word timings are a proportional-by-character-count estimate against the
engine's real output duration, not a forced alignment - good enough for a
reading-highlight UI without adding a whole alignment model on the
responsiveness-critical path.
"""
import asyncio
import ipaddress
import json
import os
import re
import sys
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool
from starlette.responses import FileResponse, JSONResponse

import feed
from acronyms import expand_acronyms
from text_clean import markdown_to_speech

CLAUDE_BIN = str(Path.home() / ".local" / "bin" / "claude")
ACCOUNT_DIRS = {
    "claude": Path.home() / ".claude",
    "claude2": Path.home() / ".claude2",
    "claude3": Path.home() / ".claude3",
}


def _env_or_fatal(name):
    val = os.environ.get(name)
    if not val:
        sys.stderr.write(f"newsdigest-server: {name} must be set (see README) - refusing to start\n")
        raise SystemExit(1)
    return val


BIND_HOST = _env_or_fatal("NEWSDIGEST_BIND_HOST")
BIND_PORT = int(os.environ.get("NEWSDIGEST_BIND_PORT", "8792"))
ALLOWED_SUBNET = ipaddress.ip_network(_env_or_fatal("NEWSDIGEST_ALLOWED_SUBNET"))
MODEL_DIR = Path.home() / ".cache" / "newsdigest-server"


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Kokoro loads in well under a second, so it's done inline and is
    # already serving by the time this yields. Chatterbox's ~30s CUDA
    # warmup runs in the background instead of blocking startup - /status
    # reports it as "loading" until ready.
    ENGINES["kokoro"].load()
    threading.Thread(target=_load_engine_background, args=(ENGINES["chatterbox"],), daemon=True).start()
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


class SentenceBuffer:
    """Accumulates raw streamed text and yields complete sentences as soon
    as a sentence-ending boundary is seen, so the caller can start
    synthesizing sentence N while sentence N+1 is still being generated.
    Operates on raw (not yet markdown-stripped) text - a period inside a
    URL or code span can occasionally trigger an early split, accepted as
    a rare cosmetic edge case in exchange for not having to buffer the
    entire reply before any audio can start."""

    _BOUNDARY_RE = re.compile(r"[.!?][\"')\]]*\s+")

    def __init__(self):
        self.buf = ""

    def add(self, delta: str) -> list[str]:
        self.buf += delta
        out = []
        while True:
            m = self._BOUNDARY_RE.search(self.buf)
            if not m:
                break
            sentence = self.buf[: m.end()].strip()
            self.buf = self.buf[m.end():]
            if sentence:
                out.append(sentence)
        return out

    def flush(self) -> str | None:
        remaining = self.buf.strip()
        self.buf = ""
        return remaining or None


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
        print(f"[newsdigest-server] {engine.name} failed to load: {e.__class__.__name__}: {e}")


# ------------------------------------------------------------------ HTTP

@app.get("/status")
def status():
    return {name: ("ready" if e.ready else "loading") for name, e in ENGINES.items()}


@app.get("/voices")
def voices(engine: str = "kokoro"):
    e = ENGINES.get(engine)
    if e is None:
        return JSONResponse({"error": "unknown engine"}, status_code=404)
    return {"engine": engine, "voices": e.voices()}


# ------------------------------------------------------------------ feed

@app.get("/feed/items")
def feed_items(limit: int = 50):
    return {"items": feed.list_items(limit=min(limit, 200))}


@app.get("/feed/article")
def feed_article(link: str):
    article = feed.find_article(link)
    if article is None:
        return JSONResponse({"error": "not found"}, status_code=404)
    return article


@app.get("/feed/favicon/{host}")
def feed_favicon(host: str):
    found = feed.favicon_file(host)
    if found is None:
        return JSONResponse({"error": "not found"}, status_code=404)
    path, mime = found
    return FileResponse(path, media_type=mime)


@app.get("/feed/digest")
def feed_digest():
    digest = feed.latest_digest()
    if digest is None:
        return JSONResponse({"error": "no digest yet"}, status_code=404)
    return digest


# ------------------------------------------------------------------- WS

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
        # Sentences are split from the pre-acronym-expansion text, and that
        # is what gets returned in "text"/"words" too - expansion ("RCE" ->
        # "R C E") only happens right before synthesis, inside
        # synthesize_and_send(). Otherwise a client highlighting words
        # against its own normally-rendered display would see "R", "C", "E"
        # as three separate timed words instead of the one word "RCE" it
        # actually shows on screen - confirmed this mismatch live before
        # splitting sentences off the expanded text was fixed here.
        sentences = split_sentences(cleaned)

        for sentence in sentences:
            await synthesize_and_send(websocket, engine, sentence, voice)

        await websocket.send_json({"type": "done"})
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


async def synthesize_and_send(websocket: WebSocket, engine, sentence: str, voice: str | None):
    """sentence is exactly what the client will display and highlight -
    acronym expansion happens only in the copy handed to the engine, never
    reaching the client, so word timings always line up with what's shown
    on screen (see the comment above tts_stream's sentence loop)."""
    expanded = expand_acronyms(sentence)
    t0 = time.monotonic()
    samples, sr = await run_in_threadpool(engine.synthesize, expanded, voice)
    duration_s = len(samples) / sr
    await websocket.send_json({
        "type": "sentence",
        "text": sentence,
        "sample_rate": sr,
        "duration_ms": round(duration_s * 1000),
        "synth_ms": round((time.monotonic() - t0) * 1000),
        "words": estimate_word_timings(sentence, duration_s),
    })
    await websocket.send_bytes(float_to_pcm16(samples))


@app.websocket("/agent/chat")
async def agent_chat(websocket: WebSocket):
    """Drives claude --print --output-format stream-json
    --include-partial-messages directly as a subprocess per turn - no tmux,
    no transcript-file polling. Confirmed live (2026-09-05) that the JSONL
    transcript claude-relay/claude-agents reads for *interactive* sessions
    only gets new lines in bursts (a several-second gap, then ~10 lines at
    once), not incrementally during generation - fine for reading a
    finished reply, useless for starting TTS before the reply is done.
    --include-partial-messages gives real token-level text_delta events on
    this process's own stdout instead, which is what actually lets audio
    start on sentence 1 while the model is still generating sentence 4.

    One `claude --print` subprocess per turn (not one long-lived process
    for the whole conversation) - --session-id on the first turn and
    --resume on every follow-up is what lets this be stateless between
    turns while still being one continuous conversation server-side.
    """
    client_host = websocket.client.host if websocket.client else None
    if not _security_ok(client_host, websocket.headers):
        await websocket.close(code=4403)
        return
    await websocket.accept()

    session_id: str | None = None
    try:
        while True:
            req = await websocket.receive_json()
            cmd = req.get("cmd")
            text = (req.get("text") or "").strip()
            engine_name = req.get("engine", "kokoro")
            voice = req.get("voice")
            account = req.get("account", "claude2")

            if not text:
                await websocket.send_json({"type": "error", "message": "empty text"})
                continue
            engine = ENGINES.get(engine_name)
            if engine is None or not engine.ready:
                await websocket.send_json({"type": "error", "message": f"'{engine_name}' not available"})
                continue
            if account not in ACCOUNT_DIRS:
                await websocket.send_json({"type": "error", "message": f"unknown account '{account}'"})
                continue

            if cmd == "start":
                session_id = str(uuid.uuid4())
                argv = [
                    CLAUDE_BIN, "--print", "--output-format", "stream-json", "--verbose",
                    "--include-partial-messages", "--dangerously-skip-permissions",
                    "--session-id", session_id, text,
                ]
            elif cmd == "continue":
                session_id = req.get("session_id") or session_id
                if not session_id:
                    await websocket.send_json({"type": "error", "message": "no active session - send cmd 'start' first"})
                    continue
                argv = [
                    CLAUDE_BIN, "--print", "--output-format", "stream-json", "--verbose",
                    "--include-partial-messages", "--dangerously-skip-permissions",
                    "--resume", session_id, text,
                ]
            else:
                await websocket.send_json({"type": "error", "message": f"unknown cmd '{cmd}'"})
                continue

            await websocket.send_json({"type": "session", "session_id": session_id})

            env = os.environ.copy()
            env["CLAUDE_CONFIG_DIR"] = str(ACCOUNT_DIRS[account])

            proc = await asyncio.create_subprocess_exec(
                *argv,
                cwd=str(Path.home()),
                env=env,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )

            buf = SentenceBuffer()
            saw_any_event = False
            try:
                async for raw_line in proc.stdout:
                    line = raw_line.decode("utf-8", "replace").strip()
                    if not line:
                        continue
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    saw_any_event = True
                    etype = event.get("type")
                    if etype == "stream_event":
                        inner = event.get("event", {})
                        if inner.get("type") == "content_block_delta":
                            delta = inner.get("delta", {})
                            if delta.get("type") == "text_delta":
                                piece = delta.get("text", "")
                                if piece:
                                    await websocket.send_json({"type": "text_delta", "text": piece})
                                    for raw_sentence in buf.add(piece):
                                        cleaned_sentence = markdown_to_speech(raw_sentence).strip()
                                        if cleaned_sentence:
                                            await synthesize_and_send(websocket, engine, cleaned_sentence, voice)
                    elif etype == "result" and event.get("is_error"):
                        await websocket.send_json({"type": "error", "message": str(event.get("result", "agent error"))})

                await proc.wait()

                if not saw_any_event and proc.returncode != 0:
                    stderr = (await proc.stderr.read()).decode("utf-8", "replace")[:2000]
                    await websocket.send_json({"type": "error", "message": f"claude exited {proc.returncode}: {stderr}"})

                remaining = buf.flush()
                if remaining:
                    cleaned_remaining = markdown_to_speech(remaining).strip()
                    if cleaned_remaining:
                        await synthesize_and_send(websocket, engine, cleaned_remaining, voice)

                await websocket.send_json({"type": "turn_done", "session_id": session_id})
            finally:
                if proc.returncode is None:
                    proc.kill()
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


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=BIND_HOST, port=BIND_PORT)
