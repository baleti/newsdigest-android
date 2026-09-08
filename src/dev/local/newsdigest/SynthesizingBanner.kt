package dev.local.newsdigest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * Sticky "Synthesizing... ~Xs" banner shown between pressing Read Aloud
 * and the first sentence actually starting, and again during any later
 * gap waiting on the next one - Chatterbox especially can take 5-15s per
 * sentence, and without this the gap reads as the app having frozen
 * rather than still working (confirmed live). Full-width, placed above
 * the scrolling content by the caller so it stays visible regardless of
 * scroll position.
 *
 * The estimate counts down in real time from ReadAloudController's own
 * rolling average of this session's observed synth_ms per sentence
 * (seeded with an engine-aware guess before any real data exists) -
 * clamped at "~1s" rather than hitting zero or going negative if the
 * real wait runs long past the estimate.
 */
class SynthesizingBanner(context: Context) {
    val view: TextView = TextView(context).apply {
        textSize = 13f
        setTextColor(Theme.onPrimary)
        background = Theme.roundedDrawable(Theme.primary, context, radiusDp = 0)
        setPadding(Theme.dp(context, 16), Theme.dp(context, 10), Theme.dp(context, 16), Theme.dp(context, 10))
        visibility = View.GONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var deadlineAtNanos = 0L
    private val tick = object : Runnable {
        override fun run() {
            val remainingMs = (deadlineAtNanos - System.nanoTime()) / 1_000_000
            val displaySec = ((remainingMs + 999) / 1000).coerceAtLeast(1) // round up, never show 0
            view.text = "Synthesizing… ~${displaySec}s"
            handler.postDelayed(this, 250)
        }
    }

    fun start(estimatedMs: Long) {
        deadlineAtNanos = System.nanoTime() + estimatedMs * 1_000_000
        view.visibility = View.VISIBLE
        handler.removeCallbacks(tick)
        tick.run()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        view.visibility = View.GONE
    }
}
