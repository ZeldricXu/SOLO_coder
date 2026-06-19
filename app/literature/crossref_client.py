import re
from typing import List, Dict, Optional

import requests


CROSSREF_API_BASE = "https://api.crossref.org"
USER_AGENT = "KnowledgeVault/1.0 (mailto:dev@example.com)"
TIMEOUT = 10


def _extract_first(items, key):
    if not items:
        return None
    if isinstance(items, list):
        for item in items:
            if item:
                return item
        return None
    return items


def _parse_authors(author_list: List[Dict]) -> Optional[str]:
    if not author_list:
        return None
    names = []
    for a in author_list:
        given = a.get("given", "").strip()
        family = a.get("family", "").strip()
        if family:
            if given:
                names.append(f"{given} {family}")
            else:
                names.append(family)
    if not names:
        return None
    return ", ".join(names)


def _strip_html(text: str) -> str:
    if not text:
        return ""
    text = re.sub(r"<jats:[^>]+>", "", text)
    text = re.sub(r"</jats:[^>]+>", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    return text.strip()


def fetch_by_doi(doi: str) -> Optional[Dict]:
    if not doi:
        return None
    try:
        url = f"{CROSSREF_API_BASE}/works/{doi}"
        headers = {"User-Agent": USER_AGENT}
        resp = requests.get(url, headers=headers, timeout=TIMEOUT)
        if resp.status_code != 200:
            return None
        data = resp.json()
        msg = data.get("message", {})
        return _parse_work_message(msg)
    except (requests.RequestException, ValueError, KeyError):
        return None


def search_by_title(title: str, limit: int = 5) -> List[Dict]:
    if not title or not title.strip():
        return []
    try:
        url = f"{CROSSREF_API_BASE}/works"
        headers = {"User-Agent": USER_AGENT}
        params = {
            "query.title": title.strip(),
            "rows": max(1, min(limit, 100)),
        }
        resp = requests.get(url, headers=headers, params=params, timeout=TIMEOUT)
        if resp.status_code != 200:
            return []
        data = resp.json()
        items = data.get("message", {}).get("items", [])
        results = []
        for item in items:
            parsed = _parse_work_message(item)
            if parsed:
                results.append(parsed)
        return results
    except (requests.RequestException, ValueError, KeyError):
        return []


def _parse_work_message(msg: Dict) -> Optional[Dict]:
    if not msg:
        return None
    title_list = msg.get("title") or []
    title = _extract_first(title_list, "title")

    authors = _parse_authors(msg.get("author", []))

    abstract_raw = msg.get("abstract", "")
    abstract = _strip_html(abstract_raw) if abstract_raw else None

    journal_list = msg.get("container-title") or msg.get("short-container-title") or []
    journal = _extract_first(journal_list, "journal")

    year = None
    issued = msg.get("issued", {}) or msg.get("published-print", {}) or msg.get("published-online", {})
    date_parts = issued.get("date-parts", [[]])
    if date_parts and date_parts[0]:
        try:
            year = int(date_parts[0][0])
        except (ValueError, TypeError):
            year = None

    volume = msg.get("volume")
    issue = msg.get("issue")
    pages = msg.get("page")
    doi = msg.get("DOI")

    result = {
        "title": title,
        "authors": authors,
        "abstract": abstract or None,
        "journal": journal,
        "year": year,
        "volume": volume,
        "issue": issue,
        "pages": pages,
        "doi": doi,
    }
    return result
