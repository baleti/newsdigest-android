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
 * finished steps above, the current one last with how long it has been
 * running. Two anti-flicker rules, asked for explicitly 2026-10-04:
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
        const val MAX_LINES = 5
        const val LINE_MAX_AGE_MS = 25_000L
    }

    private class Step(val text: String, val sentence: Int, val of: Int, val atNanos: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val steps = ArrayList<Step>()
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
        val sb = StringBuilder("Synthesizing… ~${displaySec}s")
        val recent = steps.filter { (now - it.atNanos) / 1_000_000 <= LINE_MAX_AGE_MS }.takeLast(MAX_LINES)
        for ((i, step) in recent.withIndex()) {
            val last = i == recent.lastIndex
            sb.append('\n').append(if (last) "▸ " else "· ")
            if (step.sentence > 0) sb.append('[').append(step.sentence).append('/').append(step.of).append("] ")
            sb.append(step.text)
            if (last) {
                val sec = (now - step.atNanos) / 1_000_000_000
                if (sec >= 2) sb.append(" (").append(sec).append("s)")
            }
        }
        view.text = sb.toString()
    }

    /** A step message from the server's `status` event. Safe to call while
     * the banner is hidden - it is kept for when the banner next appears. */
    fun addStatus(message: String, sentence: Int = 0, of: Int = 0) {
        if (steps.lastOrNull()?.text == message && steps.last().sentence == sentence) return
        steps.add(Step(message, sentence, of, System.nanoTime()))
        while (steps.size > 20) steps.removeAt(0)
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
