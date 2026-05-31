"""Storage-related contract interfaces."""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import AsyncIterator, Dict, List, Optional, Tuple
from uuid import UUID

from ..models.common import (
    EventMessage,
    FileMetadata,
    LifecyclePolicy,
    ProcessingResult,
    StorageTier,
)


class IStorageBackend(ABC):
    @abstractmethod
    async def save_file(
        self,
        file_path: str,
        data: bytes,
        metadata: Optional[Dict] = None,
    ) -> Tuple[str, str]:
        pass

    @abstractmethod
    async def get_file(self, file_path: str) -> Tuple[bytes, Dict]:
        pass

    @abstractmethod
    async def delete_file(self, file_path: str) -> bool:
        pass

    @abstractmethod
    async def file_exists(self, file_path: str) -> bool:
        pass

    @abstractmethod
    async def get_file_size(self, file_path: str) -> int:
        pass

    @abstractmethod
    async def list_files(
        self,
        prefix: str = "",
        recursive: bool = True,
    ) -> List[str]:
        pass

    @abstractmethod
    async def get_available_space(self) -> int:
        pass

    @abstractmethod
    def get_tier(self) -> StorageTier:
        pass


class ILifecycleManager(ABC):
    @abstractmethod
    async def apply_lifecycle_policy(
        self,
        file_metadata: FileMetadata,
        policy: LifecyclePolicy,
    ) -> FileMetadata:
        pass

    @abstractmethod
    async def run_lifecycle_check(self) -> ProcessingResult:
        pass

    @abstractmethod
    async def move_file_between_tiers(
        self,
        file_id: UUID,
        target_tier: StorageTier,
    ) -> FileMetadata:
        pass

    @abstractmethod
    async def restore_file(
        self,
        file_id: UUID,
        restore_days: int = 7,
    ) -> FileMetadata:
        pass

    @abstractmethod
    async def delete_expired_files(self) -> ProcessingResult:
        pass


class IStorageService(ABC):
    @abstractmethod
    async def process_event(self, event: EventMessage) -> ProcessingResult:
        pass

    @abstractmethod
    async def upload_file(
        self,
        file_name: str,
        content: bytes,
        content_type: str,
        metadata: Optional[Dict] = None,
    ) -> FileMetadata:
        pass

    @abstractmethod
    async def download_file(self, file_id: UUID) -> Tuple[bytes, FileMetadata]:
        pass

    @abstractmethod
    async def get_file_metadata(self, file_id: UUID) -> FileMetadata:
        pass

    @abstractmethod
    async def list_files(
        self,
        tier: Optional[StorageTier] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> Tuple[List[FileMetadata], int]:
        pass

    @abstractmethod
    async def delete_file(self, file_id: UUID) -> bool:
        pass

    @abstractmethod
    async def validate_event(self, event: EventMessage) -> None:
        pass

    @abstractmethod
    def calculate_checksum(self, data: bytes) -> str:
        pass

    @abstractmethod
    async def stream_file(
        self,
        file_id: UUID,
        chunk_size: int = 8192,
    ) -> AsyncIterator[bytes]:
        pass
