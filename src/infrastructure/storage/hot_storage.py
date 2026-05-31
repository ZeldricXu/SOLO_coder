"""Hot storage backend implementation for frequently accessed data."""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import shutil
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from ...domain.contracts.storage import IStorageBackend
from ...domain.errors.storage import (
    FileNotFoundError,
    StorageCapacityExceededError,
)
from ...domain.models.common import StorageTier


class HotStorageBackend(IStorageBackend):
    def __init__(self, base_path: str, max_size_gb: int = 100) -> None:
        self._base_path = Path(base_path)
        self._max_size_bytes = max_size_gb * 1024 * 1024 * 1024
        self._tier = StorageTier.HOT
        self._lock = asyncio.Lock()
        self._initialize_storage()

    def _initialize_storage(self) -> None:
        self._base_path.mkdir(parents=True, exist_ok=True)
        meta_path = self._base_path / ".metadata"
        meta_path.mkdir(exist_ok=True)

    def get_tier(self) -> StorageTier:
        return self._tier

    async def save_file(
        self,
        file_path: str,
        data: bytes,
        metadata: Optional[Dict] = None,
    ) -> Tuple[str, str]:
        async with self._lock:
            full_path = self._base_path / file_path.lstrip("/")
            full_path.parent.mkdir(parents=True, exist_ok=True)

            available_space = await self.get_available_space()
            if len(data) > available_space:
                raise StorageCapacityExceededError(
                    required_size=len(data),
                    available_size=available_space,
                    tier=self._tier.value,
                )

            await asyncio.to_thread(full_path.write_bytes, data)

            checksum = hashlib.sha256(data).hexdigest()

            if metadata is not None:
                meta_path = self._base_path / ".metadata" / f"{file_path.lstrip('/')}.json"
                meta_path.parent.mkdir(parents=True, exist_ok=True)
                await asyncio.to_thread(
                    meta_path.write_text,
                    json.dumps({**metadata, "checksum": checksum}, indent=2),
                )

            return str(full_path), checksum

    async def get_file(self, file_path: str) -> Tuple[bytes, Dict]:
        full_path = self._base_path / file_path.lstrip("/")

        if not await asyncio.to_thread(full_path.exists):
            raise FileNotFoundError(file_path)

        data = await asyncio.to_thread(full_path.read_bytes)

        meta_path = self._base_path / ".metadata" / f"{file_path.lstrip('/')}.json"
        metadata: Dict = {}
        if await asyncio.to_thread(meta_path.exists):
            try:
                metadata = json.loads(await asyncio.to_thread(meta_path.read_text))
            except (json.JSONDecodeError, IOError):
                metadata = {}

        return data, metadata

    async def delete_file(self, file_path: str) -> bool:
        async with self._lock:
            full_path = self._base_path / file_path.lstrip("/")
            meta_path = self._base_path / ".metadata" / f"{file_path.lstrip('/')}.json"

            if not await asyncio.to_thread(full_path.exists):
                return False

            try:
                await asyncio.to_thread(full_path.unlink)
                if await asyncio.to_thread(meta_path.exists):
                    await asyncio.to_thread(meta_path.unlink)
                return True
            except IOError:
                return False

    async def file_exists(self, file_path: str) -> bool:
        full_path = self._base_path / file_path.lstrip("/")
        return await asyncio.to_thread(full_path.exists)

    async def get_file_size(self, file_path: str) -> int:
        full_path = self._base_path / file_path.lstrip("/")
        if not await asyncio.to_thread(full_path.exists):
            raise FileNotFoundError(file_path)
        return await asyncio.to_thread(full_path.stat).st_size

    async def list_files(
        self,
        prefix: str = "",
        recursive: bool = True,
    ) -> List[str]:
        base_dir = self._base_path / prefix.lstrip("/")
        if not await asyncio.to_thread(base_dir.exists):
            return []

        files: List[str] = []
        pattern = "**/*" if recursive else "*"

        for path in await asyncio.to_thread(list, base_dir.glob(pattern)):
            if await asyncio.to_thread(path.is_file) and ".metadata" not in path.parts:
                rel_path = str(path.relative_to(self._base_path))
                files.append(rel_path)

        return sorted(files)

    async def get_available_space(self) -> int:
        try:
            total, used, free = await asyncio.to_thread(shutil.disk_usage, self._base_path)
            actual_free = min(free, self._max_size_bytes - await self._get_used_space())
            return max(0, actual_free)
        except (IOError, OSError):
            return max(0, self._max_size_bytes - await self._get_used_space())

    async def _get_used_space(self) -> int:
        total_size = 0
        for path in await asyncio.to_thread(list, self._base_path.rglob("*")):
            if await asyncio.to_thread(path.is_file) and ".metadata" not in path.parts:
                try:
                    total_size += await asyncio.to_thread(path.stat).st_size
                except (IOError, OSError):
                    pass
        return total_size
