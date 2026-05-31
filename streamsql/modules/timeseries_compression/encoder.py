from __future__ import annotations

import struct
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

import numpy as np

from streamsql.core.models import generate_id


@dataclass
class EncodedData:
    encoding_id: str = field(default_factory=lambda: generate_id("enc"))
    encoding_type: str = ""
    original_size: int = 0
    compressed_size: int = 0
    compression_ratio: float = 0.0
    data: bytes = b""
    metadata: dict[str, Any] = field(default_factory=dict)


class TimeSeriesEncoder(ABC):
    @abstractmethod
    def encode(self, timestamps: list[int], values: list[float]) -> EncodedData: ...

    @abstractmethod
    def decode(self, encoded: EncodedData) -> tuple[list[int], list[float]]: ...


class DeltaEncoder(TimeSeriesEncoder):
    def __init__(self):
        self.name = "delta"

    def encode(self, timestamps: list[int], values: list[float]) -> EncodedData:
        if not timestamps:
            return EncodedData(encoding_type=self.name, data=b"")

        delta_ts = [timestamps[0]]
        for i in range(1, len(timestamps)):
            delta_ts.append(timestamps[i] - timestamps[i - 1])

        delta_vals = [values[0]]
        for i in range(1, len(values)):
            delta_vals.append(values[i] - values[i - 1])

        ts_bytes = struct.pack(f"<{len(delta_ts)}Q", *delta_ts)
        val_bytes = struct.pack(f"<{len(delta_vals)}d", *delta_vals)

        data = struct.pack("<I", len(delta_ts)) + ts_bytes + val_bytes

        original_size = len(timestamps) * (8 + 8)
        compressed_size = len(data)

        return EncodedData(
            encoding_type=self.name,
            original_size=original_size,
            compressed_size=compressed_size,
            compression_ratio=original_size / compressed_size if compressed_size > 0 else 1.0,
            data=data,
            metadata={"count": len(timestamps)},
        )

    def decode(self, encoded: EncodedData) -> tuple[list[int], list[float]]:
        if not encoded.data:
            return [], []

        data = encoded.data
        count = struct.unpack("<I", data[:4])[0]

        ts_offset = 4
        ts_end = ts_offset + count * 8
        delta_ts = list(struct.unpack(f"<{count}Q", data[ts_offset:ts_end]))

        val_offset = ts_end
        delta_vals = list(struct.unpack(f"<{count}d", data[val_offset:]))

        timestamps = [delta_ts[0]]
        for d in delta_ts[1:]:
            timestamps.append(timestamps[-1] + d)

        values = [delta_vals[0]]
        for v in delta_vals[1:]:
            values.append(values[-1] + v)

        return timestamps, values


class GorillaEncoder(TimeSeriesEncoder):
    def __init__(self):
        self.name = "gorilla"

    def encode(self, timestamps: list[int], values: list[float]) -> EncodedData:
        if not timestamps:
            return EncodedData(encoding_type=self.name, data=b"")

        delta_ts = [timestamps[0]]
        for i in range(1, len(timestamps)):
            delta_ts.append(timestamps[i] - timestamps[i - 1])

        xor_vals = []
        prev_val = np.float64(values[0]).view("uint64")
        xor_vals.append(prev_val)
        for v in values[1:]:
            curr_val = np.float64(v).view("uint64")
            xor_vals.append(prev_val ^ curr_val)
            prev_val = curr_val

        ts_bytes = struct.pack(f"<{len(delta_ts)}Q", *delta_ts)
        xor_bytes = struct.pack(f"<{len(xor_vals)}Q", *xor_vals)

        data = struct.pack("<I", len(delta_ts)) + ts_bytes + xor_bytes

        original_size = len(timestamps) * (8 + 8)
        compressed_size = len(data)

        return EncodedData(
            encoding_type=self.name,
            original_size=original_size,
            compressed_size=compressed_size,
            compression_ratio=original_size / compressed_size if compressed_size > 0 else 1.0,
            data=data,
            metadata={"count": len(timestamps)},
        )

    def decode(self, encoded: EncodedData) -> tuple[list[int], list[float]]:
        if not encoded.data:
            return [], []

        data = encoded.data
        count = struct.unpack("<I", data[:4])[0]

        ts_offset = 4
        ts_end = ts_offset + count * 8
        delta_ts = list(struct.unpack(f"<{count}Q", data[ts_offset:ts_end]))

        xor_offset = ts_end
        xor_vals = list(struct.unpack(f"<{count}Q", data[xor_offset:]))

        timestamps = [delta_ts[0]]
        for d in delta_ts[1:]:
            timestamps.append(timestamps[-1] + d)

        values = []
        prev_val = xor_vals[0]
        values.append(np.float64(np.uint64(prev_val)).item())
        for x in xor_vals[1:]:
            prev_val = prev_val ^ x
            values.append(np.float64(np.uint64(prev_val)).item())

        return timestamps, values


class Simple8bEncoder(TimeSeriesEncoder):
    def __init__(self):
        self.name = "simple8b"

    def _pack_values(self, values: list[int]) -> tuple[list[int], int]:
        packed: list[int] = []
        i = 0
        while i < len(values):
            block = self._find_best_packing(values[i:])
            packed.append(block["encoded"])
            i += block["count"]
        return packed, len(values)

    def _find_best_packing(self, values: list[int]) -> dict[str, Any]:
        n = min(60, len(values))
        for bits in [1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 15, 20, 30, 60]:
            max_count = 60 // bits
            count = min(max_count, n)
            if count == 0:
                continue

            max_val = max(values[:count]) if count > 0 else 0
            if max_val < (1 << bits):
                selector = self._get_selector(bits)
                encoded = selector
                for j in range(count):
                    encoded |= (values[j] & ((1 << bits) - 1)) << (4 + j * bits)
                return {"encoded": encoded, "count": count}

        return {"encoded": values[0], "count": 1}

    def _get_selector(self, bits: int) -> int:
        selectors = {1: 0, 2: 1, 3: 2, 4: 3, 5: 4, 6: 5, 7: 6, 8: 7, 10: 8, 12: 9, 15: 10, 20: 11, 30: 12, 60: 13}
        return selectors.get(bits, 15)

    def encode(self, timestamps: list[int], values: list[float]) -> EncodedData:
        if not timestamps:
            return EncodedData(encoding_type=self.name, data=b"")

        delta_ts = [timestamps[0]]
        for i in range(1, len(timestamps)):
            delta_ts.append(timestamps[i] - timestamps[i - 1])

        int_vals = [int(v * 1000) for v in values]

        packed_ts, count_ts = self._pack_values(delta_ts)
        packed_vals, count_vals = self._pack_values(int_vals)

        ts_bytes = struct.pack(f"<{len(packed_ts)}Q", *packed_ts)
        val_bytes = struct.pack(f"<{len(packed_vals)}Q", *packed_vals)

        data = (
            struct.pack("<II", len(delta_ts), len(packed_ts))
            + ts_bytes
            + struct.pack("<I", len(packed_vals))
            + val_bytes
        )

        original_size = len(timestamps) * (8 + 8)
        compressed_size = len(data)

        return EncodedData(
            encoding_type=self.name,
            original_size=original_size,
            compressed_size=compressed_size,
            compression_ratio=original_size / compressed_size if compressed_size > 0 else 1.0,
            data=data,
            metadata={"count": len(timestamps)},
        )

    def decode(self, encoded: EncodedData) -> tuple[list[int], list[float]]:
        if not encoded.data:
            return [], []

        offset = 0
        count, packed_count = struct.unpack("<II", encoded.data[offset:offset + 8])
        offset += 8

        packed_ts = list(struct.unpack(f"<{packed_count}Q", encoded.data[offset:offset + packed_count * 8]))
        offset += packed_count * 8

        packed_val_count = struct.unpack("<I", encoded.data[offset:offset + 4])[0]
        offset += 4
        packed_vals = list(struct.unpack(f"<{packed_val_count}Q", encoded.data[offset:offset + packed_val_count * 8]))

        delta_ts = self._unpack_values(packed_ts, count)
        int_vals = self._unpack_values(packed_vals, count)

        timestamps = [delta_ts[0]]
        for d in delta_ts[1:]:
            timestamps.append(timestamps[-1] + d)

        values = [v / 1000.0 for v in int_vals]

        return timestamps, values

    def _unpack_values(self, packed: list[int], expected_count: int) -> list[int]:
        values: list[int] = []
        bits_table = [1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 15, 20, 30, 60]

        for word in packed:
            if len(values) >= expected_count:
                break
            selector = word & 0xF
            if selector < len(bits_table):
                bits = bits_table[selector]
                count = 60 // bits
                mask = (1 << bits) - 1
                for i in range(count):
                    if len(values) >= expected_count:
                        break
                    val = (word >> (4 + i * bits)) & mask
                    values.append(val)
            else:
                values.append(word >> 4)

        return values


class EncoderFactory:
    _encoders: dict[str, type[TimeSeriesEncoder]] = {
        "delta": DeltaEncoder,
        "gorilla": GorillaEncoder,
        "simple8b": Simple8bEncoder,
    }

    @classmethod
    def create(cls, encoder_type: str) -> TimeSeriesEncoder:
        encoder_cls = cls._encoders.get(encoder_type.lower())
        if not encoder_cls:
            raise ValueError(f"Unknown encoder type: {encoder_type}")
        return encoder_cls()

    @classmethod
    def get_available_encoders(cls) -> list[str]:
        return list(cls._encoders.keys())
