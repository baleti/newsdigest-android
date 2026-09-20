"""
Strips markdown syntax down to plain spoken text before TTS. Runs before
acronym expansion and sentence splitting - a raw [label](url) link would
otherwise have its URL read aloud, and periods inside a URL would confuse
sentence splitting.
"""
import re

_LINK_RE = re.compile(r"\[([^\]]+)\]\([^)]+\)")
_HEADER_RE = re.compile(r"^#{1,6}\s*", re.MULTILINE)
# Underscore variants (___, __, _) deliberately excluded from this
# alternation - confirmed live 2026-09-09: a digest mentioning its own
# code (snake_case identifiers like text_clean.py or _BOLD_ITALIC_RE) got
# silently mangled, because a single "_" reads as valid ad-hoc italic
# open/close on both sides of any inner "_word_" run: "text_clean.py's
# _BOLD_ITALIC_RE" became "textclean.py's BOLDITALIC_RE" - the regex
# doesn't (and, this simply, can't) apply CommonMark's real rule that
# underscore emphasis must sit at a word boundary, not just anywhere.
# Asterisks aren't ambiguous the same way (words don't naturally contain
# "*"), and the generator prompt only ever asks for "**bold**" anyway, so
# dropping underscore support entirely removes the false-positive risk
# with no loss of real functionality.
#
# re.DOTALL so a bold/italic run spanning a line break (an embedded "\n" in
# the source, not word-wrap) still matches -- without it "." can't cross a
# newline, so the closing wrapper on a later line was never found and the
# markers were left in the spoken text. Must stay in sync with
# MarkdownRenderer.kt's EMPHASIS_RE (same fix there) -- this function's
# output is the exact string ReadAloudController searches the client's own
# rendered text for to place each sentence's highlight; the two diverging
# here would silently break that match again.
_BOLD_ITALIC_RE = re.compile(r"(\*\*\*|\*\*|\*)(.+?)\1", re.DOTALL)
_BULLET_RE = re.compile(r"^\s*[-*]\s+", re.MULTILINE)
_CODE_SPAN_RE = re.compile(r"`([^`]+)`")
# Fenced blocks are handled separately from inline `code spans` above - a
# multi-line source diff read aloud character-by-character is useless, so
# it's replaced with a short spoken placeholder instead of unwrapped like a
# short inline span is.
_FENCED_CODE_RE = re.compile(r"```.*?```", re.DOTALL)


def markdown_to_speech(text: str) -> str:
    text = _FENCED_CODE_RE.sub(" (code omitted) ", text)
    text = _LINK_RE.sub(r"\1", text)
    text = _CODE_SPAN_RE.sub(r"\1", text)
    text = _HEADER_RE.sub("", text)
    text = _BULLET_RE.sub("", text)
    text = _BOLD_ITALIC_RE.sub(r"\2", text)
    return text


if __name__ == "__main__":
    import sys
    print(markdown_to_speech(sys.stdin.read()))
