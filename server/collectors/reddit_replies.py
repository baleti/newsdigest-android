#!/usr/bin/env python3
"""
Emits digest items for replies other Reddit users left on the bot's comments.

reddit.com blocks anonymous JSON (403) and rate-limits RSS, so this reads the
logged-in inbox of the bot's dedicated Chromium profile over CDP (the same
instance reddit-architecture-bot/run.sh uses, port 9223). Read-only: it
navigates one tab to /message/inbox.json, reads the page text and closes the
tab. If Chromium isn't up it exits quietly rather than launching it - the bot's
daily run brings it up and leaves it running.

Must run with the CDP driver's venv python (needs `websocket-client`).
Output goes to the "reddit_replies" source's items_file (default
~/.cache/newsdigest/reddit_replies.jsonl); seen inbox ids are tracked in
~/.cache/newsdigest/cursors/reddit-replies-seen.json.
"""
import json
import os
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import websocket

from _jsonl_writer import append_items

CDP_HTTP = f"http://127.0.0.1:{os.environ.get('CDP_PORT', '9223')}"
ITEMS_FILE = Path(os.environ.get("NEWSDIGEST_REDDIT_REPLIES_ITEMS_FILE", str(Path.home() / ".cache" / "newsdigest" / "reddit_replies.jsonl")))
SEEN_FILE = Path.home() / ".cache" / "newsdigest" / "cursors" / "reddit-replies-seen.json"
INBOX_URL = "https://www.reddit.com/message/inbox.json?limit=100&raw_json=1"


def _http(path, method="GET"):
    req = urllib.request.Request(CDP_HTTP + path, method=method)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def _fetch_inbox() -> dict | None:
    page = _http("/json/new?about:blank", "PUT")
    ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=20)
    try:
        def call(msg_id, method, params=None):
            ws.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
            while True:
                resp = json.loads(ws.recv())
                if resp.get("id") == msg_id:
                    return resp
        call(1, "Page.enable")
        call(2, "Page.navigate", {"url": INBOX_URL})
        for _ in range(20):
            time.sleep(1.5)
            r = call(3, "Runtime.evaluate", {"expression": "document.readyState + '|' + (document.body ? document.body.innerText.slice(0, 1) : '')", "returnByValue": True})
            if r.get("result", {}).get("result", {}).get("value", "").startswith("complete|{"):
                break
        r = call(4, "Runtime.evaluate", {"expression": "document.body.innerText", "returnByValue": True})
        text = r["result"]["result"]["value"]
        return json.loads(text)
    except Exception as e:
        print(f"reddit_replies: could not read inbox ({e})")
        return None
    finally:
        ws.close()
        try:
            urllib.request.urlopen(CDP_HTTP + "/json/close/" + page["id"], timeout=5).read()
        except Exception:
            pass


def main():
    try:
        _http("/json/version")
    except Exception:
        print("reddit_replies: chromium not running on CDP port, nothing to do")
        return
    data = _fetch_inbox()
    if not data:
        return
    try:
        seen = set(json.loads(SEEN_FILE.read_text()))
    except Exception:
        seen = None  # first run: only surface the last 14 days, not the whole inbox history

    items = []
    ids = set(seen or [])
    for child in data.get("data", {}).get("children", []):
        d = child.get("data", {})
        name = d.get("name")
        if not name or name in ids or d.get("was_comment") is not True:
            continue
        ids.add(name)
        if seen is None and d.get("created_utc", 0) < time.time() - 14 * 86400:
            continue
        author = d.get("author") or "[deleted]"
        subreddit = d.get("subreddit") or ""
        link = "https://www.reddit.com" + (d.get("context") or "")
        kind = "mentioned you" if d.get("type") == "username_mention" else "replied to our comment"
        items.append({
            "id": f"reddit-reply:{name}",
            "title": f"u/{author} {kind} in r/{subreddit}: {d.get('link_title', '')}",
            "summary": (d.get("body") or "").strip(),
            "link": link,
            "feed_title": "reddit replies",
            "tags": ["reddit", "reddit-replies", subreddit],
            "published": datetime.fromtimestamp(d.get("created_utc", time.time()), timezone.utc).isoformat(),
        })

    append_items(ITEMS_FILE, items)
    SEEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    SEEN_FILE.write_text(json.dumps(sorted(ids)))
    print(f"reddit_replies: {len(items)} new reply(ies)")


if __name__ == "__main__":
    main()
