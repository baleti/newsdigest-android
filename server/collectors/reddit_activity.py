#!/usr/bin/env python3
"""
Emits digest items from a Reddit bot's own action logs - what it
actually posted/commented, not new browser automation against a personal
account. Reuses logs the bot already writes for its own record-keeping,
so this collector only reads local files. Meant to run periodically
(e.g. a systemd timer, every 15-30 min, or right after the bot's own run).

Config (NEWSDIGEST_REDDIT_ACTIVITY_CONFIG, default
~/.config/newsdigest/reddit-activity.json):
    {"logs_dir": "/home/you/.local/share/reddit-architecture-bot/logs"}

Expects each run to write a "<timestamp>_actions.md" file shaped like:
    # r/Subreddit1, r/Subreddit2

    ## Comment 1
    - Post: Some Title (https://reddit.com/...)
    - Replying to: u/someone - "..."
    - My reply: "..."

    ## Notes
    free text

Writes to the "reddit" entry's items_file in sources.json (default
~/.cache/newsdigest/reddit.jsonl - override via
NEWSDIGEST_REDDIT_ITEMS_FILE).
"""
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from _jsonl_writer import append_items, read_cursor, write_cursor

CONFIG_FILE = Path(
    os.environ.get("NEWSDIGEST_REDDIT_ACTIVITY_CONFIG", str(Path.home() / ".config" / "newsdigest" / "reddit-activity.json")),
)
ITEMS_FILE = Path(os.environ.get("NEWSDIGEST_REDDIT_ITEMS_FILE", str(Path.home() / ".cache" / "newsdigest" / "reddit.jsonl")))
CURSOR_FILE = Path.home() / ".cache" / "newsdigest" / "cursors" / "reddit-activity.txt"

_POST_RE = re.compile(r"-\s*Post:\s*(.+?)\s*\((https?://\S+)\)")
_REPLY_RE = re.compile(r'-\s*My reply:\s*"(.+?)"\s*$', re.DOTALL)
_COMMENT_BLOCK_RE = re.compile(r"^##\s*Comment\s*\d+\s*$(.*?)(?=^##\s|\Z)", re.MULTILINE | re.DOTALL)
_HEADER_RE = re.compile(r"^#\s*(.+)$", re.MULTILINE)
_FILENAME_TS_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})_(\d{2})(\d{2})(\d{2})_actions\.md$")


def _load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {}
    try:
        return json.loads(CONFIG_FILE.read_text())
    except Exception:
        return {}


def _filename_to_iso(name: str) -> str:
    m = _FILENAME_TS_RE.match(name)
    if not m:
        return datetime.now(timezone.utc).isoformat()
    date, hh, mm, ss = m.groups()
    return f"{date}T{hh}:{mm}:{ss}+00:00"


def _parse_log(path: Path) -> list[dict]:
    text = path.read_text(errors="ignore")
    header_match = _HEADER_RE.search(text)
    subreddits = header_match.group(1).strip() if header_match else "unknown"
    published = _filename_to_iso(path.name)

    items = []
    for block in _COMMENT_BLOCK_RE.findall(text):
        post_match = _POST_RE.search(block)
        reply_match = _REPLY_RE.search(block)
        if not post_match:
            continue
        post_title, post_url = post_match.groups()
        reply_text = reply_match.group(1).strip() if reply_match else ""
        items.append({
            "id": f"reddit:{path.stem}:{post_url}",
            "title": f"Commented on: {post_title}",
            "summary": reply_text,
            "link": post_url,
            "feed_title": "reddit bot",
            "tags": ["reddit", subreddits],
            "published": published,
        })
    return items


def main():
    config = _load_config()
    logs_dir = Path(config.get("logs_dir", str(Path.home() / ".local" / "share" / "reddit-architecture-bot" / "logs"))).expanduser()
    if not logs_dir.exists():
        print("reddit_activity: logs_dir not found, nothing to do")
        return

    last_seen = read_cursor(CURSOR_FILE)
    log_files = sorted(p for p in logs_dir.glob("*_actions.md") if not last_seen or p.name > last_seen)

    items = []
    for path in log_files:
        items.extend(_parse_log(path))

    if log_files:
        write_cursor(CURSOR_FILE, log_files[-1].name)
    append_items(ITEMS_FILE, items)
    print(f"reddit_activity: {len(items)} new item(s) from {len(log_files)} log file(s)")


if __name__ == "__main__":
    main()
