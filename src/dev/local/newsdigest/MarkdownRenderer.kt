package dev.local.newsdigest

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View

/**
 * Markdown -> Spannable, matching server/text_clean.py's markdown_to_speech()
 * transform for transform-for-transform (same constructs, same order) so
 * the plain text underneath this view's spans is character-for-character
 * identical to what the server receives and echoes back per sentence.
 *
 * That equivalence matters beyond just this static view: ReadAloudController
 * now uses THIS rendered Spannable as its live-caption base too (see its
 * class doc) - the exact-match search it does to place each sentence's
 * highlight/tap-to-seek spans only works if what's on screen and what the
 * server times are the same string. Two independently-drifting "strip
 * markdown" implementations (one here, one in text_clean.py) previously
 * wasn't that - a link, code span, or bullet in a sentence made that
 * sentence's cleaned server text impossible to find in the raw
 * (unstripped) text this view used to show while reading, silently
 * skipping its highlight; confirmed live before this rewrite.
 */
object MarkdownRenderer {
    private val FENCED_CODE_RE = Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL)
    private val LINK_RE = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val CODE_SPAN_RE = Regex("""`([^`]+)`""")
    private val HEADER_RE = Regex("(?m)^#{1,6}\\s*")
    private val BULLET_RE = Regex("(?m)^\\s*[-*]\\s+")
    // One capture group for the wrapper (*** / ** / * / ___ / __ / _), same
    // as text_clean.py's _BOLD_ITALIC_RE - it doesn't distinguish bold from
    // italic either, just strips whichever wrapper matched. Bold styling
    // below is applied uniformly rather than trying to tell them apart,
    // matching that same "good enough, not exact" bar.
    private val EMPHASIS_RE = Regex("""(\*\*\*|\*\*|\*|___|__|_)(.+?)\1""")

    fun render(markdown: String, onLinkClick: (String) -> Unit): SpannableStringBuilder {
        // Plain string transforms first - none of these need span tracking,
        // so doing them before the link/emphasis passes (which do) keeps
        // those two simpler. Order matches text_clean.py except links come
        // after these rather than first; for this app's actual content
        // (narrative prose, not bulleted/coded reports) that reordering
        // doesn't change the outcome.
        var text = FENCED_CODE_RE.replace(markdown, " (code omitted) ")
        text = CODE_SPAN_RE.replace(text) { it.groupValues[1] }
        text = HEADER_RE.replace(text, "")
        text = BULLET_RE.replace(text, "")

        val builder = SpannableStringBuilder()
        var lastEnd = 0
        val linkSpans = mutableListOf<Triple<Int, Int, String>>()
        for (m in LINK_RE.findAll(text)) {
            builder.append(text.substring(lastEnd, m.range.first))
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            val start = builder.length
            builder.append(label)
            linkSpans.add(Triple(start, builder.length, url))
            lastEnd = m.range.last + 1
        }
        builder.append(text.substring(lastEnd))

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
        for (m in EMPHASIS_RE.findAll(currentText).toList().asReversed()) {
            val inner = m.groupValues[2]
            val start = m.range.first
            builder.replace(start, m.range.last + 1, inner)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, start + inner.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return builder
    }
}
