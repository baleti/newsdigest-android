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
    ===OVERVIEW===
    <short list of [label](#jump:<verbatim phrase from the body below>) links>
    ===BODY===
    <markdown for this topic>
    ===TOPIC: Job Search===
    ===CATEGORY: Career===
    ===OVERVIEW===
    ...
    ===BODY===
    <markdown for this topic>

CATEGORY is optional (a run from an older format, or one that skipped
it, still parses - it just comes back empty) - topic is the specific,
today-only title; category is the coarse at-a-glance tag the app badges
each entry with. The OVERVIEW/BODY pair is likewise optional as a unit -
without both markers present, the whole section is just body with an
empty overview, same graceful degrade as CATEGORY. See generate-digest.sh's
prompt for what the app expects inside an overview's jump-links: each
phrase must be an exact, verbatim substring of the body text that
follows, since the app locates it there with a plain string search (no
fuzzy matching) to scroll to and briefly highlight.

Writes ~/.cache/newsdigest-server/digest/<date>.json (and updates
latest.json) as {"date": ..., "digests": [{"topic", "category",
"overview", "markdown", "references"}, ...]}, then applies a retention
curve: daily for 2 weeks, thinned to weekly through day 44, dropped
after that.

Generic and reusable across whatever sources are actually enabled -
builds its link index from feed.py's already-merged items (respecting
sources.json), and doesn't otherwise know or care which sources
contributed what, or how many topics there end up being.
"""
import json
import re
import sys
from datetime import date, datetime
from pathlib import Path

import feed

LINK_RE = re.compile(r"\[([^\]]+)\]\((https?://[^)]+)\)")
TOPIC_RE = re.compile(r"^===TOPIC:\s*(.+?)\s*===\s*$", re.MULTILINE)
CATEGORY_RE = re.compile(r"\A===CATEGORY:\s*(.+?)\s*===\s*\n?")
# Optional overview block, see generate-digest.sh's prompt: a short list of
# jump-links (#jump:<verbatim phrase from the body>) the app resolves by
# plain substring search against the body text and uses to scroll/
# highlight there. A generator run from before this existed, or one that
# skipped it, has neither marker - OVERVIEW_RE just won't match, and the
# whole thing falls back to an empty overview with the entire remainder
# as body, same "missing marker degrades gracefully" pattern as CATEGORY.
OVERVIEW_RE = re.compile(r"\A===OVERVIEW===\s*\n?")
BODY_RE = re.compile(r"===BODY===\s*\n?")

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


def parse_topics(raw: str) -> list[tuple[str, str, str, str]]:
    """Splits on ===TOPIC: name=== markers into (topic, category, overview,
    body) quadruples, pulling an optional immediately-following
    ===CATEGORY: ...=== line and an optional ===OVERVIEW=== ... ===BODY===
    pair out of each section. A generator that ignored the marker format
    entirely (no TOPIC markers found) still produces one nameless topic
    rather than losing the output; one that skipped CATEGORY or the
    overview pair just gets an empty string for those, not a parse
    failure."""
    matches = list(TOPIC_RE.finditer(raw))
    if not matches:
        stripped = raw.strip()
        return [("Today", "", "", stripped)] if stripped else []

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

        overview = ""
        ov_match = OVERVIEW_RE.match(body)
        if ov_match:
            rest = body[ov_match.end():]
            body_match = BODY_RE.search(rest)
            if body_match:
                overview = rest[:body_match.start()].strip()
                body = rest[body_match.end():]
            # else: an ===OVERVIEW=== with no matching ===BODY=== is
            # treated as if neither marker were there - `body` is
            # untouched, so the raw markers stay visible rather than
            # silently eating the whole section as "overview".

        body = body.strip()
        if body:
            sections.append((topic, category, overview, body))
    return sections


def rotate(digest_dir: Path):
    today = date.today()
    for f in digest_dir.glob("*.json"):
        if f.stem == "latest":
            continue
        try:
            # stem is now "YYYY-MM-DD" or "YYYY-MM-DD-<slot>" (see run_id
            # in main()) - the date is always the first 10 characters.
            file_date = date.fromisoformat(f.stem[:10])
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
            "overview": overview,
            "markdown": markdown,
            "references": extract_references(markdown, link_index),
        }
        for topic, category, overview, markdown in topics
    ]
    now = datetime.now()
    today_str = now.date().isoformat()
    # Distinguishes the 07:00 and 17:00 runs of the same day (see
    # newsdigest-digest.timer) so a chat conversation tied to a topic from
    # the morning run isn't silently orphaned when the evening run
    # overwrites latest.json with a fresh set of topics - feed.py persists
    # chat sessions against this run_id's own dated file, not "latest".
    # "date" itself stays a plain YYYY-MM-DD for on-screen display.
    slot = "07" if now.hour < 12 else "17"
    run_id = f"{today_str}-{slot}"
    payload = {"date": today_str, "run_id": run_id, "digests": digests}

    dated_path = feed.DIGEST_DIR / f"{run_id}.json"
    dated_path.write_text(json.dumps(payload))
    (feed.DIGEST_DIR / "latest.json").write_text(json.dumps(payload))
    rotate(feed.DIGEST_DIR)
    total_refs = sum(len(d["references"]) for d in digests)
    print(f"build_digest_json: wrote {dated_path} ({len(digests)} topics, {total_refs} references)")


if __name__ == "__main__":
    main()
