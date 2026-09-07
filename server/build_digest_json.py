#!/usr/bin/env python3
"""
Turns a raw markdown digest (from a digest-generation script's LLM call -
see README's "Digest generation") into the structured form the app
consumes: the markdown itself, plus a "references" list (url, label,
source feed title) extracted from every [label](url) link in it - so the
app can render an "open article" button next to each mention without
re-scraping anything, since every link the LLM writes should be drawn
from an already-enabled source's own archive in the first place.

Writes ~/.cache/newsdigest-server/digest/<date>.json and updates
latest.json, then applies a retention curve: daily for 2 weeks, thinned
to weekly through day 44, dropped after that.

Generic and reusable across whatever sources are actually enabled -
takes the finished markdown on stdin, builds its link index from
feed.py's already-merged items (respecting sources.json), and doesn't
otherwise know or care which sources contributed what.
"""
import json
import re
import sys
from datetime import date
from pathlib import Path

import feed

LINK_RE = re.compile(r"\[([^\]]+)\]\((https?://[^)]+)\)")

DAILY_TIER_DAYS = 14
WEEKLY_TIER_DAYS = 44


def load_link_index() -> dict:
    """url -> {feed_title}, from every currently-enabled source."""
    index = {}
    for item in feed._load_items():
        link = item.get("link")
        if not link:
            continue
        index[link] = {"feed_title": item.get("feed_title", "")}
    return index


def extract_references(markdown: str, link_index: dict) -> list:
    seen = set()
    refs = []
    for label, url in LINK_RE.findall(markdown):
        if url in seen:
            continue
        seen.add(url)
        info = link_index.get(url, {})
        refs.append({"label": label, "url": url, "feed_title": info.get("feed_title", "")})
    return refs


def rotate(digest_dir: Path):
    today = date.today()
    for f in digest_dir.glob("*.json"):
        if f.stem == "latest":
            continue
        try:
            file_date = date.fromisoformat(f.stem)
        except ValueError:
            continue
        age = (today - file_date).days
        if age <= DAILY_TIER_DAYS:
            continue
        if age <= WEEKLY_TIER_DAYS:
            # keep exactly one per age-week bucket; since this runs once a
            # day, "keep the first one seen in a bucket" is equivalent to
            # "keep the newest", same rule the earlier phone-side version used.
            continue
        f.unlink(missing_ok=True)


def main():
    markdown = sys.stdin.read()
    if not markdown.strip():
        print("build_digest_json: empty input, not writing", file=sys.stderr)
        sys.exit(1)

    feed.DIGEST_DIR.mkdir(parents=True, exist_ok=True)
    link_index = load_link_index()
    references = extract_references(markdown, link_index)
    today_str = date.today().isoformat()
    payload = {"date": today_str, "markdown": markdown, "references": references}

    dated_path = feed.DIGEST_DIR / f"{today_str}.json"
    dated_path.write_text(json.dumps(payload))
    (feed.DIGEST_DIR / "latest.json").write_text(json.dumps(payload))
    rotate(feed.DIGEST_DIR)
    print(f"build_digest_json: wrote {dated_path} ({len(references)} references)")


if __name__ == "__main__":
    main()
