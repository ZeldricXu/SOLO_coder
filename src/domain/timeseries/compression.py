import struct
import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

from src.infrastructure.config.settings import TimeseriesCompressionConfig

logger = logging.getLogger(__name__)


class CompressionAlgorithm(Enum):
    GORILLA = "gorilla"
    DELTA = "delta"
    DELTA_OF_DELTA = "delta_of_delta"
    RUN_LENGTH = "run_length"
    SNAPPY = "snappy"
    LZ4 = "lz4"
    ZSTD = "zstd"


@dataclass
class CompressedBlock:
    metric_name: str
    start_timestamp: int
    end_timestamp: int
    algorithm: str
    data: bytes
    original_size: int
    compressed_size: int
    point_count: int

    @property
    def compression_ratio(self) -> float:
        if self.compressed_size == 0:
            return 0.0
        return self.original_size / self.compressed_size


class GorillaEncoder:
    def __init__(self):
        self._prev_value: Optional[int] = None
        self._prev_leading_zeros: int = 0
        self._prev_trailing_zeros: int = 0
        self._buffer: List[int] = []
        self._bit_buffer: int = 0
        self._bit_count: int = 0

    def encode_value(self, value: float) -> bytes:
        current = struct.unpack(">Q", struct.pack(">d", value))[0]

        if self._prev_value is None:
            self._prev_value = current
            return struct.pack(">Q", current)

        xor = current ^ self._prev_value
        self._prev_value = current

        if xor == 0:
            return struct.pack(">B", 0)

        leading_zeros = self._count_leading_zeros(xor)
        trailing_zeros = self._count_trailing_zeros(xor)

        result = bytearray()

        result.append(1)

        if leading_zeros >= self._prev_leading_zeros and trailing_zeros >= self._prev_trailing_zeros:
            result.append(1)
            meaningful_bits = 64 - self._prev_leading_zeros - self._prev_trailing_zeros
            meaningful_xor = xor >> self._prev_trailing_zeros
            result.extend(struct.pack(">Q", meaningful_xor)[-((meaningful_bits + 7) // 8):])
        else:
            result.append(0)
            result.extend(struct.pack(">B", leading_zeros))
            meaningful_bits = 64 - leading_zeros - trailing_zeros
            result.extend(struct.pack(">B", meaningful_bits))
            meaningful_xor = xor >> trailing_zeros
            result.extend(struct.pack(">Q", meaningful_xor)[-((meaningful_bits + 7) // 8):])
            self._prev_leading_zeros = leading_zeros
            self._prev_trailing_zeros = trailing_zeros

        return bytes(result)

    def _count_leading_zeros(self, value: int) -> int:
        if value == 0:
            return 64
        count = 0
        for i in range(63, -1, -1):
            if value & (1 << i):
                break
            count += 1
        return count

    def _count_trailing_zeros(self, value: int) -> int:
        if value == 0:
            return 64
        count = 0
        while (value & 1) == 0:
            count += 1
            value >>= 1
        return count

    def reset(self) -> None:
        self._prev_value = None
        self._prev_leading_zeros = 0
        self._prev_trailing_zeros = 0


class DeltaEncoder:
    def __init__(self):
        self._prev_value: Optional[int] = None

    def encode_timestamp(self, timestamp: int) -> bytes:
        if self._prev_value is None:
            self._prev_value = timestamp
            return struct.pack(">Q", timestamp)
        delta = timestamp - self._prev_value
        self._prev_value = timestamp
        if -128 <= delta <= 127:
            return struct.pack(">bq", 1, delta)
        elif -32768 <= delta <= 32767:
            return struct.pack(">bh", 2, delta)
        elif -2147483648 <= delta <= 2147483647:
            return struct.pack(">bi", 4, delta)
        else:
            return struct.pack(">Bq", 8, delta)

    def reset(self) -> None:
        self._prev_value = None


class TimeseriesCompressor:
    def __init__(self, config: Optional[TimeseriesCompressionConfig] = None):
        self._config = config or TimeseriesCompressionConfig()
        self._algorithm = CompressionAlgorithm(self._config.algorithm)

    def compress(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
    ) -> CompressedBlock:
        if not timestamps or not values:
            raise ValueError("Empty data cannot be compressed")

        encoder_map = {
            CompressionAlgorithm.GORILLA: self._compress_gorilla,
            CompressionAlgorithm.DELTA: self._compress_delta,
            CompressionAlgorithm.DELTA_OF_DELTA: self._compress_delta_of_delta,
            CompressionAlgorithm.RUN_LENGTH: self._compress_run_length,
        }

        encoder = encoder_map.get(self._algorithm, self._compress_gorilla)
        compressed_data, original_size = encoder(timestamps, values)

        if self._config.algorithm in ("snappy", "lz4", "zstd"):
            compressed_data = self._apply_compression(compressed_data)

        return CompressedBlock(
            metric_name=metric_name,
            start_timestamp=timestamps[0],
            end_timestamp=timestamps[-1],
            algorithm=self._algorithm.value,
            data=compressed_data,
            original_size=original_size,
            compressed_size=len(compressed_data),
            point_count=len(timestamps),
        )

    def _compress_gorilla(self, timestamps: List[int], values: List[float]) -> Tuple[bytes, int]:
        ts_encoder = DeltaEncoder()
        val_encoder = GorillaEncoder()

        parts = []
        original_size = len(timestamps) * 8 + len(values) * 8

        for ts, val in zip(timestamps, values):
            ts_bytes = ts_encoder.encode_timestamp(ts)
            val_bytes = val_encoder.encode_value(val)
            ts_len = struct.pack(">H", len(ts_bytes))
            val_len = struct.pack(">H", len(val_bytes))
            parts.append(ts_len + ts_bytes + val_len + val_bytes)

        header = struct.pack(">II", len(timestamps), len(values))
        return header + b"".join(parts), original_size

    def _compress_delta(self, timestamps: List[int], values: List[float]) -> Tuple[bytes, int]:
        original_size = len(timestamps) * 8 + len(values) * 8
        ts_encoder = DeltaEncoder()

        parts = [struct.pack(">Q", timestamps[0])]
        ts_encoder._prev_value = timestamps[0]

        for ts in timestamps[1:]:
            parts.append(ts_encoder.encode_timestamp(ts))

        for val in values:
            parts.append(struct.pack(">d", val))

        return b"".join(parts), original_size

    def _compress_delta_of_delta(self, timestamps: List[int], values: List[float]) -> Tuple[bytes, int]:
        original_size = len(timestamps) * 8 + len(values) * 8

        parts = [struct.pack(">Q", timestamps[0])]

        if len(timestamps) > 1:
            prev_delta = timestamps[1] - timestamps[0]
            parts.append(struct.pack(">q", prev_delta))

            for i in range(2, len(timestamps)):
                delta = timestamps[i] - timestamps[i - 1]
                delta_of_delta = delta - prev_delta
                parts.append(struct.pack(">q", delta_of_delta))
                prev_delta = delta

        for val in values:
            parts.append(struct.pack(">d", val))

        return b"".join(parts), original_size

    def _compress_run_length(self, timestamps: List[int], values: List[float]) -> Tuple[bytes, int]:
        original_size = len(timestamps) * 8 + len(values) * 8

        if not values:
            return b"", original_size

        runs = []
        current_val = values[0]
        current_start = timestamps[0]
        current_count = 1

        for i in range(1, len(values)):
            if values[i] == current_val:
                current_count += 1
            else:
                runs.append((current_start, current_val, current_count))
                current_val = values[i]
                current_start = timestamps[i]
                current_count = 1
        runs.append((current_start, current_val, current_count))

        parts = [struct.pack(">I", len(runs))]
        for start_ts, val, count in runs:
            parts.append(struct.pack(">QdI", start_ts, val, count))

        return b"".join(parts), original_size

    def _apply_compression(self, data: bytes) -> bytes:
        try:
            if self._config.algorithm == "snappy":
                import snappy
                return snappy.compress(data)
            elif self._config.algorithm == "lz4":
                import lz4.frame
                return lz4.frame.compress(data)
            elif self._config.algorithm == "zstd":
                import zstandard as zstd
                cctx = zstd.ZstdCompressor()
                return cctx.compress(data)
        except ImportError:
            logger.warning(f"Compression library for {self._config.algorithm} not available")
        return data

    def decompress(self, block: CompressedBlock) -> Tuple[List[int], List[float]]:
        data = block.data
        algo = CompressionAlgorithm(block.algorithm)

        decoder_map = {
            CompressionAlgorithm.GORILLA: self._decompress_gorilla,
            CompressionAlgorithm.DELTA: self._decompress_delta,
            CompressionAlgorithm.DELTA_OF_DELTA: self._decompress_delta_of_delta,
            CompressionAlgorithm.RUN_LENGTH: self._decompress_run_length,
        }

        decoder = decoder_map.get(algo)
        if decoder:
            return decoder(data)
        raise ValueError(f"Unsupported decompression algorithm: {algo.value}")

    def _decompress_gorilla(self, data: bytes) -> Tuple[List[int], List[float]]:
        timestamps = []
        values = []
        pos = 0
        count = struct.unpack_from(">I", data, pos)[0]
        pos += 4
        pos += 4

        prev_ts = None
        prev_val = None
        prev_leading = 0
        prev_trailing = 0

        for _ in range(count):
            ts_len = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            if prev_ts is None:
                ts = struct.unpack_from(">Q", data, pos)[0]
            else:
                delta = struct.unpack_from(">q", data, pos)[0]
                ts = prev_ts + delta
            pos += ts_len
            timestamps.append(ts)
            prev_ts = ts

            val_len = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            val_bytes = data[pos:pos + val_len]
            pos += val_len
            try:
                val = struct.unpack(">d", val_bytes)[0]
            except struct.error:
                val = 0.0
            values.append(val)
            prev_val = val

        return timestamps, values

    def _decompress_delta(self, data: bytes) -> Tuple[List[int], List[float]]:
        timestamps = []
        values = []
        pos = 0

        first_ts = struct.unpack_from(">Q", data, pos)[0]
        pos += 8
        timestamps.append(first_ts)
        prev_ts = first_ts

        remaining_bytes = len(data) - pos
        n_remaining = remaining_bytes // 8
        half = n_remaining // 2

        for _ in range(half):
            try:
                size_hint = data[pos]
                pos += 1
                if size_hint == 1:
                    delta = struct.unpack_from(">b", data, pos)[0]
                    pos += 1
                elif size_hint == 2:
                    delta = struct.unpack_from(">h", data, pos)[0]
                    pos += 2
                elif size_hint == 4:
                    delta = struct.unpack_from(">i", data, pos)[0]
                    pos += 4
                else:
                    delta = struct.unpack_from(">q", data, pos)[0]
                    pos += 8
                ts = prev_ts + delta
                timestamps.append(ts)
                prev_ts = ts
            except Exception:
                break

        while pos + 8 <= len(data):
            val = struct.unpack_from(">d", data, pos)[0]
            values.append(val)
            pos += 8

        min_len = min(len(timestamps), len(values))
        return timestamps[:min_len], values[:min_len]

    def _decompress_delta_of_delta(self, data: bytes) -> Tuple[List[int], List[float]]:
        timestamps = []
        values = []
        pos = 0

        first_ts = struct.unpack_from(">Q", data, pos)[0]
        pos += 8
        timestamps.append(first_ts)
        prev_ts = first_ts

        if len(data) > pos + 8:
            first_delta = struct.unpack_from(">q", data, pos)[0]
            pos += 8
            timestamps.append(first_ts + first_delta)
            prev_delta = first_delta
            prev_ts = first_ts + first_delta

            while pos + 8 <= len(data):
                try:
                    dod = struct.unpack_from(">q", data, pos)[0]
                    pos += 8
                    delta = prev_delta + dod
                    ts = prev_ts + delta
                    timestamps.append(ts)
                    prev_delta = delta
                    prev_ts = ts
                except Exception:
                    break

        while pos + 8 <= len(data):
            val = struct.unpack_from(">d", data, pos)[0]
            values.append(val)
            pos += 8

        min_len = min(len(timestamps), len(values))
        return timestamps[:min_len], values[:min_len]

    def _decompress_run_length(self, data: bytes) -> Tuple[List[int], List[float]]:
        timestamps = []
        values = []
        pos = 0

        run_count = struct.unpack_from(">I", data, pos)[0]
        pos += 4

        for _ in range(run_count):
            start_ts = struct.unpack_from(">Q", data, pos)[0]
            pos += 8
            val = struct.unpack_from(">d", data, pos)[0]
            pos += 8
            count = struct.unpack_from(">I", data, pos)[0]
            pos += 4

            for i in range(count):
                timestamps.append(start_ts + i)
                values.append(val)

        return timestamps, values
