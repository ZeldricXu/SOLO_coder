"""
存储管理模块
对象存储适配与元数据索引
"""

from .storage_manager_module import (
    StorageManager,
    ObjectStorageService,
    MetadataService,
)

__all__ = [
    "StorageManager",
    "ObjectStorageService",
    "MetadataService",
]
