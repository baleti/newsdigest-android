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

# The article/digest chat feature doesn't run claude itself - it proxies to
# the claude-agents daemon (~/.config/claude-agents/claude-agents-daemon.py,
# claude-agents.service), the same WireGuard-only HTTP bridge the Claude
# Agents Android app talks to. That daemon owns a real tmux+claude
# interactive session per conversation and already solves everything this
# feature actually needs: a genuinely stateful conversation (not one
# subprocess per turn), resilience to a spotty phone connection (every call
# is a stateless, idempotent, since-cursor'd HTTP request - a dropped
# request just gets retried, nothing to reconnect or resume), a real
# session_id/tmux pane other tools on this machine can attach to or
# `claude --resume`, and durable delivery (a message lands in the live pane
# if one exists, else queues to disk until it does). Reusing it here beats
# reimplementing the same tmux-spawn/poll machinery a second time.
CLAUDE_AGENTS_URL = os.environ.get("NEWSDIGEST_CLAUDE_AGENTS_URL", "http://10.10.0.2:8790")
CLAUDE_AGENTS_TOKEN_FILE = Path.home() / ".config" / "claude-agents" / "token"
ALLOWED_AGENT_ACCOUNTS = {"claude", "claude2", "claude3"}


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


@app.get("/feed/digests")
def feed_digests():
    """{"date": ..., "digests": [{"topic", "markdown", "references"}, ...]}
    - however many topic-clustered digests the last generation run found
    in that day's material (see generate-digest.sh.example); not a fixed
    count or fixed set of topics."""
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


# ------------------------------------------------------------------ agent
#
# Article/digest chat - proxies to the claude-agents daemon rather than
# running claude itself (see CLAUDE_AGENTS_URL's comment above). Every
# endpoint here is a stateless, idempotent HTTP call the daemon can satisfy
# regardless of whether the conversation was started a second ago or three
# hours ago from a different network - that statelessness is exactly what
# makes this resilient to a phone roaming on and off WireGuard mid-chat:
# there is no per-connection state on this server to lose, so a client
# retry after a drop is indistinguishable from the first attempt.

def _agents_token() -> str:
    return CLAUDE_AGENTS_TOKEN_FILE.read_text().strip()


def _agents_call(method: str, path: str, body: dict | None = None, timeout: float = 15.0) -> dict:
    """Blocking (run via run_in_threadpool from async routes below). Talks
    to claude-agents-daemon.py's own HTTP API - see that file's do_GET/
    do_POST for the exact shape of every path used here."""
    import urllib.error
    import urllib.request

    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        CLAUDE_AGENTS_URL + path,
        data=data,
        method=method,
        headers={"X-Claude-Agents-Token": _agents_token(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"claude-agents {e.code}: {detail}")
    except urllib.error.URLError as e:
        raise RuntimeError(f"claude-agents unreachable: {e.reason}")


@app.get("/agent/session")
async def agent_session(run_id: str, topic: str):
    """Does a conversation already exist for this digest topic? If so,
    return it plus its full message history so the app can resume exactly
    where it left off (a fresh app launch, a killed activity, a different
    device even) instead of starting a new one every time chat is opened."""
    session_id = feed.get_chat_session(run_id, topic)
    if not session_id:
        return {"session_id": None, "messages": []}
    try:
        resp = await run_in_threadpool(_agents_call, "GET", f"/api/v1/conversations/{session_id}/messages?since=0")
    except Exception as e:
        # The session id is still real and still worth returning - just
        # couldn't fetch history right now (daemon restarting, etc).
        return {"session_id": session_id, "messages": [], "error": str(e)}
    return {"session_id": session_id, "messages": resp.get("messages", [])}


@app.post("/agent/spawn")
async def agent_spawn(request: Request):
    body = await request.json()
    run_id = (body.get("run_id") or "").strip()
    topic = (body.get("topic") or "").strip()
    text = (body.get("text") or "").strip()
    account = body.get("account", "claude2")
    if not text:
        return JSONResponse({"error": "empty text"}, status_code=400)
    if account not in ALLOWED_AGENT_ACCOUNTS:
        return JSONResponse({"error": f"unknown account '{account}'"}, status_code=400)

    # A conversation for this exact topic may already have been spawned
    # (another device, or this one after a restart) - never spawn a second
    # one out from under it, that would fork the conversation silently.
    existing = feed.get_chat_session(run_id, topic) if run_id and topic else None
    if existing:
        return {"session_id": existing, "resumed": True}

    try:
        resp = await run_in_threadpool(_agents_call, "POST", "/api/v1/spawn", {"account": account, "text": text}, 25.0)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=502)
    session_id = resp.get("session_id")
    if session_id and run_id and topic:
        feed.set_chat_session(run_id, topic, session_id)
    return {"session_id": session_id, "resumed": False}


@app.post("/agent/send")
async def agent_send(request: Request):
    body = await request.json()
    session_id = (body.get("session_id") or "").strip()
    text = (body.get("text") or "").strip()
    msg_id = body.get("id") or str(uuid.uuid4())
    if not session_id or not text:
        return JSONResponse({"error": "session_id and text required"}, status_code=400)
    try:
        resp = await run_in_threadpool(
            _agents_call, "POST", f"/api/v1/conversations/{session_id}/send", {"text": text, "id": msg_id}, 15.0,
        )
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=502)
    return resp


@app.get("/agent/stream")
async def agent_stream(session_id: str, since: int = 0, timeout: float = 25.0):
    """Long-poll: the daemon holds the request open (up to `timeout`, capped
    at 30s server-side) until either new transcript lines exist past
    `since` or the conversation's busy/idle status flips, whichever first -
    this is what the client polls in a tight retry loop instead of holding
    one long-lived connection, so a dropped request just becomes the next
    poll's problem rather than something to detect and reconnect."""
    try:
        resp = await run_in_threadpool(
            _agents_call, "GET",
            f"/api/v1/conversations/{session_id}/stream?since={since}&timeout={min(timeout, 30.0)}",
            None, timeout + 10.0,
        )
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=502)
    return resp



if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=BIND_HOST, port=BIND_PORT)
