"""Archive storage backend implementation for long-term data retention."""
from __future__ import annotations

import asyncio
import hashlib
import json
import lz4.frame
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from .cold_storage import ColdStorageBackend
from ...domain.models.common import StorageTier


class ArchiveStorageBackend(ColdStorageBackend):
    def __init__(self, base_path: str, max_size_gb: int = 2000) -> None:
        super().__init__(base_path, max_size_gb)
        self._tier = StorageTier.ARCHIVE
        self._compression_level = 9

    async def save_file(
        self,
        file_path: str,
        data: bytes,
        metadata: Optional[Dict] = None,
    ) -> Tuple[str, str]:
        original_size = len(data)
        compressed_data = await self._compress_data_lz4(data)

        result = await super().save_file(file_path, data, metadata)

        meta = metadata or {}
        meta["original_size"] = original_size
        meta["compression"] = "lz4"
        meta["compression_ratio"] = original_size / len(compressed_data) if len(compressed_data) > 0 else 1.0
        meta["archived"] = True
        meta["archive_date"] = asyncio.get_event_loop().time()

        return result

    async def get_file(self, file_path: str) -> Tuple[bytes, Dict]:
        return await super().get_file(file_path)

    async def _compress_data_lz4(self, data: bytes) -> bytes:
        return await asyncio.to_thread(lz4.frame.compress, data, compression_level=self._compression_level)

    async def _decompress_data_lz4(self, data: bytes) -> bytes:
        return await asyncio.to_thread(lz4.frame.decompress, data)
