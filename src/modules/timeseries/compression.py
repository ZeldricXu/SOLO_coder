"""Time series data compression implementations."""
from __future__ import annotations

import io
import struct
from dataclasses import dataclass
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

import lz4.frame
import numpy as np
import zstandard as zstd

from ...domain.models.common import TimeSeriesData, TimeSeriesDataPoint
from ...infrastructure.logging.structured_logger import LogManager


class CompressionAlgorithm(str, Enum):
    NONE = "none"
    LZ4 = "lz4"
    ZSTD = "zstd"
    GORILLA = "gorilla"
    DELTA = "delta"
    RLE = "rle"


@dataclass
class CompressionResult:
    algorithm: CompressionAlgorithm
    original_size: int
    compressed_size: int
    compression_ratio: float
    data: bytes
    metadata: Dict[str, Any]


class TimeSeriesCompressor:
    def __init__(self, default_algorithm: CompressionAlgorithm = CompressionAlgorithm.ZSTD) -> None:
        self._default_algorithm = default_algorithm
        self._logger = LogManager().get_logger(__name__)

    async def compress(
        self,
        data_points: List[TimeSeriesDataPoint],
        algorithm: Optional[CompressionAlgorithm] = None,
        **kwargs: Any,
    ) -> CompressionResult:
        algo = algorithm or self._default_algorithm
        original_bytes = self._serialize_data_points(data_points)
        original_size = len(original_bytes)

        self._logger.debug(
            f"Compressing {len(data_points)} data points using {algo.value}",
            original_size=original_size,
        )

        if algo == CompressionAlgorithm.NONE:
            compressed = original_bytes
        elif algo == CompressionAlgorithm.LZ4:
            compressed = await self._compress_lz4(original_bytes, **kwargs)
        elif algo == CompressionAlgorithm.ZSTD:
            compressed = await self._compress_zstd(original_bytes, **kwargs)
        elif algo == CompressionAlgorithm.GORILLA:
            compressed = await self._compress_gorilla(data_points, **kwargs)
        elif algo == CompressionAlgorithm.DELTA:
            compressed = await self._compress_delta(data_points, **kwargs)
        elif algo == CompressionAlgorithm.RLE:
            compressed = await self._compress_rle(data_points, **kwargs)
        else:
            raise ValueError(f"Unsupported compression algorithm: {algo}")

        compressed_size = len(compressed)
        ratio = original_size / compressed_size if compressed_size > 0 else 1.0

        result = CompressionResult(
            algorithm=algo,
            original_size=original_size,
            compressed_size=compressed_size,
            compression_ratio=ratio,
            data=compressed,
            metadata={"data_points_count": len(data_points)},
        )

        self._logger.debug(
            f"Compression completed: {original_size} -> {compressed_size} bytes (ratio: {ratio:.2f}x)",
            algorithm=algo.value,
        )

        return result

    async def decompress(
        self,
        compressed_data: bytes,
        algorithm: CompressionAlgorithm,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        self._logger.debug(f"Decompressing using {algorithm.value}")

        if algorithm == CompressionAlgorithm.NONE:
            return self._deserialize_data_points(compressed_data)
        elif algorithm == CompressionAlgorithm.LZ4:
            decompressed = await self._decompress_lz4(compressed_data, **kwargs)
        elif algorithm == CompressionAlgorithm.ZSTD:
            decompressed = await self._decompress_zstd(compressed_data, **kwargs)
        elif algorithm == CompressionAlgorithm.GORILLA:
            return await self._decompress_gorilla(compressed_data, **kwargs)
        elif algorithm == CompressionAlgorithm.DELTA:
            return await self._decompress_delta(compressed_data, **kwargs)
        elif algorithm == CompressionAlgorithm.RLE:
            return await self._decompress_rle(compressed_data, **kwargs)
        else:
            raise ValueError(f"Unsupported compression algorithm: {algorithm}")

        return self._deserialize_data_points(decompressed)

    def _serialize_data_points(self, data_points: List[TimeSeriesDataPoint]) -> bytes:
        buf = io.BytesIO()
        for dp in data_points:
            ts_bytes = struct.pack("!Q", int(dp.timestamp.timestamp() * 1e9))
            if isinstance(dp.value, (int, float)):
                type_flag = b"\x00" if isinstance(dp.value, int) else b"\x01"
                value_bytes = struct.pack("!d", float(dp.value))
            else:
                type_flag = b"\x02"
                str_bytes = str(dp.value).encode("utf-8")
                value_bytes = struct.pack("!H", len(str_bytes)) + str_bytes

            tags_bytes = str(dp.tags).encode("utf-8")
            tags_len = struct.pack("!H", len(tags_bytes))

            buf.write(ts_bytes)
            buf.write(type_flag)
            buf.write(value_bytes)
            buf.write(tags_len)
            buf.write(tags_bytes)

        return buf.getvalue()

    def _deserialize_data_points(self, data: bytes) -> List[TimeSeriesDataPoint]:
        from datetime import datetime

        points: List[TimeSeriesDataPoint] = []
        buf = io.BytesIO(data)

        while buf.tell() < len(data):
            ts_nanos = struct.unpack("!Q", buf.read(8))[0]
            timestamp = datetime.fromtimestamp(ts_nanos / 1e9)

            type_flag = buf.read(1)
            if type_flag == b"\x00":
                value = int(struct.unpack("!d", buf.read(8))[0])
            elif type_flag == b"\x01":
                value = struct.unpack("!d", buf.read(8))[0]
            else:
                str_len = struct.unpack("!H", buf.read(2))[0]
                value = buf.read(str_len).decode("utf-8")

            tags_len = struct.unpack("!H", buf.read(2))[0]
            tags_str = buf.read(tags_len).decode("utf-8")
            tags = eval(tags_str) if tags_str else {}

            points.append(TimeSeriesDataPoint(
                timestamp=timestamp,
                value=value,
                tags=tags,
            ))

        return points

    async def _compress_lz4(self, data: bytes, level: int = 9, **kwargs: Any) -> bytes:
        return lz4.frame.compress(data, compression_level=level)

    async def _decompress_lz4(self, data: bytes, **kwargs: Any) -> bytes:
        return lz4.frame.decompress(data)

    async def _compress_zstd(self, data: bytes, level: int = 3, **kwargs: Any) -> bytes:
        cctx = zstd.ZstdCompressor(level=level)
        return cctx.compress(data)

    async def _decompress_zstd(self, data: bytes, **kwargs: Any) -> bytes:
        dctx = zstd.ZstdDecompressor()
        return dctx.decompress(data)

    async def _compress_gorilla(self, data_points: List[TimeSeriesDataPoint], **kwargs: Any) -> bytes:
        if not data_points:
            return b""

        values = []
        for dp in data_points:
            if isinstance(dp.value, (int, float)):
                values.append(float(dp.value))
            else:
                values.append(0.0)

        arr = np.array(values, dtype=np.float64)
        buf = io.BytesIO()
        np.save(buf, arr)
        return await self._compress_zstd(buf.getvalue(), level=10)

    async def _decompress_gorilla(self, data: bytes, **kwargs: Any) -> List[TimeSeriesDataPoint]:
        from datetime import datetime, timedelta

        decompressed = await self._decompress_zstd(data)
        buf = io.BytesIO(decompressed)
        arr = np.load(buf)

        points: List[TimeSeriesDataPoint] = []
        base_time = datetime.utcnow() - timedelta(seconds=len(arr))

        for i, value in enumerate(arr):
            points.append(TimeSeriesDataPoint(
                timestamp=base_time + timedelta(seconds=i),
                value=float(value),
            ))

        return points

    async def _compress_delta(self, data_points: List[TimeSeriesDataPoint], **kwargs: Any) -> bytes:
        if not data_points:
            return b""

        values = []
        for dp in data_points:
            if isinstance(dp.value, (int, float)):
                values.append(float(dp.value))
            else:
                values.append(0.0)

        arr = np.array(values, dtype=np.float64)
        delta = np.diff(arr, prepend=arr[0])

        buf = io.BytesIO()
        np.save(buf, delta)
        return await self._compress_zstd(buf.getvalue(), level=10)

    async def _decompress_delta(self, data: bytes, **kwargs: Any) -> List[TimeSeriesDataPoint]:
        from datetime import datetime, timedelta

        decompressed = await self._decompress_zstd(data)
        buf = io.BytesIO(decompressed)
        delta = np.load(buf)
        arr = np.cumsum(delta)

        points: List[TimeSeriesDataPoint] = []
        base_time = datetime.utcnow() - timedelta(seconds=len(arr))

        for i, value in enumerate(arr):
            points.append(TimeSeriesDataPoint(
                timestamp=base_time + timedelta(seconds=i),
                value=float(value),
            ))

        return points

    async def _compress_rle(self, data_points: List[TimeSeriesDataPoint], **kwargs: Any) -> bytes:
        if not data_points:
            return b""

        encoded: List[Tuple[Any, int]] = []
        current_value = None
        count = 0

        for dp in data_points:
            if dp.value == current_value:
                count += 1
            else:
                if current_value is not None:
                    encoded.append((current_value, count))
                current_value = dp.value
                count = 1

        if current_value is not None:
            encoded.append((current_value, count))

        buf = io.BytesIO()
        for value, count in encoded:
            if isinstance(value, (int, float)):
                type_flag = b"\x00" if isinstance(value, int) else b"\x01"
                value_bytes = struct.pack("!d", float(value))
            else:
                type_flag = b"\x02"
                str_bytes = str(value).encode("utf-8")
                value_bytes = struct.pack("!H", len(str_bytes)) + str_bytes

            count_bytes = struct.pack("!I", count)
            buf.write(type_flag)
            buf.write(value_bytes)
            buf.write(count_bytes)

        return await self._compress_zstd(buf.getvalue(), level=10)

    async def _decompress_rle(self, data: bytes, **kwargs: Any) -> List[TimeSeriesDataPoint]:
        from datetime import datetime, timedelta

        decompressed = await self._decompress_zstd(data)
        buf = io.BytesIO(decompressed)

        points: List[TimeSeriesDataPoint] = []
        timestamp = datetime.utcnow()

        while buf.tell() < len(decompressed):
            type_flag = buf.read(1)
            if type_flag == b"\x00":
                value = int(struct.unpack("!d", buf.read(8))[0])
            elif type_flag == b"\x01":
                value = struct.unpack("!d", buf.read(8))[0]
            else:
                str_len = struct.unpack("!H", buf.read(2))[0]
                value = buf.read(str_len).decode("utf-8")

            count = struct.unpack("!I", buf.read(4))[0]

            for _ in range(count):
                points.append(TimeSeriesDataPoint(
                    timestamp=timestamp,
                    value=value,
                ))
                timestamp += timedelta(seconds=1)

        return points

    async def auto_select_algorithm(
        self,
        data_points: List[TimeSeriesDataPoint],
    ) -> CompressionAlgorithm:
        if len(data_points) < 100:
            return CompressionAlgorithm.NONE

        sample = data_points[:100]
        values = [dp.value for dp in sample if isinstance(dp.value, (int, float))]

        if len(values) < 50:
            return CompressionAlgorithm.ZSTD

        if len(set(values)) < len(values) * 0.1:
            return CompressionAlgorithm.RLE

        arr = np.array(values, dtype=np.float64)
        variance = np.var(arr)

        if variance < 1.0:
            return CompressionAlgorithm.DELTA

        if variance < 10.0:
            return CompressionAlgorithm.GORILLA

        return CompressionAlgorithm.ZSTD
