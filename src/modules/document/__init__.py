"""
文档索引模块
"""

from __future__ import annotations

import hashlib
import os
import re
import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Protocol, Set

from src.domain.contracts.tracing import LoggerProtocol
from src.domain.models.common import DocumentMetadata


@dataclass
class IndexedDocument:
    metadata: DocumentMetadata
    content: str = ""
    tokens: List[str] = field(default_factory=list)


class SimpleInvertedIndex:
    def __init__(self) -> None:
        self._index: Dict[str, List[str]] = {}
        self._docs: Dict[str, IndexedDocument] = {}

    def _tokenize(self, text: str) -> List[str]:
        tokens = re.findall(r"\b[a-z0-9\u4e00-\u9fa5]+\b", text.lower())
        return [t for t in tokens if len(t) > 1]

    def index(self, doc: IndexedDocument) -> None:
        self._docs[doc.metadata.id] = doc
        all_text = f"{doc.metadata.title} {doc.content} {' '.join(doc.metadata.tags)}"
        tokens = self._tokenize(all_text)
        doc.tokens = tokens
        for token in set(tokens):
            if token not in self._index:
                self._index[token] = []
            if doc.metadata.id not in self._index[token]:
                self._index[token].append(doc.metadata.id)

    def search(self, query: str, **filters: Any) -> List[Dict[str, Any]]:
        query_tokens = self._tokenize(query)
        if not query_tokens:
            return []
        doc_scores: Dict[str, float] = defaultdict(float)
        for token in query_tokens:
            if token in self._index:
                for doc_id in self._index[token]:
                    doc = self._docs.get(doc_id)
                    if doc:
                        score = doc.tokens.count(token) / len(doc.tokens) if doc.tokens else 0
                        doc_scores[doc_id] += score
        results = []
        for doc_id, score in sorted(doc_scores.items(), key=lambda x: -x[1]):
            doc = self._docs[doc_id]
            match = True
            for key, value in filters.items():
                if hasattr(doc.metadata, key):
                    if getattr(doc.metadata, key) != value:
                        match = False
            if match:
                results.append({
                    "doc_id": doc_id, "title": doc.metadata.title,
                    "source": doc.metadata.source, "url": doc.metadata.url,
                    "score": score, "tags": doc.metadata.tags,
                    "snippet": doc.content[:200] + "..." if len(doc.content) > 200 else doc.content,
                })
        return results


class DocumentIndex:
    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._backend = SimpleInvertedIndex()
        self._logger = logger

    async def crawl_and_index(self, source_path: str, allowed_extensions: Optional[List[str]] = None) -> int:
        exts = allowed_extensions or [".md", ".txt", ".rst"]
        count = 0
        if not os.path.exists(source_path):
            return 0
        for root, _, files in os.walk(source_path):
            for filename in files:
                _, ext = os.path.splitext(filename)
                if ext.lower() not in exts:
                    continue
                file_path = os.path.join(root, filename)
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        content = f.read()
                    doc = IndexedDocument(
                        metadata=DocumentMetadata(
                            title=os.path.splitext(filename)[0],
                            source="filesystem", type=ext.lstrip("."), url=file_path,
                            content_hash=hashlib.md5(content.encode()).hexdigest(),
                        ),
                        content=content,
                    )
                    self._backend.index(doc)
                    count += 1
                except Exception:
                    continue
        return count

    def search(self, query: str, **filters: Any) -> List[Dict[str, Any]]:
        return self._backend.search(query, **filters)
