#!/usr/bin/env python3
"""GPU model-swap arbiter for ai1. Any process on this VM that loads a model
onto a shared GPU registers it here (a name + a URL the arbiter can POST to
to ask it to unload); before actually using that model it calls `/acquire`,
which evicts whoever currently holds the slot first if it's someone else.
stdlib only, loopback-bound (127.0.0.1:8101) - every caller lives on this
same VM, so there's no wider auth surface needed.

Built 2026-09-22 after tts-stt-server.py's own in-process version (whisper-
medium-gpu vs a second Chatterbox instance, both wanting GPU 1) needed to
also arbitrate with imagechat's chat_server.py - a wholly separate process,
also on GPU 1, also wanting most of that card when generating an image.
A plain threading.Lock can't coordinate across processes, hence a small
shared service instead. Adding a fourth GPU-hungry participant later is
three HTTP calls from that service (register once at startup, acquire
before each use, optionally release when going idle) plus one evict route
it exposes - nothing in this file needs to change.

Correctness notes for anyone extending a participant's client side (see
tts-stt-server.py's RemoteGpuModel for the reference implementation):
- Eviction must never land mid-use. A participant's own evict endpoint has
  to block on the SAME lock its `use()`-equivalent holds while the model is
  actually being called (not just while `/acquire` is in flight) - the
  2026-09-22 STT corruption incident this was built to prevent was exactly
  "the model got unloaded while something was still transcribing with it."
- A participant's `/acquire` call must NOT hold that same lock across the
  network round-trip to this service, or a concurrent eviction of a
  DIFFERENT local model on the same process (which this service needs to
  call back into, synchronously, before granting the acquire) will
  deadlock against it. Grab the exclusivity lock only after `/acquire`
  returns; give each locally-registered model its own lock, not one
  lock shared across all of a process's own models, so evicting model A
  is never blocked on model B's in-flight use.
"""
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BIND_HOST = "127.0.0.1"
PORT = int(__import__("os").environ.get("GPU_MANAGER_PORT", "8101"))
EVICT_TIMEOUT = 60  # seconds - generous; a cold model load/unload has taken up to ~40s seen live


class Slot:
    def __init__(self, name: str):
        self.name = name
        self.lock = threading.RLock()
        self.resident: str | None = None
        self.models: dict[str, str] = {}  # model_name -> evict_url

    def register(self, model: str, evict_url: str) -> None:
        with self.lock:
            self.models[model] = evict_url

    def acquire(self, model: str) -> None:
        if model not in self.models:
            raise KeyError(model)
        with self.lock:
            if self.resident == model:
                return
            if self.resident is not None and self.resident in self.models:
                _evict(self.models[self.resident], self.resident)
            self.resident = model

    def release(self, model: str) -> None:
        with self.lock:
            if self.resident == model:
                self.resident = None

    def status(self) -> dict:
        with self.lock:
            return {"resident": self.resident, "registered": sorted(self.models)}


def _evict(evict_url: str, model: str) -> None:
    # X-Peer-Agent: 1 matches this codebase's usual same-host/tunnel auth
    # header (see tts-stt-server.py's security_middleware) - harmless for a
    # participant that doesn't check it (e.g. chat_server.py), required for
    # one that does.
    req = urllib.request.Request(
        evict_url, data=b"{}", method="POST",
        headers={"Content-Type": "application/json", "X-Peer-Agent": "1"},
    )
    try:
        with urllib.request.urlopen(req, timeout=EVICT_TIMEOUT) as resp:
            if resp.status != 200:
                raise RuntimeError(f"evict {model!r} -> {evict_url} returned HTTP {resp.status}")
    except urllib.error.URLError as e:
        raise RuntimeError(f"evict {model!r} -> {evict_url} failed: {e}") from e


_slots: dict[str, Slot] = {}
_slots_lock = threading.Lock()


def get_slot(name: str) -> Slot:
    with _slots_lock:
        slot = _slots.get(name)
        if slot is None:
            slot = _slots[name] = Slot(name)
        return slot


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        pass

    def _send(self, code: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if self.path == "/status":
            with _slots_lock:
                out = {name: slot.status() for name, slot in _slots.items()}
            return self._send(200, out)
        self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            body = self._read_json()
        except Exception:
            return self._send(400, {"error": "bad json"})

        slot_name = body.get("slot")
        model = body.get("model")
        if not slot_name or not model:
            return self._send(400, {"error": "'slot' and 'model' are required"})
        slot = get_slot(slot_name)

        if self.path == "/register":
            evict_url = body.get("evict_url")
            if not evict_url:
                return self._send(400, {"error": "'evict_url' is required"})
            slot.register(model, evict_url)
            return self._send(200, {"ok": True})

        if self.path == "/acquire":
            try:
                slot.acquire(model)
            except KeyError:
                return self._send(404, {"error": f"{model!r} is not registered on slot {slot_name!r}"})
            except RuntimeError as e:
                return self._send(502, {"error": str(e)})
            return self._send(200, {"ok": True, "resident": model})

        if self.path == "/release":
            slot.release(model)
            return self._send(200, {"ok": True})

        self._send(404, {"error": "not found"})


def main():
    srv = ThreadingHTTPServer((BIND_HOST, PORT), Handler)
    print(f"{time.strftime('%T')} gpu-model-manager on {BIND_HOST}:{PORT}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
