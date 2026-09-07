#!/usr/bin/env python3
"""
Emits digest items for recent git commits (across a configured list of
local repos) and completed systemd --user timer/service runs. Meant to
run periodically (e.g. a systemd timer of its own, every 15-30 min) -
each run only emits what's new since the last one, tracked per-source via
a small cursor file.

Config (NEWSDIGEST_MACHINE_ACTIVITY_CONFIG, default
~/.config/newsdigest/machine-activity.json):
    {
      "repos": ["/home/you/src/project1", "/home/you/src/project2"],
      "systemd_units": ["some-timer.service", "another.service"]
    }

Writes to the "machine" entry's items_file in sources.json (default
~/.cache/newsdigest/machine.jsonl - override via
NEWSDIGEST_MACHINE_ITEMS_FILE).
"""
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from _jsonl_writer import append_items, read_cursor, write_cursor

CONFIG_FILE = Path(
    os.environ.get("NEWSDIGEST_MACHINE_ACTIVITY_CONFIG", str(Path.home() / ".config" / "newsdigest" / "machine-activity.json")),
)
ITEMS_FILE = Path(os.environ.get("NEWSDIGEST_MACHINE_ITEMS_FILE", str(Path.home() / ".cache" / "newsdigest" / "machine.jsonl")))
CURSOR_DIR = Path.home() / ".cache" / "newsdigest" / "cursors"
INITIAL_BACKFILL = 5  # first run per repo/unit: don't flood the feed with all of history


def _load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {"repos": [], "systemd_units": []}
    try:
        return json.loads(CONFIG_FILE.read_text())
    except Exception:
        return {"repos": [], "systemd_units": []}


def _collect_repo_commits(repo_path: str) -> list[dict]:
    repo = Path(repo_path).expanduser()
    if not (repo / ".git").exists():
        return []
    name = repo.name
    cursor_file = CURSOR_DIR / f"git-{name}.txt"
    last_sha = read_cursor(cursor_file)

    rev_range = f"{last_sha}..HEAD" if last_sha else f"-n {INITIAL_BACKFILL}"
    fmt = "%H%x1f%ct%x1f%s"
    try:
        out = subprocess.run(
            ["git", "-C", str(repo), "log", *rev_range.split(), f"--format={fmt}"],
            capture_output=True, text=True, timeout=15, check=True,
        ).stdout
    except Exception:
        return []

    items = []
    for line in out.strip().splitlines():
        parts = line.split("\x1f")
        if len(parts) != 3:
            continue
        sha, epoch, subject = parts
        published = datetime.fromtimestamp(int(epoch), tz=timezone.utc).isoformat()
        items.append({
            "id": f"git:{name}:{sha}",
            "title": f"{name}: {subject}",
            "summary": f"Commit {sha[:8]} in {name}",
            "link": None,
            "feed_title": "host3 activity",
            "tags": ["git", name],
            "published": published,
        })
    if items:
        write_cursor(cursor_file, items[0]["id"].rsplit(":", 1)[-1])  # newest sha (git log is newest-first)
    return items


def _collect_unit_run(unit: str) -> list[dict]:
    cursor_file = CURSOR_DIR / f"unit-{unit}.txt"
    last_seen = read_cursor(cursor_file)
    try:
        out = subprocess.run(
            ["systemctl", "--user", "show", unit, "-p", "ActiveExitTimestamp", "-p", "Result", "--value"],
            capture_output=True, text=True, timeout=10, check=True,
        ).stdout
    except Exception:
        return []
    lines = out.strip().splitlines()
    if len(lines) < 2:
        return []
    exit_timestamp, result = lines[0], lines[1]
    if not exit_timestamp or exit_timestamp == last_seen:
        return []
    write_cursor(cursor_file, exit_timestamp)
    if not last_seen:
        return []  # first run: just establish the cursor, don't emit a backlog item for "always ran before we watched"
    return [{
        "id": f"unit:{unit}:{exit_timestamp}",
        "title": f"{unit} finished ({result})",
        "summary": f"systemd --user unit {unit} completed with result: {result}",
        "link": None,
        "feed_title": "host3 activity",
        "tags": ["systemd", unit],
        "published": datetime.now(timezone.utc).isoformat(),
    }]


def main():
    config = _load_config()
    items = []
    for repo in config.get("repos", []):
        items.extend(_collect_repo_commits(repo))
    for unit in config.get("systemd_units", []):
        items.extend(_collect_unit_run(unit))
    append_items(ITEMS_FILE, items)
    print(f"machine_activity: {len(items)} new item(s)")


if __name__ == "__main__":
    main()
