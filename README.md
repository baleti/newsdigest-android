# News Digest

An Android app that reads a personal activity digest aloud with
natural-sounding text-to-speech, over your own private network - no cloud
TTS API, no tracking, no ads. It started as an RSS reader; the server side
now merges RSS with whatever other activity sources you point it at (git
activity, a bot's own posts, calendar, email headers, ...) into one feed,
and you can ask an agent questions about any item with the reply read back
to you in real time as it's generated.

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
  stripping), merges one or more activity sources into a feed, streams an
  agent-chat reply sentence-by-sentence as it's generated, and serves it
  all to the app.
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
mangled. The same pipeline reads agent chat replies aloud as they stream
in, not after the fact - pauses while it waits for more text to generate
are fine, but it never sits on a finished sentence before speaking it.

## What "digest" means here

`server/feed.py` doesn't know or care where an item came from - it just
merges whichever JSONL files a small **local, untracked** config
(`sources.json`, see [Sources](#sources)) points it at, all in one shared
item schema. RSS is one source among several this repo ships a generic,
config-driven collector for:

- `server/collectors/machine_activity.py` - recent commits across
  whichever repos you list, completed systemd timer/service runs.
- `server/collectors/github_activity.py` - via the `gh` CLI: new commits,
  PRs/issues, review requests, notifications on repos/orgs you list.
- `server/collectors/reddit_activity.py` - a bot's own logged
  posts/comments (reads existing action logs; no new browser automation).
- `server/collectors/email_activity.py` - unread mail **subjects and
  senders only** (never body content) via your existing IMAP setup.
- `server/collectors/calendar_activity.py` - upcoming events via your
  existing Google Calendar OAuth setup.
- `server/collectors/jobsearch_activity.py` - **read-only** checks (new
  connections/notifications counts, never any automated outreach) against
  an existing logged-in browser profile, plus a local job-search
  pipeline's own state files. Off by default - see the risk note in that
  file's docstring before enabling it.

None of these need new credentials beyond what you'd already have set up
for that source (an authenticated `gh`, an IMAP account, an OAuth token,
a logged-in browser profile) - they only read.

An optional daily narrative digest (`GET /feed/digest`) can be generated
by an LLM reading across whichever sources are enabled - see
[Digest generation](#digest-generation).

## Setup

### Server

```sh
cd server
pip install -r requirements.txt
# Download Kokoro's model weights (~350MB total) into ~/.cache/newsdigest-server/:
mkdir -p ~/.cache/newsdigest-server
curl -sSL -o ~/.cache/newsdigest-server/kokoro-v1.0.onnx \
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx
curl -sSL -o ~/.cache/newsdigest-server/voices-v1.0.bin \
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin

NEWSDIGEST_BIND_HOST=<your tunnel address> \
NEWSDIGEST_ALLOWED_SUBNET=<your tunnel subnet, e.g. 10.0.0.0/24> \
python3 server.py
```

A systemd user-service template is at
[server/newsdigest-server.service.example](server/newsdigest-server.service.example) -
it runs `server.py` straight out of wherever you clone this repo, so
`git pull` is the only update step.

### Sources

Copy the example config and point it at your own data:

```sh
mkdir -p ~/.config/newsdigest
cp server/config.example/sources.example.json ~/.config/newsdigest/sources.json
# edit sources.json: which of the JSONL files below actually exist for you
```

Each enabled source is a plain JSONL file, one object per line, in this
shared schema (only `title` is required - fill in what applies):

```json
{"id": "...", "title": "...", "summary": "...", "link": "https://...", "feed_title": "...", "tags": ["..."], "published": "2026-01-01T00:00:00+00:00", "content_html": "..."}
```

The RSS source itself isn't included - bring your own poller (anything
that appends lines in this format works), or run any of the
`server/collectors/*.py` scripts on a timer to populate the others.
`feed.py`'s `NEWSDIGEST_SOURCES_CONFIG` env var overrides the config path
if you don't want it under `~/.config/`.

`GET /feed/digest` (an optional daily narrative summary distinct from
individual items) reads `~/.cache/newsdigest-server/digest/latest.json`
(`{"date": "...", "markdown": "...", "references": [...]}`) if present;
the app treats a missing digest as normal, not an error.

### Digest generation

Not included as a runnable script, since a good one is inherently
personal (it should know who you are and what you care about to pick out
what's worth mentioning). `server/generate-digest.sh.example` shows the
mechanics - reading across your enabled sources, prompting an LLM for a
short narrative piece, writing the JSON shape above - with placeholder
personalization; copy it outside the repo and fill in the real thing.

### App

```sh
bash build.sh                             # produces build/newsdigest-signed.apk
adb install -r build/newsdigest-signed.apk
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

## License

MIT — see [LICENSE](LICENSE).
