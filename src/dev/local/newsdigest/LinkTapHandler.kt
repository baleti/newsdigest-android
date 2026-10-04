package dev.local.newsdigest

import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import kotlin.math.abs

/**
 * Replacement for LinkMovementMethod on a TextView that lives inside a
 * ScrollView and carries many ClickableSpans - a long digest can now
 * carry 100+ reference links (see the ordering/actionable-first prompt
 * change), and read-aloud's word-tap-seek adds one ClickableSpan per
 * word on top of that. LinkMovementMethod hit-tests spans on every touch
 * MOVE event, not just a tap, which measurably degrades scroll
 * smoothness the more spans there are - reported live 2026-09-09 as
 * scroll flicker/stutter on a ~6,500-word, ~100-link digest.
 *
 * This only reacts to an actual tap (short duration, minimal movement)
 * and never consumes the touch event either way, so the parent
 * ScrollView drives every drag/fling exactly as if the TextView had no
 * click handling at all.
 */
object LinkTapHandler {
    private const val TAP_SLOP_PX = 20
    private const val TAP_MAX_MS = 300

    fun attach(textView: TextView) {
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        textView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val movedPx = maxOf(abs(event.x - downX), abs(event.y - downY))
                    val elapsedMs = event.eventTime - downTime
                    // Root cause found live 2026-09-20: this checked
                    // `as? Spannable`, but a plain `textView.text = x`
                    // assignment (BufferType.NORMAL, the default) copies
                    // any Spanned source into an immutable SpannedString
                    // -- spans survive the copy (SpannedString still
                    // implements Spanned), but SpannedString does NOT
                    // implement the separate, stricter Spannable interface
                    // (mutation methods this code never needed anyway,
                    // getSpans() is on Spanned), so the cast silently
                    // returned null on every real tap. This is why link
                    // taps (and word-tap-seek) were reported broken twice
                    // before with "no obvious cause found by reading the
                    // code" -- the diagnostic logging below is what
                    // finally caught it (tap registered, but
                    // spannable=null).
                    if (movedPx < TAP_SLOP_PX && elapsedMs < TAP_MAX_MS) {
                        val tv = view as TextView
                        val layout = tv.layout
                        val spannable = tv.text as? Spanned
                        if (layout != null && spannable != null) {
                            val x = (event.x.toInt() - tv.totalPaddingLeft + tv.scrollX).toFloat()
                            val y = event.y.toInt() - tv.totalPaddingTop + tv.scrollY
                            val line = layout.getLineForVertical(y)
                            val offset = layout.getOffsetForHorizontal(line, x)
                            val spans = spannable.getSpans(offset, offset, ClickableSpan::class.java)
                            val nearby = spannable.subSequence(
                                (offset - 15).coerceAtLeast(0),
                                (offset + 15).coerceAtMost(spannable.length),
                            )
                            Log.d("LinkTapHandler", "tap registered: offset=$offset spansFound=${spans.size} nearbyText=$nearby")
                            spans.firstOrNull()?.onClick(tv)
                        } else {
                            Log.d("LinkTapHandler", "tap ignored: layout=$layout spannable=$spannable (text type=${tv.text?.javaClass})")
                        }
                    } else {
                        Log.d("LinkTapHandler", "tap rejected as drag: movedPx=$movedPx elapsedMs=$elapsedMs")
                    }
                }
            }
            false // never consume - the parent ScrollView still sees every drag/fling untouched
        }
    }
}
