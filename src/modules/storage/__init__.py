"""
存储管理模块
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.domain.contracts.storage import StorageProtocol
from src.domain.contracts.tracing import LoggerProtocol
from src.domain.errors.storage import StorageError
from src.infra.storage import StorageMetadataIndex


@dataclass
class StoredObject:
    bucket: str
    key: str
    size: int
    etag: str
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


class StorageManager:
    def __init__(self, storage: StorageProtocol, logger: Optional[LoggerProtocol] = None) -> None:
        self._storage = storage
        self._metadata_index = StorageMetadataIndex()
        self._logger = logger

    @classmethod
    def create_memory_storage(cls, logger: Optional[LoggerProtocol] = None) -> "StorageManager":
        from src.infra.storage import MemoryStorage
        return cls(MemoryStorage(), logger)

    async def upload(self, bucket: str, key: str, data: bytes, metadata: Optional[Dict[str, Any]] = None) -> StoredObject:
        etag = hashlib.md5(data).hexdigest()
        full_metadata = {**(metadata or {}), "etag": etag, "size": len(data), "created_at": time.time()}
        await self._storage.upload(bucket, key, data, full_metadata)
        self._metadata_index.index_object(bucket, key, full_metadata)
        return StoredObject(bucket=bucket, key=key, size=len(data), etag=etag, metadata=full_metadata)

    async def download(self, bucket: str, key: str) -> tuple:
        data = await self._storage.download(bucket, key)
        metadata = await self._storage.get_metadata(bucket, key)
        return data, StoredObject(bucket=bucket, key=key, size=len(data), etag=metadata.get("etag", ""), metadata=metadata)

    async def delete(self, bucket: str, key: str) -> None:
        await self._storage.delete(bucket, key)

    async def exists(self, bucket: str, key: str) -> bool:
        return await self._storage.exists(bucket, key)

    async def list(self, bucket: str, prefix: Optional[str] = None) -> List[StoredObject]:
        entries = await self._storage.list(bucket, prefix)
        return [
            StoredObject(bucket=bucket, key=e["key"], size=e.get("size", 0), etag=e.get("metadata", {}).get("etag", ""), metadata=e.get("metadata", {}))
            for e in entries
        ]

    def search_metadata(self, bucket: Optional[str] = None, **filters: Any) -> List[Dict[str, Any]]:
        return self._metadata_index.search_by_metadata(bucket, **filters)

    def search_by_tag(self, tag: str) -> List[Dict[str, Any]]:
        return self._metadata_index.search_by_tag(tag)
