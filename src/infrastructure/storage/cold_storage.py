"""Cold storage backend implementation for infrequently accessed data."""
from __future__ import annotations

import asyncio
import hashlib
import json
import zstandard as zstd
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from .hot_storage import HotStorageBackend
from ...domain.models.common import StorageTier


class ColdStorageBackend(HotStorageBackend):
    def __init__(self, base_path: str, max_size_gb: int = 500) -> None:
        super().__init__(base_path, max_size_gb)
        self._tier = StorageTier.COLD
        self._compression_level = 3

    async def save_file(
        self,
        file_path: str,
        data: bytes,
        metadata: Optional[Dict] = None,
    ) -> Tuple[str, str]:
        original_size = len(data)
        compressed_data = await self._compress_data(data)

        result = await super().save_file(
            file_path + ".zst", compressed_data, metadata)

        meta = metadata or {}
        meta["original_size"] = original_size
        meta["compression"] = "zstandard"
        meta["compression_ratio"] = original_size / len(compressed_data) if len(compressed_data) > 0 else 1.0

        return result

    async def get_file(self, file_path: str) -> Tuple[bytes, Dict]:
        compressed_path = file_path
        if not file_path.endswith(".zst"):
            compressed_path = file_path + ".zst"

        compressed_data, metadata = await super().get_file(compressed_path)

        if metadata.get("compression") == "zstandard":
            data = await self._decompress_data(compressed_data)
            return data, metadata

        return compressed_data, metadata

    async def _compress_data(self, data: bytes) -> bytes:
        cctx = zstd.ZstdCompressor(level=self._compression_level)
        return await asyncio.to_thread(cctx.compress, data)

    async def _decompress_data(self, data: bytes) -> bytes:
        dctx = zstd.ZstdDecompressor()
        return await asyncio.to_thread(dctx.decompress, data)
