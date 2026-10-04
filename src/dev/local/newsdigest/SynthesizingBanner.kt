package dev.local.newsdigest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * Sticky "Synthesizing..." banner shown while waiting on the server for
 * the next audio - between pressing Read Aloud and the first sentence, and
 * during any later gap. Chatterbox can take 5-30s per sentence (cold model
 * load, waiting on the GPU arbiter, another model being evicted), and
 * without this the wait reads as the app having frozen. Full-width, placed
 * above the scrolling content by the caller so it stays visible regardless
 * of scroll position.
 *
 * Besides the countdown (ReadAloudController's rolling average of observed
 * synth_ms, clamped at "~1s"), it shows the server's own step-by-step
 * `status` messages (see server.py's _progress) as a short multiline log:
 * the sentence it is waiting on ("sentence 30 of 135"), its finished steps
 * marked ✓ and the current one marked ▸ with how long it has been running. Two anti-flicker rules, asked for explicitly 2026-10-04:
 *  - it only appears once the wait has lasted SHOW_DELAY_MS, so the
 *    split-second gaps between sentences mid-read never show it;
 *  - once shown it stays for at least MIN_VISIBLE_MS.
 * Status lines keep arriving while it is hidden (the server works ahead of
 * playback), so when it does appear it already has the recent history.
 */
class SynthesizingBanner(context: Context) {
    val view: TextView = TextView(context).apply {
        textSize = 13f
        setTextColor(Theme.onPrimary)
        background = Theme.roundedDrawable(Theme.primary, context, radiusDp = 0)
        setPadding(Theme.dp(context, 16), Theme.dp(context, 10), Theme.dp(context, 16), Theme.dp(context, 10))
        visibility = View.GONE
    }

    private companion object {
        const val SHOW_DELAY_MS = 1200L
        const val MIN_VISIBLE_MS = 1500L
        const val MAX_LINES = 4
    }

    private class Step(val text: String, val atNanos: Long)

    private val handler = Handler(Looper.getMainLooper())
    // Steps of the ONE sentence playback is currently blocked on (the
    // server only forwards that one - see server.py's blocking/on_status).
    // Everything but the last is finished; the last is in progress.
    private val steps = ArrayList<Step>()
    private var sentence = 0
    private var sentenceCount = 0
    private var waiting = false
    private var deadlineAtNanos = 0L
    private var visibleSinceNanos = 0L

    private val showRunnable = Runnable {
        if (!waiting) return@Runnable
        visibleSinceNanos = System.nanoTime()
        view.visibility = View.VISIBLE
        tick.run()
    }

    private val hideRunnable = Runnable { hideNow() }

    private val tick = object : Runnable {
        override fun run() {
            if (view.visibility != View.VISIBLE) return
            render()
            handler.postDelayed(this, 250)
        }
    }

    private fun render() {
        val now = System.nanoTime()
        val remainingMs = (deadlineAtNanos - now) / 1_000_000
        val displaySec = ((remainingMs + 999) / 1000).coerceAtLeast(1) // round up, never show 0
        val sb = StringBuilder("Synthesizing")
        if (sentence > 0) sb.append(" sentence ").append(sentence).append(" of ").append(sentenceCount)
        sb.append(" · ~").append(displaySec).append('s')
        val shown = steps.takeLast(MAX_LINES)
        for ((i, step) in shown.withIndex()) {
            val current = i == shown.lastIndex
            sb.append('\n').append(if (current) "▸ " else "✓ ").append(step.text)
            if (current) {
                val sec = (now - step.atNanos) / 1_000_000_000
                if (sec >= 2) sb.append(" (").append(sec).append("s)")
            }
        }
        view.text = sb.toString()
    }

    /** A step message from the server's `status` event. Safe to call while
     * the banner is hidden - it is kept for when the banner next appears. */
    fun addStatus(message: String, sentenceNo: Int = 0, of: Int = 0) {
        // A different sentence (or the "Starting..." message of a new read,
        // sentence 0) means the previous sentence's steps are history.
        if (sentenceNo != sentence || sentenceNo == 0) steps.clear()
        sentence = sentenceNo
        sentenceCount = of
        if (steps.lastOrNull()?.text != message) steps.add(Step(message, System.nanoTime()))
        if (view.visibility == View.VISIBLE) render()
    }

    fun start(estimatedMs: Long) {
        deadlineAtNanos = System.nanoTime() + estimatedMs * 1_000_000
        handler.removeCallbacks(hideRunnable)
        if (view.visibility == View.VISIBLE) {
            if (!waiting) tick.run()
            waiting = true
            return
        }
        if (!waiting) handler.postDelayed(showRunnable, SHOW_DELAY_MS)
        waiting = true
    }

    fun stop() {
        waiting = false
        handler.removeCallbacks(showRunnable)
        if (view.visibility != View.VISIBLE) return
        val visibleMs = (System.nanoTime() - visibleSinceNanos) / 1_000_000
        if (visibleMs >= MIN_VISIBLE_MS) hideNow()
        else {
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, MIN_VISIBLE_MS - visibleMs)
        }
    }

    private fun hideNow() {
        handler.removeCallbacks(tick)
        view.visibility = View.GONE
    }
}
