#!/usr/bin/env python3
"""
Emits digest items from GitHub notifications (PRs/issues opened or
commented on, review requests, mentions - anything `gh` already
authenticates for) plus new commits on a configured list of repos. Uses
the `gh` CLI's existing auth - no new credentials. Meant to run
periodically (e.g. a systemd timer, every 15-30 min).

Config (NEWSDIGEST_GITHUB_ACTIVITY_CONFIG, default
~/.config/newsdigest/github-activity.json):
    {
      "watch_notifications": true,
      "repos": ["yourname/some-repo"]
    }

`repos` is only for the "new commits" half - notifications already cover
every repo `gh`'s account has access to, so `repos` can be empty if
notifications alone are enough.

Writes to the "github" entry's items_file in sources.json (default
~/.cache/newsdigest/github.jsonl - override via
NEWSDIGEST_GITHUB_ITEMS_FILE).
"""
import json
import os
import subprocess
from pathlib import Path

from _jsonl_writer import append_items, read_cursor, write_cursor

CONFIG_FILE = Path(
    os.environ.get("NEWSDIGEST_GITHUB_ACTIVITY_CONFIG", str(Path.home() / ".config" / "newsdigest" / "github-activity.json")),
)
ITEMS_FILE = Path(os.environ.get("NEWSDIGEST_GITHUB_ITEMS_FILE", str(Path.home() / ".cache" / "newsdigest" / "github.jsonl")))
CURSOR_DIR = Path.home() / ".cache" / "newsdigest" / "cursors"
INITIAL_BACKFILL = 5


def _load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {"watch_notifications": True, "repos": []}
    try:
        return json.loads(CONFIG_FILE.read_text())
    except Exception:
        return {"watch_notifications": True, "repos": []}


def _gh_json(args: list[str]) -> list | dict | None:
    try:
        out = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=20, check=True).stdout
        return json.loads(out) if out.strip() else None
    except Exception:
        return None


def _collect_notifications() -> list[dict]:
    cursor_file = CURSOR_DIR / "github-notifications.txt"
    last_seen = read_cursor(cursor_file)
    notifications = _gh_json(["api", "notifications", "--paginate"]) or []
    if not isinstance(notifications, list):
        return []

    newest_seen = last_seen
    items = []
    for n in notifications:
        updated_at = n.get("updated_at", "")
        if last_seen and updated_at <= last_seen:
            continue
        subject = n.get("subject", {})
        repo_name = n.get("repository", {}).get("full_name", "")
        title = subject.get("title", "(untitled)")
        reason = n.get("reason", "")
        api_url = subject.get("url") or ""
        items.append({
            "id": f"github-notif:{n.get('id')}",
            "title": f"{repo_name}: {title}",
            "summary": f"{subject.get('type', 'notification')} - {reason.replace('_', ' ')}",
            "link": api_url.replace("api.github.com/repos", "github.com").replace("/pulls/", "/pull/") or None,
            "feed_title": "github",
            "tags": ["github", repo_name],
            "published": updated_at,
        })
        if not newest_seen or updated_at > newest_seen:
            newest_seen = updated_at

    if newest_seen and newest_seen != last_seen:
        write_cursor(cursor_file, newest_seen)
    return items


def _collect_repo_commits(repo: str) -> list[dict]:
    cursor_file = CURSOR_DIR / f"github-commits-{repo.replace('/', '_')}.txt"
    last_sha = read_cursor(cursor_file)

    commits = _gh_json(["api", f"repos/{repo}/commits", "--jq", "."]) or []
    if not isinstance(commits, list):
        return []

    items = []
    for c in commits[:INITIAL_BACKFILL] if not last_sha else commits:
        sha = c.get("sha", "")
        if last_sha and sha == last_sha:
            break
        message = (c.get("commit", {}).get("message") or "").splitlines()[0]
        items.append({
            "id": f"github-commit:{repo}:{sha}",
            "title": f"{repo}: {message}",
            "summary": f"Commit {sha[:8]} pushed to {repo}",
            "link": c.get("html_url"),
            "feed_title": "github",
            "tags": ["github", repo],
            "published": c.get("commit", {}).get("author", {}).get("date", ""),
        })
    if commits:
        write_cursor(cursor_file, commits[0].get("sha", ""))
    return items


def main():
    config = _load_config()
    items = []
    if config.get("watch_notifications", True):
        items.extend(_collect_notifications())
    for repo in config.get("repos", []):
        items.extend(_collect_repo_commits(repo))
    append_items(ITEMS_FILE, items)
    print(f"github_activity: {len(items)} new item(s)")


if __name__ == "__main__":
    main()
