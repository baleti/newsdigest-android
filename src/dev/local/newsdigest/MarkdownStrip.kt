package dev.local.newsdigest

/**
 * Strips markdown formatting down to plain text -- ported from
 * dictate-android's own MarkdownStrip (same regex passes, same order;
 * that one migrated the old Termux .shortcuts/md-clip-plain script,
 * this is just a second copy for DetailActivity's own "copy article as
 * plain text" long-press action, asked for explicitly 2026-09-20).
 */
object MarkdownStrip {
    fun strip(input: String): String {
        var text = input

        // Fenced code blocks: drop the fence markers, keep the code content.
        text = Regex("```[^\n]*\n(.*?)```", RegexOption.DOT_MATCHES_ALL).replace(text) { it.groupValues[1] }
        text = Regex("~~~[^\n]*\n(.*?)~~~", RegexOption.DOT_MATCHES_ALL).replace(text) { it.groupValues[1] }

        // Inline code: `code` -> code
        text = Regex("`([^`]+)`").replace(text) { it.groupValues[1] }

        // Images: ![alt](url) -> alt
        text = Regex("""!\[([^\]]*)\]\([^)]*\)""").replace(text) { it.groupValues[1] }

        // Links: [text](url) -> text
        text = Regex("""\[([^\]]+)\]\([^)]*\)""").replace(text) { it.groupValues[1] }

        // Reference-style links: [text][ref] -> text
        text = Regex("""\[([^\]]+)\]\[[^\]]*\]""").replace(text) { it.groupValues[1] }

        // Headers: leading #'s
        text = Regex("""^\s{0,3}#{1,6}\s*""", RegexOption.MULTILINE).replace(text, "")

        // Blockquotes
        text = Regex("""^\s{0,3}>\s?""", RegexOption.MULTILINE).replace(text, "")

        // Horizontal rules
        text = Regex("""^\s{0,3}([-*_])\s*(\1\s*){2,}$""", RegexOption.MULTILINE).replace(text, "")

        // Bold+italic (***text*** / ___text___)
        text = Regex("""\*\*\*(.+?)\*\*\*""").replace(text) { it.groupValues[1] }
        text = Regex("""___(.+?)___""").replace(text) { it.groupValues[1] }

        // Bold (**text** / __text__)
        text = Regex("""\*\*(.+?)\*\*""").replace(text) { it.groupValues[1] }
        text = Regex("""__(.+?)__""").replace(text) { it.groupValues[1] }

        // Italic (*text* / _text_). Require a non-word char (or string
        // edge) outside the markers and no space just inside, so things
        // like "price*quantity" or "my_variable_name" are left untouched.
        text = Regex("""(?<!\w)\*(?!\*)(?!\s)(.+?)(?<!\s)(?<!\*)\*(?!\w)""").replace(text) { it.groupValues[1] }
        text = Regex("""(?<!\w)_(?!_)(?!\s)(.+?)(?<!\s)(?<!_)_(?!\w)""").replace(text) { it.groupValues[1] }

        // Strikethrough
        text = Regex("""~~(.+?)~~""").replace(text) { it.groupValues[1] }

        // List markers: -, *, +, and ordered "1."
        text = Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE).replace(text, "")
        text = Regex("""^\s*\d+\.\s+""", RegexOption.MULTILINE).replace(text, "")

        // Collapse 3+ blank lines down to 1
        text = Regex("""\n{3,}""").replace(text, "\n\n")

        return text.trim()
    }
}
