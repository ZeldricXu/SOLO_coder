"""存储管理模块 - 对象存储适配与元数据索引"""

from .storage_manager import (
    StorageManager,
    ObjectStorageAdapter,
    LocalStorageAdapter,
    S3StorageAdapter,
    StoredObject,
    ObjectMetadata
)

__all__ = [
    "StorageManager",
    "ObjectStorageAdapter",
    "LocalStorageAdapter",
    "S3StorageAdapter",
    "StoredObject",
    "ObjectMetadata"
]
