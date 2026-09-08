#!/usr/bin/env python3
"""
Turns a raw multi-topic digest (from a digest-generation script's LLM
call - see README's "Digest generation") into the structured form the
app consumes: one entry per topic the generator actually found that day
- however many that turns out to be, about whatever it turns out to be
about, never a fixed count or fixed set of topics - each with its own
markdown and a "references" list (url, label, source feed title)
extracted from every [label](url) link in it, so the app can render an
"open article" button next to each mention without re-scraping anything.

Expects the LLM's raw output on stdin split into topic sections marked
like:
    ===TOPIC: Programming & Your Tools===
    ===CATEGORY: Programming===
    <markdown for this topic>
    ===TOPIC: Job Search===
    ===CATEGORY: Career===
    <markdown for this topic>

The CATEGORY line is optional (a run that produced it in an older format,
or skipped it, still parses - it just comes back empty) - topic is the
specific, today-only title; category is the coarse at-a-glance tag the
app badges each entry with.

Writes ~/.cache/newsdigest-server/digest/<date>.json (and updates
latest.json) as {"date": ..., "digests": [{"topic", "category",
"markdown", "references"}, ...]}, then applies a retention curve: daily
for 2 weeks, thinned to weekly through day 44, dropped after that.

Generic and reusable across whatever sources are actually enabled -
builds its link index from feed.py's already-merged items (respecting
sources.json), and doesn't otherwise know or care which sources
contributed what, or how many topics there end up being.
"""
import json
import re
import sys
from datetime import date
from pathlib import Path

import feed

LINK_RE = re.compile(r"\[([^\]]+)\]\((https?://[^)]+)\)")
TOPIC_RE = re.compile(r"^===TOPIC:\s*(.+?)\s*===\s*$", re.MULTILINE)
CATEGORY_RE = re.compile(r"\A===CATEGORY:\s*(.+?)\s*===\s*\n?")

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


def parse_topics(raw: str) -> list[tuple[str, str, str]]:
    """Splits on ===TOPIC: name=== markers into (topic, category, markdown)
    triples, pulling an optional immediately-following ===CATEGORY: ...===
    line out of each body. A generator that ignored the marker format
    entirely (no TOPIC markers found) still produces one nameless topic
    rather than losing the output; one that skipped the CATEGORY line
    just gets an empty category, not a parse failure."""
    matches = list(TOPIC_RE.finditer(raw))
    if not matches:
        stripped = raw.strip()
        return [("Today", "", stripped)] if stripped else []

    sections = []
    for i, m in enumerate(matches):
        topic = m.group(1).strip()
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(raw)
        body = raw[start:end].lstrip("\n")

        category = ""
        cat_match = CATEGORY_RE.match(body)
        if cat_match:
            category = cat_match.group(1).strip()
            body = body[cat_match.end():]

        body = body.strip()
        if body:
            sections.append((topic, category, body))
    return sections


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
    raw = sys.stdin.read()
    if not raw.strip():
        print("build_digest_json: empty input, not writing", file=sys.stderr)
        sys.exit(1)

    topics = parse_topics(raw)
    if not topics:
        print("build_digest_json: no topic sections found in input, not writing", file=sys.stderr)
        sys.exit(1)

    feed.DIGEST_DIR.mkdir(parents=True, exist_ok=True)
    link_index = load_link_index()
    digests = [
        {
            "topic": topic,
            "category": category,
            "markdown": markdown,
            "references": extract_references(markdown, link_index),
        }
        for topic, category, markdown in topics
    ]
    today_str = date.today().isoformat()
    payload = {"date": today_str, "digests": digests}

    dated_path = feed.DIGEST_DIR / f"{today_str}.json"
    dated_path.write_text(json.dumps(payload))
    (feed.DIGEST_DIR / "latest.json").write_text(json.dumps(payload))
    rotate(feed.DIGEST_DIR)
    total_refs = sum(len(d["references"]) for d in digests)
    print(f"build_digest_json: wrote {dated_path} ({len(digests)} topics, {total_refs} references)")


if __name__ == "__main__":
    main()
