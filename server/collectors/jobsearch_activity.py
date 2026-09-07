#!/usr/bin/env python3
"""
Emits digest items about job-search activity. Two independent halves:

1. Local pipeline state (always safe, on by default) - reads your
   existing job-search pipeline's own state files (a "last run" cursor
   file and a JSON file tracking companies/posts checked) to report
   things like "pipeline last ran at X, tracking N companies". Pure
   local file reads, no network, no browser.

2. Browser notification counts (READ-ONLY, off by default) - checking
   LinkedIn/Instagram for new connections/notifications by reusing an
   *already logged-in* browser profile over the Chrome DevTools Protocol
   (same idea as this project's existing Reddit CDP automation), rather
   than any new login or automated action. NOT implemented here beyond a
   scaffold: which page/selector actually holds a notification count is
   specific to your own browser profile and LinkedIn/Instagram's current
   DOM (which changes), so `_check_browser_notifications()` is a
   documented stub you fill in once you've confirmed it against your own
   session - don't guess at this blind.

   These platforms actively detect and can ban automated access, even
   read-only, if it looks bot-like (fixed intervals, headless fingerprint,
   etc.) - keep this disabled unless you've weighed that, and if you do
   enable it, run it at most once a day, never polling.

Config (NEWSDIGEST_JOBSEARCH_ACTIVITY_CONFIG, default
~/.config/newsdigest/jobsearch-activity.json):
    {
      "pipeline_state_dir": "/home/you/.local/share/architect-job-search/state",
      "enable_browser_check": false,
      "cdp_port": 9223
    }

Writes to the "jobsearch" entry's items_file in sources.json (default
~/.cache/newsdigest/jobsearch.jsonl - override via
NEWSDIGEST_JOBSEARCH_ITEMS_FILE).
"""
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from _jsonl_writer import append_items, read_cursor, write_cursor

CONFIG_FILE = Path(
    os.environ.get("NEWSDIGEST_JOBSEARCH_ACTIVITY_CONFIG", str(Path.home() / ".config" / "newsdigest" / "jobsearch-activity.json")),
)
ITEMS_FILE = Path(os.environ.get("NEWSDIGEST_JOBSEARCH_ITEMS_FILE", str(Path.home() / ".cache" / "newsdigest" / "jobsearch.jsonl")))
CURSOR_FILE = Path.home() / ".cache" / "newsdigest" / "cursors" / "jobsearch-pipeline.txt"


def _load_config() -> dict:
    if not CONFIG_FILE.exists():
        return {}
    try:
        return json.loads(CONFIG_FILE.read_text())
    except Exception:
        return {}


def _collect_pipeline_state(state_dir: Path) -> list[dict]:
    last_run_file = state_dir / "last_run"
    seen_posts_file = state_dir / "seen-posts.json"
    if not last_run_file.exists():
        return []

    try:
        last_run_epoch = int(last_run_file.read_text().strip())
    except Exception:
        return []
    last_run_iso = datetime.fromtimestamp(last_run_epoch, tz=timezone.utc).isoformat()

    prev_cursor = read_cursor(CURSOR_FILE)
    if prev_cursor == last_run_iso:
        return []  # pipeline hasn't run again since we last reported it
    write_cursor(CURSOR_FILE, last_run_iso)

    company_count = None
    if seen_posts_file.exists():
        try:
            company_count = len(json.loads(seen_posts_file.read_text()))
        except Exception:
            pass

    summary = f"Tracking {company_count} companies." if company_count is not None else "Ran another check."
    return [{
        "id": f"jobsearch:pipeline:{last_run_iso}",
        "title": "Job-search pipeline ran",
        "summary": summary,
        "link": None,
        "feed_title": "job search",
        "tags": ["jobsearch"],
        "published": last_run_iso,
    }]


def _check_browser_notifications(cdp_port: int) -> list[dict]:
    """Scaffold only - see the risk note in this module's docstring.
    Fill in once you've confirmed, in your own browser session, which
    open tab and which element actually holds a notification count for
    each platform you want covered."""
    print("jobsearch_activity: enable_browser_check is on, but _check_browser_notifications() "
          "is an unfilled scaffold - see this file's docstring. Skipping.")
    return []


def main():
    config = _load_config()
    items = []

    state_dir = Path(
        config.get("pipeline_state_dir", str(Path.home() / ".local" / "share" / "architect-job-search" / "state")),
    ).expanduser()
    items.extend(_collect_pipeline_state(state_dir))

    if config.get("enable_browser_check"):
        items.extend(_check_browser_notifications(config.get("cdp_port", 9223)))

    append_items(ITEMS_FILE, items)
    print(f"jobsearch_activity: {len(items)} new item(s)")


if __name__ == "__main__":
    main()
