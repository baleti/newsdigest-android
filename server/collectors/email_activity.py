#!/usr/bin/env python3
"""
Emits digest items for new unread mail - subject and sender only, never
body content, since email is the most sensitive source this project
touches. Uses the existing `mail` CLI (~/.config/claude-email/mail,
IMAP-backed) - no new credentials. Meant to run periodically (e.g. a
systemd timer, every 15-30 min).

Config (NEWSDIGEST_EMAIL_ACTIVITY_CONFIG, default
~/.config/newsdigest/email-activity.json):
    {"accounts": ["work", "personal"], "mail_bin": "/path/to/mail"}

Empty/missing "accounts" uses the `mail` CLI's own default account.

Writes to the "email" entry's items_file in sources.json (default
~/.cache/newsdigest/email.jsonl - override via
NEWSDIGEST_EMAIL_ITEMS_FILE). Tracks the highest UID already emitted per
account so a re-run doesn't re-emit the same mail (note: this assumes
IMAP UIDVALIDITY doesn't change for the watched folder - rare, but would
require clearing that account's cursor file to recover from).
"""
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from _jsonl_writer import append_items, read_cursor, write_cursor

CONFIG_FILE = Path(
    os.environ.get("NEWSDIGEST_EMAIL_ACTIVITY_CONFIG", str(Path.home() / ".config" / "newsdigest" / "email-activity.json")),
)
ITEMS_FILE = Path(os.environ.get("NEWSDIGEST_EMAIL_ITEMS_FILE", str(Path.home() / ".cache" / "newsdigest" / "email.jsonl")))
CURSOR_DIR = Path.home() / ".cache" / "newsdigest" / "cursors"
DEFAULT_MAIL_BIN = str(Path.home() / ".config" / "claude-email" / "mail")
FETCH_LIMIT = 30


def _load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {}
    try:
        return json.loads(CONFIG_FILE.read_text())
    except Exception:
        return {}


def _collect_account(mail_bin: str, account: str | None) -> list[dict]:
    label = account or "default"
    cursor_file = CURSOR_DIR / f"email-{label}.txt"
    last_uid = int(read_cursor(cursor_file) or 0)

    argv = [mail_bin]
    if account:
        argv += ["--account", account]
    argv += ["list", "--unseen", f"--limit={FETCH_LIMIT}"]
    try:
        out = subprocess.run(argv, capture_output=True, text=True, timeout=30, check=True).stdout
    except Exception:
        return []

    items = []
    max_uid = last_uid
    for line in out.strip().splitlines():
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        uid_str, date, frm, subj = parts[0], parts[1], parts[2], parts[3]
        try:
            uid = int(uid_str)
        except ValueError:
            continue
        if uid <= last_uid:
            continue
        max_uid = max(max_uid, uid)
        items.append({
            "id": f"email:{label}:{uid}",
            "title": subj or "(no subject)",
            "summary": f"From {frm}",
            "link": None,
            "feed_title": f"email ({label})" if account else "email",
            "tags": ["email", label],
            "published": date or datetime.now(timezone.utc).isoformat(),
        })

    if max_uid != last_uid:
        write_cursor(cursor_file, str(max_uid))
    return items


def main():
    config = _load_config()
    mail_bin = config.get("mail_bin", DEFAULT_MAIL_BIN)
    accounts = config.get("accounts") or [None]  # None = mail CLI's own default account

    items = []
    for account in accounts:
        items.extend(_collect_account(mail_bin, account))
    append_items(ITEMS_FILE, items)
    print(f"email_activity: {len(items)} new item(s)")


if __name__ == "__main__":
    main()
