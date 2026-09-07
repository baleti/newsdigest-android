"""
Tiny shared helper for News Digest collectors. Each collector's job is
just: figure out what's new since last time, append it as JSONL in the
shared item schema (see README's "Sources" section), and remember where
it left off. This module is the "remember where it left off" and
"append" halves, kept generic on purpose - what a cursor value *means*
(a timestamp, a commit sha, a notification id) is entirely up to the
collector using it.
"""
import json
from pathlib import Path


def read_cursor(cursor_file: Path) -> str | None:
    try:
        value = cursor_file.read_text().strip()
        return value or None
    except FileNotFoundError:
        return None


def write_cursor(cursor_file: Path, value: str) -> None:
    cursor_file.parent.mkdir(parents=True, exist_ok=True)
    cursor_file.write_text(value)


def append_items(items_file: Path, items: list[dict]) -> None:
    if not items:
        return
    items_file.parent.mkdir(parents=True, exist_ok=True)
    with open(items_file, "a") as f:
        for item in items:
            f.write(json.dumps(item) + "\n")
