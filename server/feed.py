"""
Read-only feed data for the News Digest app: merges whatever activity
sources a local, untracked config points it at (RSS, git activity, a
bot's own posts, calendar, email headers, ...) into one item list, plus
cached RSS favicons and the daily digest built by a local (also
untracked - see README's "Digest generation") generator script. Kept
separate from server.py's TTS/security plumbing so this module has no
FastAPI dependency of its own - server.py wires it into routes.

Every source is just a JSONL file in the shared item schema (see
README's "Sources" section) - this module doesn't know or care which
collector produced a given line, so adding a new source is purely a
config change, never a code change here.
"""
import json
import os
import re
import threading
from pathlib import Path

_HOST_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

# Back-compat default when no sources.json exists yet: just the one RSS
# archive this project originally read, so an existing deployment that
# hasn't set up the new config keeps working unchanged.
_DEFAULT_SOURCES = [{"name": "rss", "items_file": str(Path.home() / ".cache" / "rssd" / "items.jsonl")}]

SOURCES_CONFIG = Path(
    os.environ.get("NEWSDIGEST_SOURCES_CONFIG", str(Path.home() / ".config" / "newsdigest" / "sources.json")),
)
ICONS_DIR = Path.home() / ".cache" / "rssd" / "icons"
DIGEST_DIR = Path.home() / ".cache" / "newsdigest-server" / "digest"

_EXT_TO_MIME = {
    ".png": "image/png",
    ".gif": "image/gif",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".ico": "image/x-icon",
}


def _enabled_sources() -> list[dict]:
    if not SOURCES_CONFIG.exists():
        return _DEFAULT_SOURCES
    try:
        config = json.loads(SOURCES_CONFIG.read_text())
    except Exception:
        return _DEFAULT_SOURCES
    return [s for s in config.get("sources", []) if s.get("enabled", True)]


def _load_items() -> list[dict]:
    items = []
    for source in _enabled_sources():
        items_file = Path(source["items_file"]).expanduser()
        if not items_file.exists():
            continue
        with open(items_file, "r", errors="ignore") as f:
            for line in f:
                try:
                    items.append(json.loads(line))
                except Exception:
                    continue
    return items


def list_items(limit: int = 50) -> list[dict]:
    items = _load_items()
    items.sort(key=lambda it: it.get("published") or it.get("fetched_at") or "", reverse=True)
    out = []
    for it in items[:limit]:
        icon = it.get("icon") or ""
        out.append({
            "id": it.get("id"),
            "title": it.get("title"),
            "summary": it.get("summary"),
            "link": it.get("link"),
            "feed_title": it.get("feed_title"),
            "tags": it.get("tags", []),
            "published": it.get("published"),
            "favicon_host": Path(icon).stem if icon else None,
        })
    return out


def find_article(link: str) -> dict | None:
    for it in _load_items():
        if it.get("link") == link:
            return {
                "title": it.get("title"),
                "content_html": it.get("content_html", ""),
                "link": it.get("link"),
                "feed_title": it.get("feed_title"),
                "published": it.get("published"),
                "image": it.get("image") or None,
            }
    return None


def favicon_file(host: str) -> tuple[Path, str] | None:
    # host reaches here straight from a URL path segment - reject anything
    # that isn't hostname-shaped before it ever touches a filesystem path,
    # rather than trusting Path's "/" operator to not walk out of ICONS_DIR.
    if not _HOST_RE.match(host):
        return None
    for ext, mime in _EXT_TO_MIME.items():
        p = ICONS_DIR / f"{host}{ext}"
        if p.exists():
            return p, mime
    return None


_chat_session_lock = threading.Lock()


def _run_digest_path(run_id: str) -> Path | None:
    # run_id comes straight from a client-supplied query param - restrict
    # it to the exact shape build_digest_json.py produces before it ever
    # touches a filesystem path (no "latest", no traversal).
    if not re.match(r"^\d{4}-\d{2}-\d{2}-\d{2}$", run_id):
        return None
    return DIGEST_DIR / f"{run_id}.json"


def get_chat_session(run_id: str, topic: str) -> str | None:
    """The Claude Agents session id (a real tmux+claude conversation, see
    claude-agents-daemon.py) already spawned for this digest topic, if the
    app has chatted about it before - persisted into the run's own dated
    digest file (not latest.json, whose identity changes out from under a
    conversation the moment the next scheduled digest run overwrites it)."""
    p = _run_digest_path(run_id)
    if p is None or not p.exists():
        return None
    try:
        payload = json.loads(p.read_text())
    except Exception:
        return None
    for d in payload.get("digests", []):
        if d.get("topic") == topic:
            return d.get("chat_session_id")
    return None


def set_chat_session(run_id: str, topic: str, session_id: str) -> bool:
    p = _run_digest_path(run_id)
    if p is None or not p.exists():
        return False
    with _chat_session_lock:
        try:
            payload = json.loads(p.read_text())
        except Exception:
            return False
        found = False
        for d in payload.get("digests", []):
            if d.get("topic") == topic:
                d["chat_session_id"] = session_id
                found = True
                break
        if not found:
            return False
        p.write_text(json.dumps(payload))
        return True


def latest_digest() -> dict | None:
    """{"date": ..., "digests": [{"topic", "markdown", "references"}, ...]} -
    see build_digest_json.py for how the topic split is produced. However
    many digests there are, and whatever they're about, comes entirely
    from what the generator actually found in that day's sources - never
    a fixed count or fixed set of topics."""
    p = DIGEST_DIR / "latest.json"
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text())
    except Exception:
        return None
