"""
存储契约
"""

from __future__ import annotations

from abc import abstractmethod
from typing import Any, Dict, List, Optional, Protocol, runtime_checkable


@runtime_checkable
class StorageProtocol(Protocol):
    @abstractmethod
    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str: ...

    @abstractmethod
    async def download(self, bucket: str, key: str) -> bytes: ...

    @abstractmethod
    async def delete(self, bucket: str, key: str) -> None: ...

    @abstractmethod
    async def exists(self, bucket: str, key: str) -> bool: ...

    @abstractmethod
    async def list(
        self, bucket: str, prefix: Optional[str] = None
    ) -> List[Dict[str, Any]]: ...

    @abstractmethod
    async def get_metadata(self, bucket: str, key: str) -> Dict[str, Any]: ...
