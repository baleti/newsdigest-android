"""
Read-only feed data for the RSS reader app: rssd's item archive, cached
favicons, and the daily digest built by rss-digest.sh / build-digest-json.py.
Kept separate from server.py's TTS/security plumbing so this module has no
FastAPI dependency of its own - server.py wires it into routes.
"""
import json
import re
from pathlib import Path

_HOST_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

ITEMS_FILE = Path.home() / ".cache" / "rssd" / "items.jsonl"
ICONS_DIR = Path.home() / ".cache" / "rssd" / "icons"
DIGEST_DIR = Path.home() / ".cache" / "tts-server" / "digest"

_EXT_TO_MIME = {
    ".png": "image/png",
    ".gif": "image/gif",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".ico": "image/x-icon",
}


def _load_items() -> list[dict]:
    items = []
    if not ITEMS_FILE.exists():
        return items
    with open(ITEMS_FILE, "r", errors="ignore") as f:
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


def latest_digest() -> dict | None:
    p = DIGEST_DIR / "latest.json"
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text())
    except Exception:
        return None
