"""
Strips markdown syntax down to plain spoken text before TTS. Runs before
acronym expansion and sentence splitting - a raw [label](url) link would
otherwise have its URL read aloud, and periods inside a URL would confuse
sentence splitting.
"""
import re

_LINK_RE = re.compile(r"\[([^\]]+)\]\([^)]+\)")
_HEADER_RE = re.compile(r"^#{1,6}\s*", re.MULTILINE)
_BOLD_ITALIC_RE = re.compile(r"(\*\*\*|\*\*|\*|___|__|_)(.+?)\1")
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
