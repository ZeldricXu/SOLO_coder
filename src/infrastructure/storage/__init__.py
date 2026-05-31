"""
存储基础设施实现
实现 StorageProtocol 协议，提供多种存储后端适配
"""

from .storage_impl import (
    MemoryStorage,
    LocalFileStorage,
    S3Storage,
    StorageMetadataIndex,
)

__all__ = [
    "MemoryStorage",
    "LocalFileStorage",
    "S3Storage",
    "StorageMetadataIndex",
]
