package dev.local.newsdigest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import org.json.JSONObject

// Generic fallback before any real synth_ms data exists at all - start()
// replaces this with an engine-aware guess (Chatterbox's diffusion
// sampler is far slower than Kokoro) the moment it knows which engine.
private const val DEFAULT_ESTIMATE_MS = 4000L

/**
 * Wires a WebSocketClient (against /tts/stream) to TtsPlaybackService and
 * highlights words in place over the full text as it's read - the content
 * stays on screen exactly as it was before playback started, never
 * blanked or rebuilt; only a moving background-color span changes. The
 * text shown is the plain (post-markdown-stripped, pre-acronym-expansion)
 * form the server was given, because that's the exact string it echoes
 * back per sentence to search for - see server.py's tts_stream comment.
 * Highlighting inside a separately-rendered rich view risks the two texts
 * drifting out of sync character-for-character, so this deliberately
 * isn't that.
 *
 * Every word gets a tap target from the moment reading starts, not just
 * ones already spoken - tapping one that's already synthesized seeks
 * straight to it (TtsPlaybackService.seekTo); tapping one further ahead
 * than the server has gotten to skips there instead of waiting: the
 * current stream is abandoned and a new one is started from that word,
 * so whatever was between the old and new position is simply never
 * synthesized, not queued up behind the jump.
 */
class ReadAloudController(
    private val context: Context,
    private val onStateChanged: (playing: Boolean) -> Unit,
    private val onCaptionChanged: (CharSequence) -> Unit,
    // Fires true (with a live best-guess of how many ms the wait will be)
    // right when start() is called and again after a sentence finishes
    // playing with nothing queued yet to follow it; false once the next
    // one actually starts. Chatterbox in particular can take 5-15s to
    // synthesize a sentence - streaming means that gap is expected (see
    // server.py's docs), but with no visual cue it reads as the app
    // having frozen rather than still working. The estimate is a rolling
    // average of this session's own observed synth_ms per sentence (see
    // server.py's synthesize_and_send), seeded with a generic per-engine
    // guess before any real data exists - optional, callers that don't
    // care about showing a "still generating..." indicator can leave it out.
    private val onGenerating: (generating: Boolean, estimatedMs: Long) -> Unit = { _, _ -> },
    // Fires on every real play/pause transition, from ANY trigger - the
    // in-app controls, the system notification's own button, a seek,
    // audio-focus loss/gain - not just ones a caller's own button press
    // caused. Optional - see TtsPlaybackService.HighlightListener.onPlayingChanged.
    private val onPlayingChanged: (playing: Boolean) -> Unit = {},
) {
    private var ttsService: TtsPlaybackService? = null
    private var bound = false
    private var ws: WebSocketClient? = null
    private var wsThread: Thread? = null

    // Holds the FULL original text throughout - never blanked or rebuilt.
    // Reading highlights a moving span inside it instead of replacing it,
    // so the on-screen content never disappears once playback starts.
    private val captionBuilder = SpannableStringBuilder()
    private var fullText = ""
    // Character offsets into fullText where each "section" (paragraph)
    // begins -- asked for explicitly: "split text into sections ... in
    // news [digest] it would likely be paragraphs or when topic changes".
    // Computed once in start(); skipToNextSection() below just reuses the
    // exact same abandon-and-restart mechanism word-tap-seek (skipAheadTo)
    // already provides, jumping to the next entry in this list instead of
    // an arbitrary tapped character.
    private var sectionStarts: List<Int> = listOf(0)
    private var searchCursor = 0
    private var currentSentenceStartOffset = 0
    private var currentWordRanges: List<IntRange> = emptyList()
    private var highlightSpan: BackgroundColorSpan? = null
    // Char offset (into fullText/captionBuilder) of whichever word is
    // currently highlighted - null until the first onWordHighlight of a
    // session. Exposed via currentReadingOffset() so a caller can scroll
    // back to the live position on demand (asked for explicitly
    // 2026-09-10: "hard to find where it's at when scrolling larger
    // articles").
    private var currentHighlightStart: Int? = null
    @Volatile private var active = false
    @Volatile private var currentSpeed = 1.0f
    // Bumped every streamText() call. skipAheadTo() closes the old socket
    // before opening a new one, but a hand-rolled WebSocketClient reading
    // in its own thread could still have one message already in flight
    // when close() is called - each stream's callbacks check this before
    // touching shared state, so a stale message from an abandoned stream
    // can't enqueue a "skipped" sentence after the jump.
    @Volatile private var streamGeneration = 0

    /** One entry per word we actually have synthesis timing for, in the
     * order sentences arrived - spans the whole session, not just the
     * currently-highlighting sentence (unlike currentWordRanges), so a
     * tap can seek into anything already read, not only the live one. */
    private data class KnownWord(val charStart: Int, val charEnd: Int, val ms: Long)
    private val knownWords = mutableListOf<KnownWord>()

    // Rolling average of synth_ms across this controller's own observed
    // sentences (kept across separate start() calls in the same activity,
    // not just within one session - a second read benefits from what the
    // first one learned about how fast this engine/server currently is).
    @Volatile private var avgSynthMs: Long = DEFAULT_ESTIMATE_MS
    private var synthSampleCount = 0

    private fun recordSynthMs(ms: Long) {
        if (ms <= 0) return
        synthSampleCount++
        // Weight recent samples more heavily so the estimate adapts if
        // the server's pace changes mid-session (e.g. a GPU warming up).
        avgSynthMs = if (synthSampleCount == 1) ms else (avgSynthMs * 3 + ms) / 4
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val highlightListener = object : TtsPlaybackService.HighlightListener {
        override fun onSentenceStart(text: String, words: List<WordTiming>, startMs: Long) {
            mainHandler.post {
                onGenerating(false, 0L)
                // Locate this sentence inside the text that's already on
                // screen rather than appending it - the server's sentence
                // text is the same string this controller sent it (see
                // streamText() below), so it should always be found
                // forward of the last match.
                var idx = captionBuilder.toString().indexOf(text, searchCursor)
                if (idx < 0) idx = captionBuilder.toString().indexOf(text) // shouldn't happen; best effort
                if (idx < 0) {
                    currentWordRanges = emptyList() // can't safely place a span - skip highlighting this sentence
                    return@post
                }
                currentSentenceStartOffset = idx
                searchCursor = idx + text.length
                currentWordRanges = computeWordRanges(text, words)

                for ((i, range) in currentWordRanges.withIndex()) {
                    if (range.isEmpty()) continue
                    knownWords.add(
                        KnownWord(
                            currentSentenceStartOffset + range.first,
                            currentSentenceStartOffset + range.last + 1,
                            startMs + words[i].startMs,
                        ),
                    )
                }
            }
        }

        override fun onWordHighlight(wordIndex: Int) {
            mainHandler.post {
                if (wordIndex !in currentWordRanges.indices) return@post
                highlightSpan?.let { captionBuilder.removeSpan(it) }
                val range = currentWordRanges[wordIndex]
                val span = BackgroundColorSpan(0x552196F3)
                captionBuilder.setSpan(
                    span,
                    currentSentenceStartOffset + range.first,
                    currentSentenceStartOffset + range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                highlightSpan = span
                currentHighlightStart = currentSentenceStartOffset + range.first
                // The SAME captionBuilder instance every time (not a fresh
                // copy) - see the class doc on why this matters as much as
                // it does now that the overview is prepended to what's
                // read: the caller only needs to call TextView.setText()
                // once and can just invalidate() on every subsequent tick
                // once it recognizes this is the same object, instead of
                // forcing a full relayout of the whole (now longer) body
                // on every ~60ms highlight tick for the entire session.
                onCaptionChanged.invoke(captionBuilder)
            }
        }

        override fun onSentenceEnd() {
            mainHandler.post { onGenerating(true, avgSynthMs) }
        }

        override fun onPlayingChanged(playing: Boolean) {
            // Qualified: this override and the outer constructor param
            // share a name, and unqualified would recurse into itself.
            mainHandler.post { this@ReadAloudController.onPlayingChanged.invoke(playing) }
        }

        override fun onQueueIdle() {
            mainHandler.post {
                onGenerating(false, 0L)
                if (active) {
                    active = false
                    onStateChanged.invoke(false)
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as TtsPlaybackService.LocalBinder).service()
            ttsService = svc
            svc.setListener(highlightListener)
            bound = true
            // A previous screen (this article, or a different one/the
            // digest/a chat reply) may have started a session and then
            // gone away without stopping it -- the service itself, a real
            // foreground service with its own notification, kept right on
            // playing in the background exactly as intended (asked for
            // explicitly: "playback continues even if i close the
            // activity/window with the conversation"). This fresh
            // controller's own `active`/caption state starts empty
            // regardless, so this only resyncs play/pause state for
            // whatever's listening for it (e.g. the toolbar's Stop/Read
            // aloud toggle) -- it deliberately does NOT try to restore the
            // in-place word-highlight caption, since that needs the
            // original text this screen instance never received.
            if (svc.hasActiveSession()) {
                active = true
                val playingNow = svc.isPlaying()
                mainHandler.post {
                    onStateChanged.invoke(true)
                    onPlayingChanged.invoke(playingNow)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            bound = false
        }
    }

    fun bind() {
        context.bindService(Intent(context, TtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Detaches this controller from the playback service WITHOUT stopping
     * playback -- TtsPlaybackService is a real foreground service with its
     * own MediaSession/notification specifically so a read continues
     * playing (and stays controllable from the notification/lock screen)
     * after the launching screen is gone, the same way any other media app
     * behaves. Call stop() explicitly first (e.g. the toolbar's own Stop
     * action already does) if leaving really should end the read. */
    fun unbind() {
        if (bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
    }

    fun isActive(): Boolean = active

    /** Char offset (into the same text onCaptionChanged hands the caller)
     * of wherever reading currently is - the live-highlighted word once
     * one has been highlighted this session, else the start of the
     * current sentence. Null when not active. A caller uses this to
     * scroll its own view back to the live position on demand. */
    fun currentReadingOffset(): Int? =
        if (!active) null else (currentHighlightStart ?: currentSentenceStartOffset)

    /** title is what shows in the media notification while this plays.
     * `text` can be plain, or a Spannable (e.g. MarkdownRenderer.render()'s
     * output) whose spans - link clicks, bold styling - carry over into
     * the live caption automatically (SpannableStringBuilder.append()
     * copies spans from a Spanned source). Passing the rendered form
     * instead of raw markdown is what keeps the plain text underneath
     * identical to what the server actually receives and times, since
     * markdown_to_speech() on the server strips the exact same
     * constructs this rendering already stripped - see class doc. */
    // startOffset resumes a previous session instead of starting over --
    // asked for explicitly 2026-09-10 ("save last position... Start again
    // / Resume buttons"). The full text is still shown and word-spanned
    // top to bottom either way (see below); only the actual TTS stream
    // (streamText, at the very end of this function) begins partway
    // through, at the saved character offset, rather than at 0.
    fun start(title: String, text: CharSequence, startOffset: Int = 0) {
        stop()
        fullText = text.toString()
        sectionStarts = splitIntoSections(fullText)
        knownWords.clear()
        captionBuilder.clear()
        captionBuilder.append(text) // shown in full immediately - reading only ever highlights within this, never replaces it
        // Real link ClickableSpans (copied in by append() from a Spanned
        // source) are kept live during reading, not swapped for plain
        // color - tapping a reference link should open it whether or not
        // playback is active (asked for explicitly 2026-09-09: "clicking
        // on links should work both in normal and in read aloud modes").
        // Their ranges are recorded so attachWordSpans() below can skip
        // adding its own per-word seek span over the same characters -
        // LinkTapHandler.attach() fires only the first ClickableSpan found
        // at a tap's offset, so two overlapping spans there would make
        // which action wins a coin flip; excluding link ranges from the
        // word spans keeps it unambiguous (no tap-to-seek granularity
        // inside a link's own words, seek still works everywhere else).
        val linkRanges = captionBuilder.getSpans(0, captionBuilder.length, ClickableSpan::class.java)
            .map { captionBuilder.getSpanStart(it) to captionBuilder.getSpanEnd(it) }
        attachWordSpans(fullText, linkRanges)
        val clampedStart = startOffset.coerceIn(0, fullText.length)
        searchCursor = clampedStart
        highlightSpan = null
        currentHighlightStart = null
        active = true
        onStateChanged.invoke(true)
        // Same object every time from here on (see onWordHighlight's doc)
        onCaptionChanged.invoke(captionBuilder)
        if (synthSampleCount == 0) {
            // No real data yet at all (first read this activity has done) -
            // seed with an engine-aware guess rather than the generic
            // default, so the very first estimate isn't wildly off for
            // Chatterbox in particular.
            avgSynthMs = if (Settings.getTtsEngine(context) == "chatterbox") 10_000L else 2_000L
        }
        onGenerating(true, avgSynthMs) // nothing synthesized yet either - same "still working" state as a mid-read gap

        val svc = ttsService
        if (svc == null) {
            Log.e("ReadAloudController", "TtsPlaybackService not bound yet")
            active = false
            onStateChanged.invoke(false)
            return
        }
        // Explicitly START the service, not just bind it -- confirmed live
        // (2026-09-08, in claude-agents-android's copy of this same code)
        // that a bind-only service is destroyed the instant its last
        // client unbinds, REGARDLESS of startForeground() having already
        // been called: startForeground() elevates process priority/shows
        // the notification while the service is alive, but it does not by
        // itself keep the component's lifecycle independent of bindings.
        // Without this, "playback continues even if i close the activity"
        // silently did nothing -- the notification and service both
        // vanished the moment the launching screen's onDestroy() ran
        // unbind(), even with the stop() call already removed from it.
        context.startForegroundService(Intent(context, TtsPlaybackService::class.java))
        svc.startSession(title)
        val resumeText = fullText.substring(clampedStart)
        // Rough upfront guess of the REMAINING length (not the whole
        // article, when resuming) so the system media notification has an
        // end-time to show immediately, rather than only whatever's been
        // synthesized so far (which permanently lags behind real-time
        // under the 30s lookahead cap) - see
        // TtsPlaybackService.setEstimatedDuration's own doc. 160 words/
        // minute is a typical spoken-word rate; real per-sentence
        // durations (enqueueSentence) take over once they exceed this.
        val wordCount = resumeText.split(Regex("\\s+")).count { it.isNotBlank() }
        svc.setEstimatedDuration((wordCount / (160.0 / 60.0) * 1000).toLong())
        svc.setPlaybackSpeed(currentSpeed)
        streamText(resumeText, svc)
    }

    /** One ClickableSpan per whitespace-delimited word across the WHOLE
     * text, attached up front - not only the parts already synthesized.
     * Each span's onClick re-checks knownWords at tap time (not at
     * attach time), so the very same span transparently seeks once its
     * word has been read, or skips ahead if it hasn't yet - no need to
     * ever swap a span out as synthesis catches up to it. */
    private fun attachWordSpans(text: String, linkRanges: List<Pair<Int, Int>>) {
        for (m in Regex("\\S+").findAll(text)) {
            val wordStart = m.range.first
            val wordEnd = m.range.last + 1
            if (linkRanges.any { (s, e) -> wordStart < e && wordEnd > s }) continue
            captionBuilder.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = handleWordTap(wordStart)
                    // No underline/color - reads as plain text, not a hyperlink.
                    override fun updateDrawState(ds: TextPaint) {}
                },
                wordStart,
                wordEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun handleWordTap(charOffset: Int) {
        if (!active) return
        val hit = knownWords.find { charOffset in it.charStart until it.charEnd }
        if (hit != null) {
            ttsService?.seekTo(hit.ms)
        } else {
            skipAheadTo(charOffset)
        }
    }

    /** Abandons the current stream and starts a fresh one from charOffset
     * - whatever was between wherever the server had gotten to and this
     * new point is simply never synthesized, rather than making you wait
     * for it. Already-synthesized audio before charOffset is untouched
     * (still there to tap/seek back into); TtsPlaybackService.jumpToUpcoming()
     * is what makes the NEXT enqueued sentence play immediately instead of
     * whatever was still mid-flight. */
    private fun skipAheadTo(charOffset: Int) {
        val svc = ttsService ?: return
        val clamped = charOffset.coerceIn(0, fullText.length)
        val resumeText = fullText.substring(clamped).trimStart()
        if (resumeText.isBlank()) return
        ws?.close() // stop the old stream's remaining sentences from arriving after the new ones
        svc.jumpToUpcoming()
        searchCursor = fullText.length - resumeText.length
        onGenerating(true, avgSynthMs)
        streamText(resumeText, svc)
    }

    /** Paragraph boundaries (a blank line) as character offsets into
     * `text` -- always includes 0. In practice a topic change in
     * generated article/digest text already lands on a paragraph break,
     * so this doubles as "when topic changes" without needing separate
     * topic-detection logic. */
    private fun splitIntoSections(text: String): List<Int> {
        val starts = mutableListOf(0)
        for (m in Regex("\\n\\s*\\n").findAll(text)) {
            val next = m.range.last + 1
            if (next < text.length) starts.add(next)
        }
        return starts
    }

    /** Index into sectionStarts for wherever the currently-playing (or
     * most recently started) sentence began -- the highest boundary at or
     * before it. */
    private fun currentSectionIndex(): Int {
        var idx = 0
        for (i in sectionStarts.indices) {
            if (sectionStarts[i] <= currentSentenceStartOffset) idx = i else break
        }
        return idx
    }

    fun hasNextSection(): Boolean = currentSectionIndex() + 1 < sectionStarts.size

    /** Skips to the start of the next paragraph -- exactly skipAheadTo()'s
     * own abandon-and-restart mechanism (already proven for word-tap-seek),
     * just aimed at the next section boundary instead of a tapped word. */
    fun skipToNextSection() {
        val idx = currentSectionIndex()
        if (idx + 1 >= sectionStarts.size) return
        skipAheadTo(sectionStarts[idx + 1])
    }

    /** Same as skipToNextSection() but backwards -- jumps to the start of
     * the previous paragraph. No-op on the first section. */
    fun skipToPreviousSection() {
        val idx = currentSectionIndex()
        if (idx <= 0) return
        skipAheadTo(sectionStarts[idx - 1])
    }

    /** Connects to /tts/stream, sends `text`, and enqueues every sentence
     * that comes back onto `svc` as it arrives. Used both for the initial
     * read and for resuming after skipAheadTo() - the only difference is
     * whether the caller already reset the service's session state first. */
    private fun streamText(text: String, svc: TtsPlaybackService) {
        val myGeneration = ++streamGeneration
        fun isCurrent() = streamGeneration == myGeneration
        // Base position this stream builds on top of - 0 for the initial
        // start(), or wherever a skipAheadTo() jump landed - so the
        // "played_ms" reported below is relative to THIS stream's own
        // audio, matching how the server counts what it's sent.
        val streamStartPositionMs = svc.getPositionMs()

        // Tells the server how far playback has actually gotten into this
        // stream, every 500ms, so it can cap how far ahead of that it
        // synthesizes (see server.py's TTS_LOOKAHEAD_CAP_MS). A paused
        // player simply stops advancing getPositionMs(), which is exactly
        // what makes the server stall on its own - no separate pause
        // signal needed. Reported live 2026-09-09: the server was
        // synthesizing the entire article right after the first play.
        fun reportPosition(client: WebSocketClient) {
            if (!isCurrent()) return
            val playedMs = (svc.getPositionMs() - streamStartPositionMs).coerceAtLeast(0)
            try {
                client.sendText(JSONObject().apply {
                    put("type", "position")
                    put("played_ms", playedMs)
                }.toString())
            } catch (_: Exception) {}
            mainHandler.postDelayed({ reportPosition(client) }, 500)
        }

        wsThread = Thread {
            val client = WebSocketClient(
                Settings.getHost(context),
                Settings.getTtsPort(context),
                "/tts/stream",
                mapOf("X-Peer-Agent" to "1"),
            )
            ws = client
            client.connect(object : WebSocketClient.Listener {
                private var pendingMeta: JSONObject? = null

                override fun onOpen() {
                    client.sendText(JSONObject().apply {
                        put("text", text)
                        put("engine", Settings.getTtsEngine(context))
                    }.toString())
                    mainHandler.post { reportPosition(client) }
                }

                override fun onText(text: String) {
                    if (!isCurrent()) return
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "sentence" -> pendingMeta = obj
                        "done" -> {
                            // No more sentences are coming, but whatever's
                            // already queued may still be playing - let
                            // TtsPlaybackService's own queue-drain decide
                            // when onQueueIdle actually fires, rather than
                            // flipping the UI to "stopped" the instant the
                            // server finishes generating.
                            svc.endSession()
                        }
                        "error" -> {
                            Log.e("ReadAloudController", "server error: ${obj.optString("message")}")
                            svc.endSession()
                            mainHandler.post {
                                if (active) {
                                    active = false
                                    onStateChanged.invoke(false)
                                }
                            }
                        }
                    }
                }

                override fun onBinary(data: ByteArray) {
                    if (!isCurrent()) return
                    val meta = pendingMeta ?: return
                    val words = mutableListOf<WordTiming>()
                    val wordsArray = meta.optJSONArray("words")
                    if (wordsArray != null) {
                        for (i in 0 until wordsArray.length()) {
                            val w = wordsArray.getJSONObject(i)
                            words.add(WordTiming(w.getString("word"), w.getInt("start_ms"), w.getInt("end_ms")))
                        }
                    }
                    svc.enqueueSentence(meta.getString("text"), words, data, meta.getInt("sample_rate"))
                    recordSynthMs(meta.optLong("synth_ms", -1))
                }

                override fun onFailure(error: Throwable) {
                    if (!isCurrent()) return // expected: skipAheadTo()'s ws.close() surfaces as a failure on the abandoned stream
                    Log.e("ReadAloudController", "websocket failed", error)
                    mainHandler.post {
                        active = false
                        onStateChanged.invoke(false)
                    }
                }
            })
        }.apply { isDaemon = true; name = "ReadAloudWs"; start() }
    }

    fun stop() {
        active = false
        onGenerating(false, 0L)
        streamGeneration++ // suppress any late callback from whatever stream this abandons
        ws?.close()
        ws = null
        ttsService?.stopAll()
    }

    fun pause() = ttsService?.pause()
    fun resume() = ttsService?.resume()

    /** Skip back/forward by deltaMs (negative to rewind) from the current
     * position - clamps into whatever's been synthesized so far, same as
     * seekTo() itself; a forward skip that runs past that just lands at
     * the end of what's available rather than skipping ahead into
     * unsynthesized text (that's what tapping a specific word further
     * ahead is for - see skipAheadTo()). */
    fun seekRelative(deltaMs: Long) {
        val svc = ttsService ?: return
        svc.seekTo((svc.getPositionMs() + deltaMs).coerceAtLeast(0))
    }

    /** Remembered for the next start() too, not just applied live - so
     * picking a speed sticks across separate read-aloud sessions instead
     * of quietly resetting to 1.0x each time. */
    fun setSpeed(speed: Float) {
        currentSpeed = speed
        ttsService?.setPlaybackSpeed(speed)
    }

    fun getSpeed(): Float = currentSpeed

    /** Sequentially matches each timed word against the sentence text to
     * find its character range - the words are exactly the sentence's own
     * whitespace-split tokens (see server.py estimate_word_timings), so a
     * simple left-to-right scan is enough; no fuzzy matching needed. */
    private fun computeWordRanges(sentenceText: String, words: List<WordTiming>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var searchFrom = 0
        for (w in words) {
            val idx = sentenceText.indexOf(w.word, searchFrom)
            if (idx < 0) {
                ranges.add(IntRange.EMPTY)
                continue
            }
            ranges.add(idx..(idx + w.word.length - 1))
            searchFrom = idx + w.word.length
        }
        return ranges
    }
}
