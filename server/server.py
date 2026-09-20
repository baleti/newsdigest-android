"""
Feed-digest + agent-chat server for the RSS reader app. WireGuard-only:
bound directly to the tunnel interface address (NEWSDIGEST_BIND_HOST), not
0.0.0.0 - unlike the phone's Companion app, a Linux host CAN bind to its
own tunnel interface, so off-tunnel traffic never reaches this socket at
the kernel level at all. The X-Peer-Agent header + Origin rejection below
is defense-in-depth beyond that: other peers sharing the same private
network also run browsers, and a page loaded there could otherwise be
tricked into hitting this port.

TTS/STT (Kokoro, Chatterbox, faster-whisper) used to live in this same
process - split out 2026-09-19 to ai1, a qemu VM on host1, so voice
synthesis/dictation runs isolated from host3 and Chatterbox gets its own
dedicated GPU instead of sharing host3's. See
~/src/newsdigest-android/tts-stt-server/server.py (deployed to ai1) for
that half.

The app still only ever talks to this one host/port, same as before the
split - /tts/stream, /stt/*, /voices and /status below are a thin proxy
onto ai1's server rather than the app being pointed at a second
host/port. That's deliberate: host1 (and therefore ai1) is only reachable
at all from this host's specific LAN address (see ai1-netup.sh's ufw rule
on host1), not from the phone's WireGuard address - host3 is the one
thing allowed to reach across to it, so it has to be the one place that
does.
"""
import asyncio
import ipaddress
import json
import os
import sys
import uuid
from pathlib import Path

import websockets
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool
from starlette.responses import FileResponse, JSONResponse

import feed

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
CLAUDE_AGENTS_URL = os.environ.get("NEWSDIGEST_CLAUDE_AGENTS_URL", "http://127.0.0.1:8790")
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
# ai1's tts-stt-server, reachable only from this host (see module docstring).
TTS_STT_UPSTREAM = os.environ.get("NEWSDIGEST_TTS_STT_UPSTREAM", "10.176.54.16:8100")


app = FastAPI()


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


# --------------------------------------------------------- tts/stt proxy
#
# Thin proxy onto ai1's tts-stt-server (see module docstring for why this
# is a proxy rather than a second host/port the app points at directly).
# HTTP routes proxy via run_in_threadpool + urllib (same pattern as
# _agents_call below); the two WebSocket routes bridge messages in both
# directions until either side closes.

def _tts_stt_get(path: str, timeout: float = 15.0) -> tuple[int, bytes]:
    import urllib.error
    import urllib.request

    req = urllib.request.Request(f"http://{TTS_STT_UPSTREAM}{path}", headers={"X-Peer-Agent": "1"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def _tts_stt_post(path: str, body: bytes, timeout: float = 30.0) -> tuple[int, bytes]:
    import urllib.error
    import urllib.request

    req = urllib.request.Request(
        f"http://{TTS_STT_UPSTREAM}{path}", data=body, method="POST",
        headers={"X-Peer-Agent": "1", "Content-Type": "application/octet-stream"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


@app.get("/status")
async def status():
    code, body = await run_in_threadpool(_tts_stt_get, "/status")
    return JSONResponse(json.loads(body), status_code=code)


@app.get("/voices")
async def voices(engine: str = "kokoro"):
    code, body = await run_in_threadpool(_tts_stt_get, f"/voices?engine={engine}")
    return JSONResponse(json.loads(body), status_code=code)


@app.get("/stt/models")
async def stt_models():
    code, body = await run_in_threadpool(_tts_stt_get, "/stt/models")
    return JSONResponse(json.loads(body), status_code=code)


@app.post("/stt/transcribe")
async def stt_transcribe(request: Request, model: str = "whisper-medium-cpu"):
    body = await request.body()
    code, resp_body = await run_in_threadpool(_tts_stt_post, f"/stt/transcribe?model={model}", body)
    return JSONResponse(json.loads(resp_body), status_code=code)


async def _ws_proxy(client_ws: WebSocket, upstream_path: str):
    client_host = client_ws.client.host if client_ws.client else None
    if not _security_ok(client_host, client_ws.headers):
        await client_ws.close(code=4403)
        return
    await client_ws.accept()

    try:
        # max_size defaults to 1 MiB in the websockets library - too small
        # for a raw PCM16 audio frame from a long sentence (easily 1.5MB+
        # at 24kHz mono), so ai1 forcibly closed the connection mid-stream
        # with "1009 message too big" the moment one came through -
        # reported live 2026-09-20 as "read aloud button in news digest
        # stopped working" right after the TTS/STT split to ai1 introduced
        # this proxy hop. 16MB is comfortably above any single sentence's
        # audio while still bounded (not None/unlimited) against a
        # runaway response.
        upstream = await websockets.connect(
            f"ws://{TTS_STT_UPSTREAM}{upstream_path}", additional_headers={"X-Peer-Agent": "1"},
            max_size=16 * 1024 * 1024,
        )
    except Exception as e:
        try:
            await client_ws.send_json({"type": "error", "message": f"ai1 unreachable: {e}"})
        except Exception:
            pass
        await client_ws.close()
        return

    async def from_client():
        try:
            while True:
                msg = await client_ws.receive()
                if msg["type"] == "websocket.disconnect":
                    break
                if (text := msg.get("text")) is not None:
                    await upstream.send(text)
                elif (data := msg.get("bytes")) is not None:
                    await upstream.send(data)
        except WebSocketDisconnect:
            pass

    async def from_upstream():
        async for message in upstream:
            if isinstance(message, bytes):
                await client_ws.send_bytes(message)
            else:
                await client_ws.send_text(message)

    tasks = [asyncio.create_task(from_client()), asyncio.create_task(from_upstream())]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for t in done:
            if t.cancelled():
                continue
            exc = t.exception()
            if exc is not None:
                print(f"[newsdigest-server] tts/stt proxy task error: {exc.__class__.__name__}: {exc}")
    finally:
        for t in tasks:
            t.cancel()
        await upstream.close()
        try:
            await client_ws.close()
        except Exception:
            pass


@app.websocket("/tts/stream")
async def tts_stream(websocket: WebSocket):
    await _ws_proxy(websocket, "/tts/stream")


@app.websocket("/stt/stream")
async def stt_stream(websocket: WebSocket):
    await _ws_proxy(websocket, "/stt/stream")


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
    count or fixed set of topics. Kept for compatibility - the app itself
    now calls /feed/digests/history to also see older runs."""
    digest = feed.latest_digest()
    if digest is None:
        return JSONResponse({"error": "no digest yet"}, status_code=404)
    return digest


@app.get("/feed/digests/history")
def feed_digests_history(limit: int = 30):
    """[{"date", "run_id", "digests": [...]}, ...], newest run first - every
    dated run still on disk (build_digest_json.py's own retention curve
    decides how far back that goes), not just the latest one."""
    return {"runs": feed.list_digest_runs(limit=min(limit, 100))}


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
    # loop="asyncio", not uvicorn's uvloop default: the /tts/stream and
    # /stt/stream proxy routes open their own outbound client connection
    # (websockets.connect) from inside a request handler - under uvloop
    # this reproduced as either an immediate ECONNREFUSED or a silent hang
    # on that outbound connect (confirmed live 2026-09-19: identical code
    # worked instantly as a bare `python3 -c` script using the default
    # asyncio loop, failed inconsistently only when run inside uvicorn's
    # uvloop-based server). Not a hot enough path to need uvloop's speed.
    #
    # ws_max_size: the 16MB override on the ai1-facing websockets.connect()
    # call (see _ws_proxy above) only covers that outbound leg. This is the
    # OTHER leg - uvicorn's own ASGI websocket server handling the phone's
    # inbound connection - which still used its 1MiB default and hit the
    # same "1009 message too big" failure from the other side (confirmed
    # live 2026-09-20 via newsdigest-server's own log: "sent 1009 ... frame
    # with 1194213 bytes exceeds limit of 1048576 bytes"). Match the same
    # 16MB bound here for consistency.
    uvicorn.run(app, host=BIND_HOST, port=BIND_PORT, loop="asyncio", ws_max_size=16 * 1024 * 1024)
