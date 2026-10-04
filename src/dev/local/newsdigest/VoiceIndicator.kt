package dev.local.newsdigest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * One slim line shown while reading aloud that says WHICH voice is
 * speaking and, while the phone's own basic voice is covering for a slow
 * server, how long until the better one takes over:
 *
 *   Voice: on-device (basic) → Chatterbox in ~9s · Loading Chatterbox onto the second GPU
 *   Voice: Chatterbox
 *
 * The countdown is the controller's own estimate of the server's time to
 * first sentence (a rolling average, engine-aware seed - same number the
 * "Synthesizing" banner counts down). It can run over: past the estimate
 * the line says how far over instead of pretending. The trailing step is
 * the server's live `status` text (see server.py's _progress).
 */
class VoiceIndicator(context: Context, private val serverVoiceLabel: () -> String) {
    val view: TextView = TextView(context).apply {
        textSize = 11.5f
        setTextColor(Theme.muted)
        setPadding(Theme.dp(context, 16), Theme.dp(context, 4), Theme.dp(context, 16), Theme.dp(context, 4))
        visibility = View.GONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var onDevice = false          // is the phone's own voice the one speaking right now
    private var serverTookOver = false    // a server sentence has played at least once
    private var requestedAtNanos = 0L
    private var estimateMs = 0L
    private var step: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            render()
            handler.postDelayed(this, 500)
        }
    }

    /** A read just started (or restarted after a seek/skip); `estimateMs` is
     * the expected wait for the server's first audio. */
    fun begin(estimateMs: Long) {
        active = true
        onDevice = false
        serverTookOver = false
        step = null
        requestedAtNanos = System.nanoTime()
        this.estimateMs = estimateMs
        view.visibility = View.VISIBLE
        handler.removeCallbacks(tick)
        tick.run()
    }

    /** A sentence just started playing: `local` = it came from the phone's TTS. */
    fun onSentenceSource(local: Boolean) {
        if (!active) return
        onDevice = local
        if (!local) serverTookOver = true
        render()
    }

    fun onStep(message: String) {
        step = message
        if (active) render()
    }

    fun end() {
        active = false
        handler.removeCallbacks(tick)
        view.visibility = View.GONE
    }

    private fun render() {
        val server = serverVoiceLabel()
        val sb = StringBuilder("Voice: ")
        if (!onDevice && !serverTookOver) {
            // Nothing has played yet - say what we're waiting on.
            sb.append("starting…")
        } else if (onDevice) {
            sb.append("on-device (basic)")
        } else {
            sb.append(server)
        }
        if (onDevice && !serverTookOver) {
            val elapsedMs = (System.nanoTime() - requestedAtNanos) / 1_000_000
            val leftMs = estimateMs - elapsedMs
            sb.append("  →  ").append(server).append(' ')
            when {
                leftMs > 1000 -> sb.append("in ~").append((leftMs + 999) / 1000).append('s')
                leftMs > -3000 -> sb.append("any moment now")
                else -> sb.append("still loading (+").append(-leftMs / 1000).append("s over)")
            }
            step?.let { sb.append(" · ").append(it) }
        }
        view.text = sb.toString()
    }
}
