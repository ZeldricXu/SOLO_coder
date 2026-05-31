"""
存储管理实现
核心功能：
1. 对象存储多后端适配
2. 元数据索引与检索
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.core import StorageError, StorageProtocol, LoggerProtocol
from src.infrastructure.storage import StorageMetadataIndex


@dataclass
class StoredObject:
    bucket: str
    key: str
    size: int
    etag: str
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


class ObjectStorageService:
    """对象存储服务 - 适配多种存储后端"""

    def __init__(
        self,
        storage: StorageProtocol,
        metadata_index: Optional[StorageMetadataIndex] = None,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._storage = storage
        self._metadata_index = metadata_index or StorageMetadataIndex()
        self._logger = logger

    def _calculate_etag(self, data: bytes) -> str:
        return hashlib.md5(data).hexdigest()

    async def put_object(
        self,
        bucket: str,
        key: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredObject:
        """上传对象"""
        try:
            etag = self._calculate_etag(data)
            full_metadata = {
                **(metadata or {}),
                "etag": etag,
                "size": len(data),
                "created_at": time.time(),
            }

            uri = await self._storage.upload(bucket, key, data, full_metadata)

            stored_obj = StoredObject(
                bucket=bucket,
                key=key,
                size=len(data),
                etag=etag,
                metadata=full_metadata,
            )

            self._metadata_index.index_object(bucket, key, full_metadata)

            if self._logger:
                self._logger.info(
                    "Object stored",
                    bucket=bucket,
                    key=key,
                    size=len(data),
                    uri=uri,
                )

            return stored_obj

        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                f"Failed to store object: {e}", bucket=bucket, key=key
            ) from e

    async def get_object(
        self,
        bucket: str,
        key: str,
    ) -> tuple[bytes, StoredObject]:
        """获取对象"""
        try:
            data = await self._storage.download(bucket, key)
            metadata = await self._storage.get_metadata(bucket, key)

            stored_obj = StoredObject(
                bucket=bucket,
                key=key,
                size=len(data),
                etag=metadata.get("etag", self._calculate_etag(data)),
                metadata=metadata,
            )

            return data, stored_obj

        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                f"Failed to get object: {e}", bucket=bucket, key=key
            ) from e

    async def delete_object(self, bucket: str, key: str) -> None:
        """删除对象"""
        try:
            await self._storage.delete(bucket, key)

            if self._logger:
                self._logger.info(
                    "Object deleted",
                    bucket=bucket,
                    key=key,
                )

        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                f"Failed to delete object: {e}", bucket=bucket, key=key
            ) from e

    async def object_exists(self, bucket: str, key: str) -> bool:
        """检查对象是否存在"""
        return await self._storage.exists(bucket, key)

    async def list_objects(
        self,
        bucket: str,
        prefix: Optional[str] = None,
    ) -> List[StoredObject]:
        """列出对象"""
        try:
            entries = await self._storage.list(bucket, prefix)
            objects = []

            for entry in entries:
                obj = StoredObject(
                    bucket=bucket,
                    key=entry["key"],
                    size=entry.get("size", 0),
                    etag=entry.get("metadata", {}).get("etag", ""),
                    metadata=entry.get("metadata", {}),
                )
                objects.append(obj)

            return objects

        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                f"Failed to list objects: {e}", bucket=bucket
            ) from e

    async def copy_object(
        self,
        src_bucket: str,
        src_key: str,
        dst_bucket: str,
        dst_key: str,
    ) -> StoredObject:
        """复制对象"""
        data, _ = await self.get_object(src_bucket, src_key)
        metadata = await self._storage.get_metadata(src_bucket, src_key)
        return await self.put_object(dst_bucket, dst_key, data, metadata)


class MetadataService:
    """元数据服务 - 提供元数据索引与检索"""

    def __init__(
        self,
        index: Optional[StorageMetadataIndex] = None,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._index = index or StorageMetadataIndex()
        self._logger = logger

    def index(
        self,
        bucket: str,
        key: str,
        metadata: Dict[str, Any],
    ) -> None:
        """索引对象元数据"""
        self._index.index_object(bucket, key, metadata)

        if self._logger:
            self._logger.debug(
                "Object metadata indexed",
                bucket=bucket,
                key=key,
            )

    def search_by_tag(self, tag: str) -> List[Dict[str, Any]]:
        """按标签搜索"""
        results = self._index.search_by_tag(tag)

        if self._logger:
            self._logger.debug(
                "Tag search completed",
                tag=tag,
                results_count=len(results),
            )

        return results

    def search(
        self,
        bucket: Optional[str] = None,
        **filters: Any,
    ) -> List[Dict[str, Any]]:
        """按元数据字段搜索"""
        results = self._index.search_by_metadata(bucket, **filters)

        if self._logger:
            self._logger.debug(
                "Metadata search completed",
                bucket=bucket,
                filters=filters,
                results_count=len(results),
            )

        return results


class StorageManager:
    """
    存储管理器 - 核心类
    整合对象存储和元数据服务
    """

    def __init__(
        self,
        storage: StorageProtocol,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._metadata_index = StorageMetadataIndex()
        self._object_service = ObjectStorageService(
            storage, self._metadata_index, logger
        )
        self._metadata_service = MetadataService(self._metadata_index, logger)
        self._logger = logger

    @classmethod
    def create_memory_storage(cls, logger: Optional[LoggerProtocol] = None) -> "StorageManager":
        """创建内存存储管理器（用于测试）"""
        from src.infrastructure.storage import MemoryStorage
        return cls(MemoryStorage(), logger)

    @classmethod
    def create_local_storage(
        cls, base_path: str, logger: Optional[LoggerProtocol] = None
    ) -> "StorageManager":
        """创建本地文件存储管理器"""
        from src.infrastructure.storage import LocalFileStorage
        return cls(LocalFileStorage(base_path), logger)

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredObject:
        return await self._object_service.put_object(bucket, key, data, metadata)

    async def download(
        self,
        bucket: str,
        key: str,
    ) -> tuple[bytes, StoredObject]:
        return await self._object_service.get_object(bucket, key)

    async def delete(self, bucket: str, key: str) -> None:
        await self._object_service.delete_object(bucket, key)

    async def exists(self, bucket: str, key: str) -> bool:
        return await self._object_service.object_exists(bucket, key)

    async def list(
        self,
        bucket: str,
        prefix: Optional[str] = None,
    ) -> List[StoredObject]:
        return await self._object_service.list_objects(bucket, prefix)

    async def copy(
        self,
        src_bucket: str,
        src_key: str,
        dst_bucket: str,
        dst_key: str,
    ) -> StoredObject:
        return await self._object_service.copy_object(
            src_bucket, src_key, dst_bucket, dst_key
        )

    def search_metadata(
        self,
        bucket: Optional[str] = None,
        **filters: Any,
    ) -> List[Dict[str, Any]]:
        return self._metadata_service.search(bucket, **filters)

    def search_by_tag(self, tag: str) -> List[Dict[str, Any]]:
        return self._metadata_service.search_by_tag(tag)

    def get_object_service(self) -> ObjectStorageService:
        return self._object_service

    def get_metadata_service(self) -> MetadataService:
        return self._metadata_service
