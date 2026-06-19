import re
import time
from typing import Optional, List, Dict, Any

import jieba
from PyQt6.QtGui import QTextCharFormat, QColor, QTextCursor, QTextDocument

from app.database import Database


def preprocess_query(query: str) -> str:
    if not query or not query.strip():
        return ""
    tokens = []
    segments = re.split(r'(\s+)', query)
    for seg in segments:
        if not seg.strip():
            continue
        if re.search(r'[\u4e00-\u9fff]', seg):
            words = jieba.lcut(seg)
            tokens.extend([w.strip() for w in words if w.strip()])
        else:
            tokens.append(seg.strip())
    return " ".join(tokens)


def _days_since(timestamp: int) -> float:
    return (time.time() - timestamp) / 86400.0


def _recency_score(updated_at: int) -> float:
    days = _days_since(updated_at)
    return 1.0 / (1.0 + days / 30.0)


def _normalize_bm25(results: List[Dict]) -> None:
    if not results:
        return
    ranks = [r.get("rank", 0.0) for r in results]
    min_rank = min(ranks)
    max_rank = max(ranks)
    if max_rank == min_rank:
        for r in results:
            r["bm25_norm"] = 1.0
        return
    for r in results:
        r["bm25_norm"] = 1.0 - (r["rank"] - min_rank) / (max_rank - min_rank)


class SearchEngine:
    def __init__(self, db: Database):
        self.db = db

    def search(self, query: str,
               tag_ids: Optional[List[int]] = None,
               folder_id: Optional[int] = None,
               date_from: Optional[int] = None,
               date_to: Optional[int] = None,
               limit: int = 50) -> List[Dict]:
        fts_query = preprocess_query(query)
        if not fts_query:
            return []

        raw_results = self.db.fts_search(fts_query, limit=limit * 3)
        if not raw_results:
            return []

        filtered = []
        for r in raw_results:
            if folder_id is not None and r.get("folder_id") != folder_id:
                continue
            if date_from is not None and r.get("updated_at", 0) < date_from:
                continue
            if date_to is not None and r.get("updated_at", 0) > date_to:
                continue
            if tag_ids:
                note_tags = self.db.get_note_tags(r["id"])
                note_tag_ids = [t["id"] for t in note_tags]
                if not all(tid in note_tag_ids for tid in tag_ids):
                    continue
            filtered.append(r)

        _normalize_bm25(filtered)

        for r in filtered:
            bm25 = r.get("bm25_norm", 0.0)
            recency = _recency_score(r.get("updated_at", 0))
            r["score"] = bm25 * 0.6 + recency * 0.4

        filtered.sort(key=lambda x: x["score"], reverse=True)
        return filtered[:limit]

    def get_highlight_format(self) -> QTextCharFormat:
        fmt = QTextCharFormat()
        fmt.setBackground(QColor("#FFFF00"))
        return fmt

    def highlight_in_document(self, doc: QTextDocument, query: str) -> None:
        if not query or not query.strip():
            return
        fmt = self.get_highlight_format()
        tokens = self._extract_highlight_tokens(query)
        if not tokens:
            return
        cursor = QTextCursor(doc)
        cursor.beginEditBlock()
        cursor.movePosition(QTextCursor.MoveOperation.Start)
        while not cursor.isNull():
            cursor = doc.find("|".join(re.escape(t) for t in tokens), cursor)
            if not cursor.isNull():
                cursor.mergeCharFormat(fmt)
        cursor.endEditBlock()

    def _extract_highlight_tokens(self, query: str) -> List[str]:
        if not query or not query.strip():
            return []
        tokens = []
        segments = re.split(r'(\s+)', query)
        for seg in segments:
            if not seg.strip():
                continue
            if re.search(r'[\u4e00-\u9fff]', seg):
                words = jieba.lcut(seg)
                tokens.extend([w.strip() for w in words if w.strip() and len(w.strip()) > 0])
            else:
                tokens.append(seg.strip())
        return list(dict.fromkeys(tokens))

    def highlight_text(self, text: str, query: str, max_length: int = 200) -> str:
        if not text:
            return ""
        tokens = self._extract_highlight_tokens(query)
        if not tokens:
            if len(text) > max_length:
                return text[:max_length] + "..."
            return text

        lower_text = text.lower()
        first_pos = len(text)
        for token in tokens:
            pos = lower_text.find(token.lower())
            if pos != -1 and pos < first_pos:
                first_pos = pos

        start = max(0, first_pos - 40)
        end = min(len(text), start + max_length)
        snippet = text[start:end]
        if start > 0:
            snippet = "..." + snippet
        if end < len(text):
            snippet = snippet + "..."

        for token in sorted(tokens, key=len, reverse=True):
            snippet = re.sub(
                r'(' + re.escape(token) + r')',
                r'<mark>\1</mark>',
                snippet,
                flags=re.IGNORECASE
            )
        return snippet
