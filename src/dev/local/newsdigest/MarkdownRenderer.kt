package dev.local.newsdigest

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View

/**
 * Minimal markdown -> Spannable for the static (not currently being read
 * aloud) content view: bold text and clickable inline links, which is
 * what the digest and article text actually use. This is intentionally
 * NOT what read-aloud highlighting is driven from - see
 * ReadAloudController's class doc for why that uses a separate plain-text
 * "live caption" instead of trying to highlight inside this richer view.
 */
object MarkdownRenderer {
    private val LINK_RE = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val BOLD_RE = Regex("""\*\*(.+?)\*\*""")
    private val HEADER_RE = Regex("(?m)^#{1,6}\\s*")

    fun render(markdown: String, onLinkClick: (String) -> Unit): SpannableStringBuilder {
        val stripped = HEADER_RE.replace(markdown, "")

        val builder = SpannableStringBuilder()
        var lastEnd = 0
        val linkSpans = mutableListOf<Triple<Int, Int, String>>()
        for (m in LINK_RE.findAll(stripped)) {
            builder.append(stripped.substring(lastEnd, m.range.first))
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            val start = builder.length
            builder.append(label)
            linkSpans.add(Triple(start, builder.length, url))
            lastEnd = m.range.last + 1
        }
        builder.append(stripped.substring(lastEnd))

        for ((start, end, url) in linkSpans) {
            builder.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = onLinkClick(url)
                },
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        // Back-to-front so each replace()'s automatic span-shifting (a
        // SpannableStringBuilder/Editable guarantee) never invalidates an
        // offset a not-yet-processed earlier match still needs - only text
        // to the *right* of each edit moves, and we've already handled
        // everything to the right by the time we get here.
        val currentText = builder.toString()
        for (m in BOLD_RE.findAll(currentText).toList().asReversed()) {
            val inner = m.groupValues[1]
            val start = m.range.first
            builder.replace(start, m.range.last + 1, inner)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, start + inner.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return builder
    }
}
