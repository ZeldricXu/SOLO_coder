"""
内部文档索引实现
核心功能：
1. 多源文档爬取与聚合
2. 全文搜索
3. 权限过滤
"""

from __future__ import annotations

import hashlib
import os
import re
import time
from dataclasses import dataclass, field
from collections import defaultdict
from typing import Any, Dict, List, Optional, Protocol, Set

from src.core import DocumentMetadata, LoggerProtocol


@dataclass
class IndexedDocument:
    metadata: DocumentMetadata
    content: str = ""
    tokens: List[str] = field(default_factory=list)


class SearchBackend(Protocol):
    """搜索后端协议"""

    def index(self, doc: IndexedDocument) -> None: ...

    def search(self, query: str, **filters: Any) -> List[Dict[str, Any]]: ...


class SimpleInvertedIndex(SearchBackend):
    """简单的倒排索引实现"""

    def __init__(self) -> None:
        self._index: Dict[str, List[str]] = {}
        self._docs: Dict[str, IndexedDocument] = {}

    def _tokenize(self, text: str) -> List[str]:
        text = text.lower()
        tokens = re.findall(r"\b[a-z0-9\u4e00-\u9fa5]+\b", text)
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
                        break
                elif key in doc.metadata.tags:
                    if value not in doc.metadata.tags:
                        match = False
                        break

            if match:
                results.append(
                    {
                        "doc_id": doc_id,
                        "title": doc.metadata.title,
                        "source": doc.metadata.source,
                        "url": doc.metadata.url,
                        "type": doc.metadata.type,
                        "score": score,
                        "tags": doc.metadata.tags,
                        "snippet": doc.content[:200] + "..." if len(doc.content) > 200 else doc.content,
                    }
                )

        return results


class PermissionFilter:
    """权限过滤器"""

    def __init__(self) -> None:
        self._user_permissions: Dict[str, Set[str]] = {}
        self._group_permissions: Dict[str, Set[str]] = {}
        self._user_groups: Dict[str, Set[str]] = {}

    def add_user_permission(self, user_id: str, permission: str) -> None:
        if user_id not in self._user_permissions:
            self._user_permissions[user_id] = set()
        self._user_permissions[user_id].add(permission)

    def add_group_permission(self, group_id: str, permission: str) -> None:
        if group_id not in self._group_permissions:
            self._group_permissions[group_id] = set()
        self._group_permissions[group_id].add(permission)

    def add_user_to_group(self, user_id: str, group_id: str) -> None:
        if user_id not in self._user_groups:
            self._user_groups[user_id] = set()
        self._user_groups[user_id].add(group_id)

    def get_user_permissions(self, user_id: str) -> Set[str]:
        permissions = set(self._user_permissions.get(user_id, []))
        for group_id in self._user_groups.get(user_id, []):
            permissions.update(self._group_permissions.get(group_id, []))
        return permissions

    def can_access(self, user_id: str, doc_permissions: List[str]) -> bool:
        if not doc_permissions:
            return True

        user_perms = self.get_user_permissions(user_id)
        return any(perm in user_perms for perm in doc_permissions)

    def filter_documents(
        self,
        user_id: str,
        documents: List[DocumentMetadata],
    ) -> List[DocumentMetadata]:
        return [doc for doc in documents if self.can_access(user_id, doc.permissions)]


class DocumentCrawler:
    """文档爬虫 - 从多源爬取文档"""

    def __init__(
        self,
        sources: Optional[Dict[str, Dict[str, Any]]] = None,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._sources = sources or {}
        self._logger = logger

    def add_source(self, name: str, config: Dict[str, Any]) -> None:
        self._sources[name] = config

    def _crawl_file_system(self, config: Dict[str, Any]) -> List[IndexedDocument]:
        """从本地文件系统爬取"""
        base_path = config.get("path", ".")
        allowed_extensions = config.get("extensions", [".md", ".txt", ".rst"])
        documents = []

        if not os.path.exists(base_path):
            return documents

        for root, _, files in os.walk(base_path):
            for filename in files:
                _, ext = os.path.splitext(filename)
                if ext.lower() not in allowed_extensions:
                    continue

                file_path = os.path.join(root, filename)
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        content = f.read()

                    content_hash = hashlib.md5(content.encode()).hexdigest()
                    rel_path = os.path.relpath(file_path, base_path)

                    doc = IndexedDocument(
                        metadata=DocumentMetadata(
                            title=os.path.splitext(filename)[0],
                            source="filesystem",
                            type=ext.lstrip("."),
                            url=file_path,
                            content_hash=content_hash,
                            permissions=config.get("permissions", []),
                            tags=config.get("tags", []),
                        ),
                        content=content,
                    )
                    documents.append(doc)
                except Exception as e:
                    if self._logger:
                        self._logger.warning(
                            "Failed to crawl file",
                            file=file_path,
                            error=str(e),
                        )

        return documents

    def _crawl_web(self, config: Dict[str, Any]) -> List[IndexedDocument]:
        """从网页爬取（占位实现）"""
        base_url = config.get("url", "")
        documents = []

        try:
            doc = IndexedDocument(
                metadata=DocumentMetadata(
                    title=f"Web: {base_url}",
                    source="web",
                    type="html",
                    url=base_url,
                    content_hash=hashlib.md5(base_url.encode()).hexdigest(),
                    permissions=config.get("permissions", []),
                    tags=config.get("tags", []),
                ),
                content=f"Content from {base_url}",
            )
            documents.append(doc)
        except Exception as e:
            if self._logger:
                self._logger.warning(
                    "Failed to crawl web",
                    url=base_url,
                    error=str(e),
                )

        return documents

    def crawl(self, source_name: Optional[str] = None) -> List[IndexedDocument]:
        """爬取指定或所有源的文档"""
        sources = [source_name] if source_name else list(self._sources.keys())
        all_docs = []

        for name in sources:
            config = self._sources.get(name)
            if not config:
                continue

            source_type = config.get("type", "filesystem")
            docs = []

            if source_type == "filesystem":
                docs = self._crawl_file_system(config)
            elif source_type == "web":
                docs = self._crawl_web(config)

            if self._logger:
                self._logger.info(
                    "Crawled documents",
                    source=name,
                    count=len(docs),
                )

            all_docs.extend(docs)

        return all_docs


class SearchEngine:
    """搜索引擎"""

    def __init__(
        self,
        backend: Optional[SearchBackend] = None,
        permission_filter: Optional[PermissionFilter] = None,
    ) -> None:
        self._backend = backend or SimpleInvertedIndex()
        self._permission_filter = permission_filter or PermissionFilter()

    def index_document(self, doc: IndexedDocument) -> None:
        self._backend.index(doc)

    def search(
        self,
        query: str,
        user_id: Optional[str] = None,
        **filters: Any,
    ) -> List[Dict[str, Any]]:
        results = self._backend.search(query, **filters)

        if user_id and self._permission_filter:
            filtered = []
            for result in results:
                doc_perms = result.get("permissions", [])
                if self._permission_filter.can_access(user_id, doc_perms):
                    filtered.append(result)
            return filtered

        return results


class DocumentIndex:
    """
    文档索引 - 核心类
    整合爬虫、索引、搜索、权限过滤
    """

    def __init__(
        self,
        crawler: Optional[DocumentCrawler] = None,
        search_engine: Optional[SearchEngine] = None,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._crawler = crawler or DocumentCrawler(logger=logger)
        self._search_engine = search_engine or SearchEngine()
        self._logger = logger
        self._last_crawl_time: Optional[float] = None

    def add_source(self, name: str, config: Dict[str, Any]) -> None:
        self._crawler.add_source(name, config)

    async def crawl_and_index(self, source_name: Optional[str] = None) -> int:
        """爬取并索引文档"""
        documents = self._crawler.crawl(source_name)

        for doc in documents:
            self._search_engine.index_document(doc)

        self._last_crawl_time = time.time()

        if self._logger:
            self._logger.info(
                "Crawl and index completed",
                documents_count=len(documents),
                source=source_name or "all",
            )

        return len(documents)

    def search(
        self,
        query: str,
        user_id: Optional[str] = None,
        doc_type: Optional[str] = None,
        source: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> List[Dict[str, Any]]:
        """搜索文档"""
        filters = {}
        if doc_type:
            filters["type"] = doc_type
        if source:
            filters["source"] = source

        results = self._search_engine.search(query, user_id, **filters)

        if tags:
            results = [
                r for r in results
                if all(tag in r.get("tags", []) for tag in tags)
            ]

        return results

    def get_permission_filter(self) -> PermissionFilter:
        return self._search_engine._permission_filter

    def get_last_crawl_time(self) -> Optional[float]:
        return self._last_crawl_time
