from __future__ import annotations

import logging
import os
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

import aiofiles
import httpx

from src.common.exceptions import ValidationError
from src.common.utils import async_retry
from src.document_index.models import Document, DocumentSource

logger = logging.getLogger(__name__)


class DocumentSourceAdapter(ABC):
    @abstractmethod
    async def fetch(self, source_id: str) -> Document:
        ...

    @abstractmethod
    async def list(self, params: Optional[Dict[str, Any]] = None) -> List[Document]:
        ...

    @abstractmethod
    async def sync(self, last_sync_at: Optional[str] = None) -> List[Document]:
        ...


class LocalFileSource(DocumentSourceAdapter):
    def __init__(self, base_path: str = "./documents") -> None:
        self.base_path = base_path

    async def fetch(self, source_id: str) -> Document:
        file_path = os.path.join(self.base_path, source_id)
        if not os.path.exists(file_path):
            raise ValidationError(f"File not found: {source_id}")
        async with aiofiles.open(file_path, "r", encoding="utf-8") as f:
            content = await f.read()
        stat = os.stat(file_path)
        return Document(
            title=os.path.basename(source_id),
            content=content,
            source=DocumentSource.LOCAL_FILE,
            source_id=source_id,
            mime_type=self._get_mime_type(source_id),
            metadata={"size": stat.st_size, "path": file_path},
        )

    async def list(self, params: Optional[Dict[str, Any]] = None) -> List[Document]:
        documents: List[Document] = []
        for root, _, files in os.walk(self.base_path):
            for filename in files:
                if filename.endswith((".md", ".txt", ".rst")):
                    rel_path = os.path.relpath(os.path.join(root, filename), self.base_path)
                    documents.append(await self.fetch(rel_path))
        return documents

    async def sync(self, last_sync_at: Optional[str] = None) -> List[Document]:
        return await self.list()

    def _get_mime_type(self, filename: str) -> str:
        ext = os.path.splitext(filename)[1].lower()
        types = {".md": "text/markdown", ".txt": "text/plain", ".rst": "text/x-rst"}
        return types.get(ext, "text/plain")


class WebSource(DocumentSourceAdapter):
    def __init__(self, timeout: int = 30) -> None:
        self.timeout = timeout

    @async_retry(max_attempts=3)
    async def fetch(self, source_id: str) -> Document:
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.get(source_id)
            response.raise_for_status()
            content = response.text
            title = self._extract_title(content) or source_id
            return Document(
                title=title,
                content=content,
                source=DocumentSource.WEB,
                source_id=source_id,
                source_url=source_id,
                mime_type="text/html",
            )

    async def list(self, params: Optional[Dict[str, Any]] = None) -> List[Document]:
        urls = (params or {}).get("urls", [])
        return [await self.fetch(url) for url in urls]

    async def sync(self, last_sync_at: Optional[str] = None) -> List[Document]:
        return []

    def _extract_title(self, html: str) -> Optional[str]:
        import re
        match = re.search(r"<title>(.*?)</title>", html, re.IGNORECASE)
        return match.group(1).strip() if match else None


class SourceManager:
    def __init__(self) -> None:
        self._sources: Dict[DocumentSource, DocumentSourceAdapter] = {}
        self._register_default_sources()

    def _register_default_sources(self) -> None:
        self.register(DocumentSource.LOCAL_FILE, LocalFileSource())
        self.register(DocumentSource.WEB, WebSource())

    def register(self, source_type: DocumentSource, adapter: DocumentSourceAdapter) -> None:
        self._sources[source_type] = adapter

    def get(self, source_type: DocumentSource) -> DocumentSourceAdapter:
        if source_type not in self._sources:
            raise ValidationError(f"Unsupported source type: {source_type}")
        return self._sources[source_type]

    async def fetch_document(self, source: DocumentSource, source_id: str) -> Document:
        return await self.get(source).fetch(source_id)

    async def sync_all(self) -> List[Document]:
        all_docs: List[Document] = []
        for source_type, adapter in self._sources.items():
            try:
                docs = await adapter.sync()
                all_docs.extend(docs)
                logger.info(f"Synced {len(docs)} documents from {source_type}")
            except Exception as e:
                logger.error(f"Failed to sync {source_type}: {e}")
        return all_docs
