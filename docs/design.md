# Design notes

## Why two TTS engines, resident in one process

[Kokoro](https://github.com/thewh1teagle/kokoro-onnx) (82M params,
onnxruntime, CPU-only) and [Chatterbox](https://github.com/resemble-ai/chatterbox)
(0.5B params, needs a CUDA GPU) sit behind the same API so the app can
switch between them from a settings toggle rather than committing to one.
Measured on a desktop-class CPU/GPU: Kokoro runs at roughly 2.4x
real-time on CPU alone; Chatterbox, once its ~30s one-time CUDA warmup
has finished, runs close to real-time per sentence on a mid-range GPU.
Kokoro loads inline at startup (under a second); Chatterbox's slower
warmup runs in a background thread so it doesn't block Kokoro from
serving requests in the meantime - `GET /status` reports each engine's
state independently.

GPU idle power draw with Chatterbox's weights resident but not
synthesizing is indistinguishable from having nothing loaded at all
(~8-9W either way, measured) - the cost of keeping it loaded is VRAM
occupancy (a few GB), not power.

## Streaming protocol: WebSocket, not chunked HTTP

Plain HTTP has no clean way to interleave binary audio with per-sentence
word-timing JSON in one response without inventing a framing format. A
WebSocket already frames messages naturally: each synthesized sentence
arrives as a JSON message (the sentence's text, per-word timing estimates,
sample rate) immediately followed by a binary message (that sentence's
raw 16-bit PCM). The client can start playing sentence 1 while sentence 2
is still synthesizing, and use the timing metadata to highlight words as
they're spoken.

Word timings are a proportional-by-character-count estimate against the
engine's real output duration for that sentence - not a forced alignment.
That's a deliberate trade: a real aligner would add a model and real
latency to the responsiveness-critical path for a highlight effect that
doesn't need millisecond precision to read correctly.

## Acronym and markdown preprocessing

Both TTS engines will attempt to pronounce an all-caps acronym like "RCE"
as if it were a word, rather than spelling it out - the exact complaint
that motivated building this instead of using a phone's stock
text-to-speech. `acronyms.py` expands 2-6 letter all-caps tokens into
letter-by-letter spelling ("RCE" -> "R C E") before synthesis, with a
small human-editable exception list (`word-acronyms.txt`) for ones that
are genuinely pronounced as a word (NASA, OK, COVID, ...). `text_clean.py`
strips markdown syntax (links, bold/italic, headers, bullets) first, since
the source text is usually markdown and both raw `[text](url)` links and
literal `**` characters read badly aloud - and a raw URL's periods would
otherwise confuse sentence splitting.

## Security model

Mirrors the same pattern used across this whole family of tools: the
server binds directly to the tunnel interface address
(`NEWSDIGEST_BIND_HOST`), not `0.0.0.0` - a Linux host can bind to its own
tunnel interface (unlike an Android app, which can't enumerate or bind a
VPN's tun interface at all), so off-tunnel traffic never reaches the
socket at the kernel level. On top of that: a source-IP check against
`NEWSDIGEST_ALLOWED_SUBNET` (defense in depth, since the bind address
already limits reachability), a required `X-Peer-Agent: 1` header, and
outright rejection of any request carrying an `Origin` header. That last
pair defends against a browser on another peer's device being tricked
into hitting this port - same-origin policy blocks a malicious page from
*reading* the response, not from *sending* the request, so a plain
`<img>` tag can trigger a GET and a `<form>` POST needs no preflight but
can't set custom headers and does send `Origin`, which this rejects
outright. There is deliberately no bearer token for this server - the
tunnel is the credential; the checks above only cover what the tunnel
structurally can't.

`claude-relay` (a separate, sibling project some users may also run
alongside this one) *does* use a bearer token, because it can start real
processes on the server - a materially higher-stakes action than reading
a cached feed archive or running TTS.

## Settings, not compile-time constants

Host, ports, and the optional claude-relay pairing token are entered once
on first launch and stored via `Settings.kt`, not baked into the app's
source - this app has no legitimate way to know your network layout at
build time. The claude-relay token specifically is encrypted at rest with
an AndroidKeyStore-backed AES-GCM key (plain `javax.crypto`/
`android.security.keystore` platform APIs, not `androidx.security-crypto`,
which this Gradle-less build has no dependency resolver to fetch) - the
same pattern used in the sibling `clauderelay-android` project's
`TokenStore.kt`. The `X-Peer-Agent` header this app sends the feed/TTS
server is a fixed constant, not something requiring secure storage - see
the security model above for why that header isn't a credential.
