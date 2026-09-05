# RSS Reader

An Android RSS reader that reads articles aloud with natural-sounding
text-to-speech, over your own private network - no cloud TTS API, no
tracking, no ads.

> **Assumption:** you already have a private tunnel (WireGuard or
> equivalent) between your phone and the machine running `server/`. This
> project does not set one up for you, and the server refuses to start
> without an explicit bind address and allowed subnet - see
> [Setup](#setup). Don't expose its port on an untrusted network; the
> `X-Peer-Agent` header it checks is a backstop against what the tunnel
> structurally can't cover (browser-originated requests), not a substitute
> for the tunnel itself. See [docs/design.md](docs/design.md).

Two halves, one repo:

- **`server/`** — a Python server (FastAPI) that runs your choice of TTS
  engine, cleans up text before synthesis (acronym expansion, markdown
  stripping), and serves feed data.
- **App** (`AndroidManifest.xml`, `src/`) — the Android client. No Gradle,
  no Play Services, no third-party dependencies; built with the plain
  Android SDK command-line tools.

## Why

Most "read this article to me" apps either use the phone's built-in
system TTS (usually the most robotic-sounding option available, and prone
to misreading acronyms like "RCE" or "CVE" as if they were words) or a
paid cloud API. This app instead runs a real neural TTS model on a
machine you control, reached over a tunnel you already trust - and
normalizes text before synthesis so acronyms get spelled out rather than
mangled.

## Setup

### Server

```sh
cd server
pip install -r requirements.txt
# Download Kokoro's model weights (~350MB total) into ~/.cache/tts-server/:
mkdir -p ~/.cache/tts-server
curl -sSL -o ~/.cache/tts-server/kokoro-v1.0.onnx \
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx
curl -sSL -o ~/.cache/tts-server/voices-v1.0.bin \
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin

RSS_READER_BIND_HOST=<your tunnel address> \
RSS_READER_ALLOWED_SUBNET=<your tunnel subnet, e.g. 10.0.0.0/24> \
python3 server.py
```

A systemd user-service template is at
[server/tts-server.service.example](server/tts-server.service.example).

**Feed data**: `server/feed.py` reads from `~/.cache/rssd/items.jsonl`, an
append-only JSONL archive with one object per RSS item - this project
doesn't include a feed poller itself, so bring your own (or write a small
one) that appends lines shaped like:

```json
{"id": "...", "title": "...", "summary": "...", "link": "https://...", "feed_title": "...", "tags": ["..."], "published": "2026-01-01T00:00:00+00:00", "content_html": "...", "icon": "/path/to/cached/favicon.png"}
```

`GET /feed/digest` (an optional daily narrative summary, distinct from
individual feed items) reads `~/.cache/tts-server/digest/latest.json`
(`{"date": "...", "markdown": "...", "references": [...]}`) if present;
the app treats a missing digest as normal, not an error.

### App

```sh
bash build.sh                             # produces build/rssreader-signed.apk
adb install -r build/rssreader-signed.apk
```

On first launch, enter the server's host and ports in the settings screen
that opens automatically. Nothing is hardcoded - see
[docs/design.md](docs/design.md) for how host/ports/token are stored.

## Requirements

- Python 3.10+ on the server. A CUDA GPU is only needed if you enable the
  optional Chatterbox engine - Kokoro alone runs fine on CPU.
- Android 10+ (`minSdkVersion 29`) on the client.
- To build the app: `aapt2`, `kotlinc`, `d8`, `apksigner`, and an
  `android.jar` for a recent platform (see `build.sh` for the exact paths
  it expects — override via environment variables for your own layout).

## What it doesn't do

- Fetch RSS feeds itself - bring your own poller writing to the
  `items.jsonl` format above.
- Generate the optional daily digest - that's a separate step (e.g. a
  scheduled call to an LLM) writing the JSON format above; this repo only
  serves and reads it.
- Provide TTS playback with word-highlighting or an agent-chat feature yet
  - both are designed but not wired up; see the "not wired up yet" markers
    in `DetailActivity.kt`/`ArticleActivity.kt`/`TtsPlaybackService.kt`.

## License

MIT — see [LICENSE](LICENSE).
