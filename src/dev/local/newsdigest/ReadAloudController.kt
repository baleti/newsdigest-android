package dev.local.newsdigest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.util.Log
import org.json.JSONObject

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
 */
class ReadAloudController(
    private val context: Context,
    private val onStateChanged: (playing: Boolean) -> Unit,
    private val onCaptionChanged: (CharSequence) -> Unit,
) {
    private var ttsService: TtsPlaybackService? = null
    private var bound = false
    private var ws: WebSocketClient? = null
    private var wsThread: Thread? = null

    // Holds the FULL original text throughout - never blanked or rebuilt.
    // Reading highlights a moving span inside it instead of replacing it,
    // so the on-screen content never disappears once playback starts.
    private val captionBuilder = SpannableStringBuilder()
    private var searchCursor = 0
    private var currentSentenceStartOffset = 0
    private var currentWordRanges: List<IntRange> = emptyList()
    private var highlightSpan: BackgroundColorSpan? = null
    @Volatile private var active = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val highlightListener = object : TtsPlaybackService.HighlightListener {
        override fun onSentenceStart(text: String, words: List<WordTiming>) {
            mainHandler.post {
                // Locate this sentence inside the text that's already on
                // screen rather than appending it - the server's sentence
                // text is the same string this controller sent it (see
                // start() below), so it should always be found forward of
                // the last match.
                var idx = captionBuilder.toString().indexOf(text, searchCursor)
                if (idx < 0) idx = captionBuilder.toString().indexOf(text) // shouldn't happen; best effort
                if (idx < 0) {
                    currentWordRanges = emptyList() // can't safely place a span - skip highlighting this sentence
                    return@post
                }
                currentSentenceStartOffset = idx
                searchCursor = idx + text.length
                currentWordRanges = computeWordRanges(text, words)
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
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                highlightSpan = span
                onCaptionChanged.invoke(SpannableStringBuilder(captionBuilder))
            }
        }

        override fun onSentenceEnd() {}

        override fun onQueueIdle() {
            mainHandler.post {
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            bound = false
        }
    }

    fun bind() {
        context.bindService(Intent(context, TtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        stop()
        if (bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
    }

    fun isActive(): Boolean = active

    /** title is what shows in the media notification while this plays. */
    fun start(title: String, text: String) {
        stop()
        captionBuilder.clear()
        captionBuilder.append(text) // shown in full immediately - reading only ever highlights within this, never replaces it
        searchCursor = 0
        highlightSpan = null
        active = true
        onStateChanged.invoke(true)
        onCaptionChanged.invoke(SpannableStringBuilder(captionBuilder))

        val svc = ttsService
        if (svc == null) {
            Log.e("ReadAloudController", "TtsPlaybackService not bound yet")
            active = false
            onStateChanged.invoke(false)
            return
        }
        svc.startSession(title)

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
                }

                override fun onText(text: String) {
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
                }

                override fun onFailure(error: Throwable) {
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
        ws?.close()
        ws = null
        ttsService?.stopAll()
    }

    fun pause() = ttsService?.pause()
    fun resume() = ttsService?.resume()

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
