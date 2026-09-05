"""
Expands all-caps acronyms into letter-by-letter spelling before TTS synthesis
(e.g. "RCE" -> "R C E") so engines that mangle them as fake words (Kokoro and
Chatterbox both do, same as the "@Voice Aloud" complaint that started this)
read them the way a person would say them out loud.

A short, human-editable exception list holds acronyms that are actually
pronounced as a word (NASA, OK, ...) - append to word-acronyms.txt as new
ones show up in real digests, same convention as rssd's feeds file.
"""
import re
from pathlib import Path

_EXCEPTIONS_FILE = Path(__file__).parent / "word-acronyms.txt"

_DEFAULT_EXCEPTIONS = {
    "NASA", "RADAR", "LASER", "NATO", "UNESCO", "SCUBA", "ASCII", "AIDS",
    "UNICEF", "OPEC", "INTERPOL", "SWAT", "ZIP", "GIF", "OK", "COVID",
}

# Two-to-six caps letters, optionally with a trailing lowercase "s" for the
# plural ("CVEs"). Deliberately does not try to handle mixed alnum tokens
# like "GPT-6" as a single unit - the hyphen already acts as a word
# boundary, so "GPT" spells out and "-6" is left for the engine's own
# number handling, which is normally fine.
_ACRONYM_RE = re.compile(r"\b([A-Z]{2,6})(s)?\b")


def _load_exceptions() -> set[str]:
    exceptions = set(_DEFAULT_EXCEPTIONS)
    if _EXCEPTIONS_FILE.exists():
        for line in _EXCEPTIONS_FILE.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                exceptions.add(line.upper())
    return exceptions


def expand_acronyms(text: str, exceptions: set[str] | None = None) -> str:
    exceptions = exceptions if exceptions is not None else _load_exceptions()

    def repl(m: re.Match) -> str:
        word, plural = m.group(1), m.group(2)
        if word in exceptions:
            return m.group(0)
        spelled = " ".join(word)
        return spelled + "'s" if plural else spelled

    return _ACRONYM_RE.sub(repl, text)


if __name__ == "__main__":
    import sys
    print(expand_acronyms(sys.stdin.read()))
