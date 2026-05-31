"""
存储基础设施实现
"""

from __future__ import annotations

import os
import pickle
import tempfile
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set

from src.domain.contracts.storage import StorageProtocol
from src.domain.errors.storage import StorageError


@dataclass
class _StorageObject:
    data: bytes
    metadata: Dict[str, Any] = field(default_factory=dict)


class MemoryStorage(StorageProtocol):
    def __init__(self) -> None:
        self._buckets: Dict[str, Dict[str, _StorageObject]] = {}

    def _ensure_bucket(self, bucket: str) -> None:
        if bucket not in self._buckets:
            self._buckets[bucket] = {}

    async def upload(self, bucket: str, key: str, data: bytes, metadata: Optional[Dict[str, Any]] = None) -> str:
        self._ensure_bucket(bucket)
        self._buckets[bucket][key] = _StorageObject(data=data, metadata=metadata or {})
        return f"memory://{bucket}/{key}"

    async def download(self, bucket: str, key: str) -> bytes:
        self._ensure_bucket(bucket)
        if key not in self._buckets[bucket]:
            raise StorageError("Object not found", bucket=bucket, key=key)
        return self._buckets[bucket][key].data

    async def delete(self, bucket: str, key: str) -> None:
        self._ensure_bucket(bucket)
        if key in self._buckets[bucket]:
            del self._buckets[bucket][key]

    async def exists(self, bucket: str, key: str) -> bool:
        self._ensure_bucket(bucket)
        return key in self._buckets[bucket]

    async def list(self, bucket: str, prefix: Optional[str] = None) -> List[Dict[str, Any]]:
        self._ensure_bucket(bucket)
        results = []
        for key, obj in self._buckets[bucket].items():
            if prefix is None or key.startswith(prefix):
                results.append({"key": key, "size": len(obj.data), "metadata": obj.metadata})
        return results

    async def get_metadata(self, bucket: str, key: str) -> Dict[str, Any]:
        self._ensure_bucket(bucket)
        if key not in self._buckets[bucket]:
            raise StorageError("Object not found", bucket=bucket, key=key)
        return self._buckets[bucket][key].metadata


class StorageMetadataIndex:
    def __init__(self) -> None:
        self._index: Dict[str, Dict[str, Any]] = {}
        self._tag_index: Dict[str, List[str]] = {}

    def index_object(self, bucket: str, key: str, metadata: Dict[str, Any]) -> None:
        obj_id = f"{bucket}/{key}"
        self._index[obj_id] = {"bucket": bucket, "key": key, **metadata}
        for tag in metadata.get("tags", []):
            if tag not in self._tag_index:
                self._tag_index[tag] = []
            if obj_id not in self._tag_index[tag]:
                self._tag_index[tag].append(obj_id)

    def search_by_tag(self, tag: str) -> List[Dict[str, Any]]:
        return [self._index[oid] for oid in self._tag_index.get(tag, [])]

    def search_by_metadata(self, bucket: Optional[str] = None, **filters: Any) -> List[Dict[str, Any]]:
        results = []
        for meta in self._index.values():
            if bucket and meta["bucket"] != bucket:
                continue
            if all(meta.get(k) == v for k, v in filters.items()):
                results.append(meta)
        return results
