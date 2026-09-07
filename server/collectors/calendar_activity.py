#!/usr/bin/env python3
"""
Emits digest items for upcoming Google Calendar events (today/tomorrow by
default). Talks to the Calendar API v3 directly over REST (just
`requests` + a refresh token) rather than pulling in the full Google API
client library, since all this needs is one endpoint.

Config (NEWSDIGEST_CALENDAR_ACTIVITY_CONFIG, default
~/.config/newsdigest/calendar-activity.json):
    {
      "token_file": "/path/to/calendar-token.json",
      "calendar_id": "primary",
      "days_ahead": 2
    }

token_file is a small JSON file you create once from your own OAuth
setup - see [[reference_google_cloud_oauth_doc]] for how to get a
client_id/client_secret/refresh_token for the Calendar API's
"https://www.googleapis.com/auth/calendar.readonly" scope:
    {"client_id": "...", "client_secret": "...", "refresh_token": "..."}

This is deliberately its own token file, not assumed to already exist
somewhere - point it at wherever your real Calendar OAuth credentials
actually live once you've set them up for this scope.

Writes to the "calendar" entry's items_file in sources.json (default
~/.cache/newsdigest/calendar.jsonl - override via
NEWSDIGEST_CALENDAR_ITEMS_FILE). Re-emits the same items on every run by
design (a "what's coming up" list, not a discrete-events log) - feed.py
callers should treat this source as a refreshed snapshot; a simple
approach is to have sources.json point every source at its own file and
just let old calendar items age out of relevance rather than trying to
dedupe against previous runs.
"""
import json
import os
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

CONFIG_FILE = Path(
    os.environ.get("NEWSDIGEST_CALENDAR_ACTIVITY_CONFIG", str(Path.home() / ".config" / "newsdigest" / "calendar-activity.json")),
)
ITEMS_FILE = Path(os.environ.get("NEWSDIGEST_CALENDAR_ITEMS_FILE", str(Path.home() / ".cache" / "newsdigest" / "calendar.jsonl")))
TOKEN_URL = "https://oauth2.googleapis.com/token"
EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/{cal}/events"


def _load_config() -> dict | None:
    if not CONFIG_FILE.exists():
        return None
    try:
        return json.loads(CONFIG_FILE.read_text())
    except Exception:
        return None


def _post_form(url: str, data: dict) -> dict:
    body = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in data.items())
    req = urllib.request.Request(url, data=body.encode(), method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())


def _access_token(token_file: Path) -> str | None:
    try:
        creds = json.loads(token_file.read_text())
    except Exception:
        return None
    try:
        resp = _post_form(TOKEN_URL, {
            "client_id": creds["client_id"],
            "client_secret": creds["client_secret"],
            "refresh_token": creds["refresh_token"],
            "grant_type": "refresh_token",
        })
        return resp.get("access_token")
    except Exception:
        return None


def _fetch_events(access_token: str, calendar_id: str, days_ahead: int) -> list[dict]:
    now = datetime.now(timezone.utc)
    params = {
        "timeMin": now.isoformat(),
        "timeMax": (now + timedelta(days=days_ahead)).isoformat(),
        "singleEvents": "true",
        "orderBy": "startTime",
        "maxResults": "20",
    }
    url = EVENTS_URL.format(cal=urllib.parse.quote(calendar_id)) + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"Bearer {access_token}")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read()).get("items", [])


def main():
    config = _load_config()
    if not config or not config.get("token_file"):
        print("calendar_activity: no config/token_file set up, nothing to do")
        return

    token_file = Path(config["token_file"]).expanduser()
    access_token = _access_token(token_file)
    if not access_token:
        print("calendar_activity: could not get an access token, skipping this run")
        return

    calendar_id = config.get("calendar_id", "primary")
    days_ahead = config.get("days_ahead", 2)
    events = _fetch_events(access_token, calendar_id, days_ahead)

    items = []
    for e in events:
        start = e.get("start", {}).get("dateTime") or e.get("start", {}).get("date", "")
        items.append({
            "id": f"calendar:{e.get('id')}",
            "title": e.get("summary", "(no title)"),
            "summary": e.get("location") or e.get("description", "")[:200],
            "link": e.get("htmlLink"),
            "feed_title": "calendar",
            "tags": ["calendar"],
            "published": start,
        })

    # Snapshot semantics (see module docstring): replace, don't append.
    ITEMS_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(ITEMS_FILE, "w") as f:
        for item in items:
            f.write(json.dumps(item) + "\n")
    print(f"calendar_activity: {len(items)} upcoming event(s)")


if __name__ == "__main__":
    main()
