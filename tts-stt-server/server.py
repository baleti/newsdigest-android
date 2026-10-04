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
import contextvars
import hashlib
import ipaddress
import json
import os
import queue
import re
import sys
import threading
import time
from contextlib import asynccontextmanager, contextmanager
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
    # No separate registration step here - RemoteGpuModel.use() registers
    # on every call (see its own comment for why), so whichever GPU1
    # participant's background load thread runs first just works.
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


# -------------------------------------------------------------- progress
#
# Human-readable "what is the server doing right now" steps, streamed to
# the client as {"type": "status", "message": ..., "sentence": k, "of": n}
# so its "Synthesizing..." banner can say more than a countdown (cold model
# load, waiting on the GPU arbiter, evicting another model, ...). Engine
# code runs in a worker thread, far from the websocket, so the sink is a
# ContextVar: tts_stream's per-sentence task sets it, and
# run_in_threadpool copies the context into the worker thread. Calling
# _progress() outside a stream (e.g. startup loading) is a no-op.
_progress_sink: contextvars.ContextVar = contextvars.ContextVar("tts_progress_sink", default=None)


def _progress(message: str) -> None:
    sink = _progress_sink.get()
    if sink is not None:
        try:
            sink(message)
        except Exception:
            pass


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


def _tts_cache_key(engine_name: str, voice: str | None, expanded_text: str, extra: str = "") -> str:
    raw = f"{engine_name}\x00{voice or ''}\x00{extra}\x00{expanded_text}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _chatterbox_cache_extra() -> str:
    """Folded into chatterbox's cache key so a sentence cached under
    yesterday's voice-of-the-day never gets served back today under the
    wrong voice - without this, daily rotation would be invisible for any
    sentence that happened to already be cached (a real bug caught live
    2026-09-23 testing this feature: a just-added rotation produced
    synth_ms=0 cache hits instead of exercising the new voice at all).
    Old entries just age out via the existing cache size eviction -
    nothing needs to actively purge them."""
    voices = _chatterbox_voice_library()
    if not voices:
        return ""
    return voices[_todays_chatterbox_voice_index(len(voices))].stem


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
        if self._lock.locked():
            _progress("Waiting for Kokoro to finish another sentence")
        with self._lock:
            _progress("Generating speech with Kokoro (CPU)")
            samples, sr = self._kokoro.create(text, voice=voice or "af_heart", speed=1.0, lang="en-us")
        return samples, sr


# Chatterbox voices a different day each other than a small fixed preset
# list (which it doesn't have) - it does zero-shot voice cloning from a
# reference clip (ChatterboxTTS.generate's `audio_prompt_path`, or the
# separate `prepare_conditionals(wav_path)` to set it once and reuse for
# many generate() calls rather than re-encoding the reference every
# sentence). Asked for explicitly 2026-09-22 ("does chatterbox offer
# different voices? could we have a different voice set at every day...
# dozens, different one every day") - the reference clips themselves are
# kokoro's own 28 built-in voices (already licensed/bundled, zero rights
# question, zero real-person impersonation risk), synthesized once into
# this directory and cloned by Chatterbox from there. 28 voices -> the
# rotation repeats every 28 days, comfortably "dozens... different one
# every day".
CHATTERBOX_VOICE_LIBRARY_DIR = Path.home() / ".cache" / "ai1-tts-stt-server" / "chatterbox-voices"


def _chatterbox_voice_library() -> list[Path]:
    if not CHATTERBOX_VOICE_LIBRARY_DIR.is_dir():
        return []
    return sorted(CHATTERBOX_VOICE_LIBRARY_DIR.glob("*.wav"))


def _todays_chatterbox_voice_index(count: int) -> int:
    import datetime
    return datetime.date.today().toordinal() % count


class ChatterboxEngine:
    def __init__(self, device: str = "cuda"):
        self.name = "chatterbox"
        self.device = device
        self.ready = False
        self._model = None
        self._lock = threading.Lock()
        # Which library index is currently prepare_conditionals()-ed onto
        # the model, if any - re-checked (cheap) on every synthesize() call
        # so a day rollover takes effect without needing to reload the
        # whole model, but the (not-quite-as-cheap) actual re-prep only
        # happens on the rare call where the day has genuinely changed.
        self._prepared_voice_index: int | None = None

    def load(self):
        import torch  # noqa: F401 - import here, not at module scope, so a CUDA hiccup can't block Kokoro from serving
        from chatterbox.tts import ChatterboxTTS
        self._model = ChatterboxTTS.from_pretrained(device=self.device)
        self._prepared_voice_index = None
        self.ready = True
        self._apply_todays_voice_locked()

    def unload(self):
        with self._lock:
            self._model = None
            self.ready = False
        import gc
        import torch
        gc.collect()
        torch.cuda.empty_cache()

    def _apply_todays_voice_locked(self) -> None:
        """Caller must hold self._lock. No-op if today's voice is already
        the one prepared (the common case - only actually re-runs
        prepare_conditionals when the day has rolled over since the last
        call, or on first load)."""
        voices = _chatterbox_voice_library()
        if not voices:
            return
        idx = _todays_chatterbox_voice_index(len(voices))
        if idx == self._prepared_voice_index:
            return
        try:
            _progress(f"Preparing Chatterbox voice '{voices[idx].stem}'")
            self._model.prepare_conditionals(str(voices[idx]), exaggeration=0.5)
            self._prepared_voice_index = idx
        except Exception as e:
            print(f"[ai1-tts-stt-server] chatterbox voice prep failed for {voices[idx]}: {e}", flush=True)

    def voices(self) -> list[str]:
        return ["default"]

    def synthesize(self, text: str, voice: str | None) -> tuple[np.ndarray, int]:
        with self._lock:
            self._apply_todays_voice_locked()
            _progress(f"Generating speech with Chatterbox on {self.device}")
            wav = self._model.generate(text)
        return wav.squeeze().numpy(), self._model.sr


# ------------------------------------------------------------- GPU arbiter
#
# GPU 1 (6GB) can't permanently hold everything that wants it: whisper-medium-gpu
# (~0.9GB) and a second Chatterbox instance (~3.5GB) fit together fine (confirmed
# live 2026-09-22 - 1.6GB to spare), but ai1's separate image-gen process
# (chat_server.py's sd-server, a DIFFERENT OS process from this one) also wants
# this same card and needs most of its 6GB when active. A plain in-process lock
# can't coordinate across processes, so residency is arbitrated by
# gpu-model-manager.py - a tiny standalone stdlib HTTP service on this same VM
# (127.0.0.1:8101) that any GPU-hungry service registers a model with. See that
# file's docstring for the full protocol and, importantly, the deadlock/
# evict-mid-use correctness notes - this class follows that contract exactly:
# `use()` does NOT hold `lock` across the network round-trip to `/acquire`
# (only after it returns), and each RemoteGpuModel gets its OWN lock rather
# than a lock shared across everything this process registers, so evicting
# model A here is never blocked behind model B's in-flight use.
#
# 2026-09-22 incident: an earlier, purely in-process version of this file's
# GPU-1 swapping held one shared lock and evicted synchronously mid-request,
# and something in that window corrupted whisper-medium-gpu's CUDA context -
# real dictation came back as garbage until the process was restarted. This
# design (separate per-model locks, arbiter never called while holding one)
# is the fix; don't collapse it back into a single shared lock.
GPU_MANAGER_URL = os.environ.get("GPU_MANAGER_URL", "http://127.0.0.1:8101")


def _gpu_manager_post(path: str, body: dict, timeout: float = 60.0) -> dict:
    import urllib.error
    import urllib.request
    req = urllib.request.Request(
        f"{GPU_MANAGER_URL}{path}", data=json.dumps(body).encode(),
        method="POST", headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())


class RemoteGpuModel:
    """This process's membership in one shared GPU slot. `own_evict_url` must
    already be routed to call `evict()` before `register()` runs - see the
    `/internal/gpu/evict/{model}` route below."""

    def __init__(self, slot: str, model: str, own_evict_url: str, unload_fn):
        self.slot = slot
        self.model = model
        self.own_evict_url = own_evict_url
        self.unload_fn = unload_fn
        self.lock = threading.Lock()

    def register(self) -> None:
        _gpu_manager_post("/register", {"slot": self.slot, "model": self.model, "evict_url": self.own_evict_url})

    @contextmanager
    def use(self):
        with self.lock:
            # Registering on every use (not just once at startup) is
            # deliberate, not laziness: it's what closes a real startup
            # race - lifespan() used to kick off registration and the
            # eager whisper-medium-gpu load in separate, unordered
            # background threads, and the load's first `/acquire` call
            # would 404 if it won the race against `/register` (confirmed
            # live 2026-09-22 in the offline integration test this class
            # was validated with). Registration is a cheap dict update on
            # the arbiter side (no eviction happens on register), so
            # paying for it on every use is a non-issue.
            self.register()
            _progress(f"Asking the GPU arbiter for {self.slot} (another model may be evicted)")
            t0 = time.monotonic()
            _gpu_manager_post("/acquire", {"slot": self.slot, "model": self.model})
            waited = time.monotonic() - t0
            if waited >= 1.0:
                _progress(f"Got {self.slot} after {waited:.0f}s")
            yield

    def evict(self) -> None:
        with self.lock:
            self.unload_fn()


GPU1_PARTICIPANTS: dict[str, RemoteGpuModel] = {}  # populated below and after WhisperEngine


class ChatterboxPool:
    """Chatterbox synthesis across two instances: `primary` is permanently
    resident on GPU 0, `secondary` lives on GPU 1 behind `slot` and only
    loads (or reloads, if it was evicted since) when actually needed.
    `synthesize()` hands out whichever instance is free - with both
    available, two sentences can generate at once instead of queuing behind
    one GPU (see tts_stream's pipelined lookahead).
    """

    name = "chatterbox"

    def __init__(self, primary: ChatterboxEngine, slot: RemoteGpuModel, secondary: ChatterboxEngine):
        self._primary = primary
        self._slot = slot
        self._secondary = secondary
        self._free: queue.Queue = queue.Queue()

    @property
    def ready(self) -> bool:
        return self._primary.ready

    def load(self):
        self._primary.load()
        self._free.put("primary")
        self._free.put("secondary")

    def voices(self) -> list[str]:
        return ["default"]

    def synthesize(self, text: str, voice: str | None) -> tuple[np.ndarray, int]:
        if self._free.empty():
            _progress("Both Chatterbox instances are busy - waiting for one to free up")
        slot_id = self._free.get()
        try:
            if slot_id == "primary":
                return self._primary.synthesize(text, voice)
            with self._slot.use():
                if not self._secondary.ready:
                    _progress("Loading Chatterbox onto the second GPU (about 15-20s)")
                    self._secondary.load()
                return self._secondary.synthesize(text, voice)
        finally:
            self._free.put(slot_id)


_chatterbox_secondary = ChatterboxEngine(device="cuda:1")
_chatterbox_b_slot = RemoteGpuModel(
    "gpu1", "chatterbox-b",
    f"http://127.0.0.1:{BIND_PORT}/internal/gpu/evict/chatterbox-b",
    _chatterbox_secondary.unload,
)
GPU1_PARTICIPANTS["chatterbox-b"] = _chatterbox_b_slot

ENGINES = {
    "kokoro": KokoroEngine(),
    "chatterbox": ChatterboxPool(ChatterboxEngine(device="cuda:0"), _chatterbox_b_slot, _chatterbox_secondary),
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

STT_MAX_BYTES = 16_000 * 2 * 60 * 15  # 16kHz, 16-bit mono, 15 minutes - not removed outright even though
# this endpoint is already gated to loopback/ALLOWED_SUBNET (_security_ok): a generous but still-bounded
# cap is cheap insurance against an oversized body wedging memory if that gate were ever bypassed by a bug.


class WhisperEngine:
    # device_index matters here specifically because this VM has TWO
    # GPUs (confirmed live 2026-09-20 while chasing "is there anything
    # we can do to speed up the transcription") - GPU 0 holds the primary
    # Chatterbox instance. Defaults to 0 so a device="cpu" engine
    # (device_index is simply unused then) and any future device="cuda"
    # caller that doesn't care both keep working unchanged; the GPU
    # engine below passes 1 explicitly. GPU 1 is no longer exclusively
    # this engine's - see the GPU arbiter section above - so this model
    # can be unloaded and reloaded on demand, not just loaded once at
    # startup.
    def __init__(self, name: str, model_size: str, device: str, compute_type: str, device_index: int = 0):
        self.name = name
        self.model_size = model_size
        self.device = device
        self.compute_type = compute_type
        self.device_index = device_index
        self.ready = False
        self._model = None
        self._lock = threading.Lock()

    def load(self):
        from faster_whisper import WhisperModel
        kwargs = {"device_index": self.device_index} if self.device == "cuda" else {}
        self._model = WhisperModel(self.model_size, device=self.device, compute_type=self.compute_type, **kwargs)
        self.ready = True

    def unload(self):
        with self._lock:
            self._model = None
            self.ready = False
        if self.device == "cuda":
            import gc
            gc.collect()
            try:
                import torch
                torch.cuda.empty_cache()
            except Exception:
                pass

    def transcribe(self, samples: np.ndarray) -> str:
        with self._lock:
            segments, _info = self._model.transcribe(samples, language="en", beam_size=1)
            return " ".join(seg.text.strip() for seg in segments).strip()

    def transcribe_streaming(self, samples: np.ndarray, on_segment) -> None:
        with self._lock:
            segments, _info = self._model.transcribe(samples, language="en", beam_size=1)
            for seg in segments:
                on_segment(seg)


class SlotWhisperProxy:
    """A WhisperEngine that lives behind a RemoteGpuModel instead of being
    permanently resident - same interface (name/ready/transcribe/
    transcribe_streaming), so the rest of the file doesn't need to know
    whether whisper-medium-gpu is actually loaded at any given moment."""

    def __init__(self, slot: RemoteGpuModel, inner: WhisperEngine):
        self.name = inner.name
        self._slot = slot
        self._inner = inner
        self.ready = False

    def load(self):
        with self._slot.use():
            self._inner.load()
        self.ready = True

    def transcribe(self, samples: np.ndarray) -> str:
        with self._slot.use():
            if not self._inner.ready:
                self._inner.load()
            return self._inner.transcribe(samples)

    def transcribe_streaming(self, samples: np.ndarray, on_segment) -> None:
        with self._slot.use():
            if not self._inner.ready:
                self._inner.load()
            return self._inner.transcribe_streaming(samples, on_segment)


# Only the app's actual default (Settings.kt DEFAULT_STT-equivalent) loads
# eagerly here - the original three-size A/B set (see host3's git history)
# was for comparing sizes live while the feature was new, not a hard
# requirement, and eagerly loading all three plus Kokoro plus Chatterbox
# doesn't fit in this VM's 6GB: confirmed live 2026-09-19, the service
# OOM-looped 97 times in this VM's guest kernel before settling on trimming
# this. Re-add a size here (and bump ai1's RAM in qemu-ai1.service to
# match) if you actually need to compare them again.
#
# whisper-medium-gpu added the same day, right after: the CPU-only
# constraint here was never actually a hard CUDA-version wall the way
# host3's original investigation concluded - confirmed live that the
# "libcublas.so.12 not found" error was just ctranslate2 not being
# pointed at the nvidia-cublas-cu12 pip package this venv already has
# installed (needs LD_LIBRARY_PATH set at process launch, see
# ai1-tts-stt-server.service.example). Once that's set, GPU medium
# transcribes in ~0.5-0.6s regardless of clip length (vs ~13s on CPU) -
# device_index=1 specifically to land on the second GPU in this VM
# rather than contending with the primary Chatterbox instance on GPU 0.
# Registered with the gpu-model-manager arbiter (see RemoteGpuModel above)
# rather than loaded unconditionally: a second Chatterbox instance and
# ai1's separate image-gen process also want this card now, so this model
# gets evicted/reloaded on demand instead of staying permanently resident.
_whisper_medium_gpu = WhisperEngine("whisper-medium-gpu", "medium", "cuda", "int8", device_index=1)
_whisper_gpu1_slot = RemoteGpuModel(
    "gpu1", "whisper-medium-gpu",
    f"http://127.0.0.1:{BIND_PORT}/internal/gpu/evict/whisper-medium-gpu",
    _whisper_medium_gpu.unload,
)
GPU1_PARTICIPANTS["whisper-medium-gpu"] = _whisper_gpu1_slot

STT_ENGINES = {
    "whisper-small-cpu": WhisperEngine("whisper-small-cpu", "small", "cpu", "int8"),
    "whisper-medium-cpu": WhisperEngine("whisper-medium-cpu", "medium", "cpu", "int8"),
    "whisper-medium-gpu": SlotWhisperProxy(_whisper_gpu1_slot, _whisper_medium_gpu),
}


# ------------------------------------------------------------------ HTTP

@app.get("/status")
def status():
    out = {name: ("ready" if e.ready else "loading") for name, e in ENGINES.items()}
    out.update({name: ("ready" if e.ready else "loading") for name, e in STT_ENGINES.items()})
    try:
        import urllib.request
        with urllib.request.urlopen(f"{GPU_MANAGER_URL}/status", timeout=2) as resp:
            out["gpu1_resident"] = json.loads(resp.read()).get("gpu1", {}).get("resident")
    except Exception:
        out["gpu1_resident"] = "unknown (arbiter unreachable)"
    voices = _chatterbox_voice_library()
    if voices:
        out["chatterbox_voice_today"] = voices[_todays_chatterbox_voice_index(len(voices))].stem
    return out


@app.post("/internal/gpu/evict/{model}")
def gpu_evict(model: str):
    participant = GPU1_PARTICIPANTS.get(model)
    if participant is None:
        return JSONResponse({"error": f"unknown model {model!r}"}, status_code=404)
    participant.evict()
    return {"ok": True}


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
# See newsdigest-server.py's original comments (host3) above
# TTS_MAX_CONCURRENT_SYNTHESIS for the incident that produced that value -
# unchanged. TTS_LOOKAHEAD_CAP_MS bumped 18s -> 30s and decoupled from
# TTS_MAX_CONCURRENT_SYNTHESIS on 2026-09-22 (see fill_pipeline's own doc) -
# the two were never the same thing: this is how far ahead of PLAYBACK the
# pipeline is allowed to buffer, that's how many sentences may synthesize
# on the GPU(s) at once. Conflating them meant the buffer could never get
# more than 2 sentences deep regardless of this constant.
TTS_LOOKAHEAD_CAP_MS = 30_000
TTS_MAX_CONCURRENT_SYNTHESIS = 2
_tts_synthesis_semaphore = asyncio.Semaphore(TTS_MAX_CONCURRENT_SYNTHESIS)
# Kokoro is CPU/onnx, Chatterbox is GPU - a backlog of Chatterbox work must
# never make a Kokoro request wait, so Kokoro gets its own gate.
_kokoro_synthesis_semaphore = asyncio.Semaphore(TTS_MAX_CONCURRENT_SYNTHESIS)


def _estimate_sentence_ms(sentence: str) -> float:
    """Rough pre-synthesis duration guess (same ~160wpm pacing assumption
    the Android clients use for their own upfront estimates) - used only to
    gate how far ahead fill_pipeline() is willing to keep starting sentences
    before any of them have a real duration_ms yet. Replaced by the real
    value the moment each sentence's own synthesis actually completes."""
    words = len(sentence.split())
    return max(500.0, words / (160.0 / 60.0) * 1000)


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

        # Progress steps from the engines (see _progress): worker threads
        # push onto status_q thread-safely, one forwarder task relays them.
        # send_lock keeps a status frame from landing between a sentence's
        # JSON header and its binary audio frame.
        loop = asyncio.get_running_loop()
        status_q: asyncio.Queue = asyncio.Queue()
        send_lock = asyncio.Lock()

        async def forward_status():
            last = None
            while True:
                item = await status_q.get()
                if item["message"] == last:
                    continue
                last = item["message"]
                async with send_lock:
                    await websocket.send_json({"type": "status", **item})

        status_task = asyncio.create_task(forward_status())
        status_q.put_nowait({"message": f"Starting {engine_name}: {len(sentences)} sentences to synthesize", "sentence": 0, "of": len(sentences)})

        # Sentences synthesize in parallel (lookahead pipeline), so their
        # steps interleave and would read as noise ("[31] generating, [30]
        # waiting for GPU, ..."). Only the sentence playback is actually
        # blocked on - the next one to be SENT - is forwarded live; the
        # others' latest step is parked and replayed the moment they
        # become the blocking one.
        blocking = {"idx": 0}
        latest: dict[int, dict] = {}

        def on_status(idx: int, total: int, message: str) -> None:
            item = {"message": message, "sentence": idx + 1, "of": total}
            latest[idx] = item
            if idx == blocking["idx"]:
                status_q.put_nowait(item)

        def advance_blocking(new_idx: int) -> None:
            latest.pop(blocking["idx"], None)
            blocking["idx"] = new_idx
            item = latest.get(new_idx)
            if item is not None:
                status_q.put_nowait(item)

        def make_emit(idx: int, total: int):
            def emit(message: str) -> None:
                loop.call_soon_threadsafe(on_status, idx, total, message)
            return emit

        pending: dict[int, asyncio.Task] = {}
        try:
            sent_ms = 0
            n = len(sentences)
            # Pipelined, not sequential: up to TTS_MAX_CONCURRENT_SYNTHESIS
            # sentences synthesize at once (this is what actually exercises
            # both Chatterbox instances - ChatterboxPool.synthesize()'s own
            # free-queue is what splits concurrent calls across GPU 0 and
            # GPU 1). Results are still SENT in order - synthesis can finish
            # out of order, playback can't. Before this, the loop awaited
            # each sentence fully before starting the next, so a second
            # GPU/Chatterbox instance sat idle for a single continuous read
            # no matter how many were available (reported live 2026-09-22:
            # "read aloud is really slow... given that we now have
            # Chatterbox running on both GPUs" - the instances existed,
            # nothing here was ever issuing overlapping requests to use them).
            next_to_start = 0
            next_to_send = 0
            # Rough duration of sentences currently pending (started, not
            # yet sent - so not yet reflected in sent_ms). Lets the
            # lookahead check see "sent + being-synthesized" instead of
            # just "sent", which is what actually lets it keep filling the
            # pipeline many sentences deep instead of stalling after 1.
            pending_estimate_ms = 0.0

            def lookahead_ok() -> bool:
                if played_ms["value"] is None:
                    return True
                return (sent_ms + pending_estimate_ms) - played_ms["value"] <= TTS_LOOKAHEAD_CAP_MS

            def fill_pipeline() -> None:
                # No concurrency cap here on purpose - only how far AHEAD
                # (in estimated ms) we're willing to buffer. Actual GPU
                # concurrency is capped elsewhere (_tts_synthesis_semaphore,
                # and ChatterboxPool's own 2-instance free-queue): a task
                # started here beyond what the hardware can run right now
                # just blocks inside run_in_threadpool() waiting for a free
                # instance, same as it always would - starting it early
                # costs nothing but a queued asyncio task, and means the
                # instant a GPU frees up it picks up the NEXT sentence
                # immediately instead of waiting for this stream to notice
                # and ask for it. Before this had `len(pending) <
                # TTS_MAX_CONCURRENT_SYNTHESIS` here too, which meant the
                # pipeline could never hold more than 2 sentences no matter
                # how large TTS_LOOKAHEAD_CAP_MS was - confirmed live
                # 2026-09-22: sentences 1+2 synthesized in parallel as
                # intended, then nothing started sentence 3 until sentence
                # 1 was fully SENT (not just synthesized), producing a long
                # audible stall right when the buffer should have already
                # been several sentences deep.
                nonlocal next_to_start, pending_estimate_ms
                while next_to_start < n and lookahead_ok():
                    idx = next_to_start
                    next_to_start += 1
                    pending_estimate_ms += _estimate_sentence_ms(sentences[idx])
                    pending[idx] = asyncio.create_task(
                        _synthesize_sentence(engine, engine_name, sentences[idx], voice, make_emit(idx, n))
                    )

            fill_pipeline()
            while next_to_send < n:
                if disconnected.is_set():
                    return
                task = pending.get(next_to_send)
                if task is None:
                    # Nothing pending for the next sentence yet - either the
                    # lookahead cap is holding it back (client is far behind),
                    # or (shouldn't happen) it just hasn't been scheduled.
                    # Wait a beat and recheck rather than busy-loop.
                    try:
                        await asyncio.wait_for(disconnected.wait(), timeout=0.25)
                    except asyncio.TimeoutError:
                        pass
                    fill_pipeline()
                    continue
                pcm, meta = await task
                del pending[next_to_send]
                pending_estimate_ms = max(0.0, pending_estimate_ms - _estimate_sentence_ms(sentences[next_to_send]))
                if disconnected.is_set():
                    return
                async with send_lock:
                    sent_ms += await _send_sentence(websocket, pcm, meta)
                next_to_send += 1
                advance_blocking(next_to_send)
                fill_pipeline()

            await websocket.send_json({"type": "done"})
        finally:
            position_task.cancel()
            status_task.cancel()
            # A client that leaves (stop, skip-ahead, app killed) must not
            # leave its look-ahead sentences queued: they'd keep holding
            # the synthesis semaphore and make the NEXT request wait behind
            # a read nobody is listening to (seen live 2026-10-04: a stopped
            # Chatterbox read starved a fresh Kokoro one for 30s+).
            # Cancelling drops everything still queued; only a synthesis
            # already running in its thread has to finish.
            for t in pending.values():
                t.cancel()
    except WebSocketDisconnect:
        pass
    except Exception as e:
        import traceback
        traceback.print_exc()
        try:
            await websocket.send_json({"type": "error", "message": f"{e.__class__.__name__}: {e}"})
        except Exception:
            pass
    finally:
        try:
            await websocket.close()
        except Exception:
            pass


async def _synthesize_sentence(engine, engine_name: str, sentence: str, voice: str | None, emit=None) -> tuple[bytes, dict]:
    """Just the synthesis half - no websocket I/O - so tts_stream can run
    several of these as concurrent tasks and send the results in order
    once ready. A cache hit intentionally skips the semaphore below (it's
    not real GPU work); a real synthesis holds it, same limit as before
    (TTS_MAX_CONCURRENT_SYNTHESIS), now actually reachable by a single
    stream instead of only by two unrelated ones overlapping by chance."""
    expanded = expand_domains(expand_acronyms(sentence))
    extra = _chatterbox_cache_extra() if engine_name == "chatterbox" else ""
    cache_key = _tts_cache_key(engine_name, voice, expanded, extra)
    cached = await run_in_threadpool(_tts_cache_load, cache_key)
    if cached is not None:
        pcm, meta = cached
        return pcm, {
            "text": sentence, "sample_rate": meta["sample_rate"],
            "duration_ms": meta["duration_ms"], "synth_ms": 0,
        }

    sem = _kokoro_synthesis_semaphore if engine_name == "kokoro" else _tts_synthesis_semaphore
    if emit is not None:
        _progress_sink.set(emit)
        if sem.locked():
            emit("Queued behind other sentences already being synthesized")
    async with sem:
        t0 = time.monotonic()
        samples, sr = await run_in_threadpool(engine.synthesize, expanded, voice)
        duration_s = len(samples) / sr
        duration_ms = round(duration_s * 1000)
        pcm = float_to_pcm16(samples)
        await run_in_threadpool(_tts_cache_store, cache_key, pcm, {"sample_rate": sr, "duration_ms": duration_ms})
        return pcm, {
            "text": sentence, "sample_rate": sr,
            "duration_ms": duration_ms, "synth_ms": round((time.monotonic() - t0) * 1000),
        }


async def _send_sentence(websocket: WebSocket, pcm: bytes, meta: dict) -> int:
    duration_ms = meta["duration_ms"]
    await websocket.send_json({
        "type": "sentence",
        "text": meta["text"],
        "sample_rate": meta["sample_rate"],
        "duration_ms": duration_ms,
        "synth_ms": meta["synth_ms"],
        "words": estimate_word_timings(meta["text"], duration_ms / 1000),
    })
    await websocket.send_bytes(pcm)
    return duration_ms


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=BIND_HOST, port=BIND_PORT)
