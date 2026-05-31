from typing import Union
import numpy as np
import pandas as pd
import msgpack
import lz4.frame
import zstandard as zstd
import snappy

from .codec import Codec, _to_numpy


class DeltaCodec(Codec):
    def name(self) -> str:
        return "delta"

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        arr = _to_numpy(data).astype(np.float64)
        if len(arr) == 0:
            return b""
        deltas = np.diff(arr, prepend=arr[0])
        header = np.array([len(arr), arr[0]], dtype=np.float64)
        payload = np.concatenate([header, deltas[1:]])
        return payload.tobytes()

    def decode(self, encoded: bytes) -> np.ndarray:
        if len(encoded) == 0:
            return np.array([], dtype=np.float64)
        arr = np.frombuffer(encoded, dtype=np.float64)
        length = int(arr[0])
        first_val = arr[1]
        deltas = arr[2:]
        result = np.cumsum(np.concatenate([[first_val], deltas]))
        return result[:length]


class XORCodec(Codec):
    def name(self) -> str:
        return "xor"

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        arr = _to_numpy(data).astype(np.float64)
        if len(arr) == 0:
            return b""
        int_view = arr.view(np.uint64)
        xors = np.zeros_like(int_view)
        xors[0] = int_view[0]
        for i in range(1, len(int_view)):
            xors[i] = int_view[i] ^ int_view[i - 1]
        header = np.array([len(arr)], dtype=np.uint64)
        payload = np.concatenate([header, xors])
        return payload.tobytes()

    def decode(self, encoded: bytes) -> np.ndarray:
        if len(encoded) == 0:
            return np.array([], dtype=np.float64)
        arr = np.frombuffer(encoded, dtype=np.uint64)
        length = int(arr[0])
        xors = arr[1 : 1 + length]
        result = np.zeros_like(xors, dtype=np.uint64)
        result[0] = xors[0]
        for i in range(1, len(xors)):
            result[i] = result[i - 1] ^ xors[i]
        return result.view(np.float64)


class GorillaCodec(Codec):
    def name(self) -> str:
        return "gorilla"

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        arr = _to_numpy(data).astype(np.float64)
        if len(arr) == 0:
            return msgpack.packb({"len": 0, "data": []})

        int_view = arr.view(np.uint64)
        prev = int_view[0]
        prev_leading = 0xFF
        prev_trailing = 0

        blocks = []
        blocks.append(int(prev))

        for i in range(1, len(int_view)):
            curr = int_view[i]
            xored = int(curr) ^ int(prev)

            if xored == 0:
                blocks.append(0)
            else:
                leading = format(xored, "064b").index("1")
                trailing = format(xored, "064b")[::-1].index("1")
                meaningful = 64 - leading - trailing

                if (
                    leading >= prev_leading
                    and trailing >= prev_trailing
                    and meaningful == (64 - prev_leading - prev_trailing)
                ):
                    control = 1
                    value = xored >> prev_trailing
                    blocks.append((control, prev_leading, prev_trailing, value, meaningful))
                else:
                    control = 2
                    value = xored >> trailing
                    blocks.append((control, leading, trailing, value, meaningful))
                    prev_leading = leading
                    prev_trailing = trailing

            prev = curr

        return msgpack.packb({"len": len(arr), "data": blocks})

    def decode(self, encoded: bytes) -> np.ndarray:
        unpacked = msgpack.unpackb(encoded)
        length = unpacked["len"]
        if length == 0:
            return np.array([], dtype=np.float64)

        blocks = unpacked["data"]
        result = np.zeros(length, dtype=np.uint64)
        prev = int(blocks[0])
        result[0] = prev
        prev_leading = 0xFF
        prev_trailing = 0

        idx = 1
        for block in blocks[1:]:
            if block == 0:
                result[idx] = prev
            else:
                control, leading, trailing, value, meaningful = block
                if control == 1:
                    leading = prev_leading
                    trailing = prev_trailing
                else:
                    prev_leading = leading
                    prev_trailing = trailing

                xored = value << trailing
                result[idx] = prev ^ xored

            prev = int(result[idx])
            idx += 1

        return result.view(np.float64)


class LZ4Codec(Codec):
    def __init__(self, compression_level: int = 9):
        self.compression_level = compression_level

    def name(self) -> str:
        return f"lz4_{self.compression_level}"

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        arr = _to_numpy(data).astype(np.float64)
        raw = arr.tobytes()
        return lz4.frame.compress(raw, compression_level=self.compression_level)

    def decode(self, encoded: bytes) -> np.ndarray:
        raw = lz4.frame.decompress(encoded)
        return np.frombuffer(raw, dtype=np.float64)


class ZSTDCodec(Codec):
    def __init__(self, compression_level: int = 3):
        self.compression_level = compression_level
        self._cctx = zstd.ZstdCompressor(level=compression_level)
        self._dctx = zstd.ZstdDecompressor()

    def name(self) -> str:
        return f"zstd_{self.compression_level}"

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        arr = _to_numpy(data).astype(np.float64)
        raw = arr.tobytes()
        return self._cctx.compress(raw)

    def decode(self, encoded: bytes) -> np.ndarray:
        raw = self._dctx.decompress(encoded)
        return np.frombuffer(raw, dtype=np.float64)


class SnappyCodec(Codec):
    def name(self) -> str:
        return "snappy"

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        arr = _to_numpy(data).astype(np.float64)
        raw = arr.tobytes()
        return snappy.compress(raw)

    def decode(self, encoded: bytes) -> np.ndarray:
        raw = snappy.decompress(encoded)
        return np.frombuffer(raw, dtype=np.float64)


class RunLengthCodec(Codec):
    def name(self) -> str:
        return "rle"

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        arr = _to_numpy(data).astype(np.float64)
        if len(arr) == 0:
            return msgpack.packb({"len": 0, "runs": []})

        runs = []
        current_val = arr[0]
        count = 1

        for val in arr[1:]:
            if np.isclose(val, current_val):
                count += 1
            else:
                runs.append((float(current_val), count))
                current_val = val
                count = 1

        runs.append((float(current_val), count))
        return msgpack.packb({"len": len(arr), "runs": runs})

    def decode(self, encoded: bytes) -> np.ndarray:
        unpacked = msgpack.unpackb(encoded)
        length = unpacked["len"]
        if length == 0:
            return np.array([], dtype=np.float64)

        runs = unpacked["runs"]
        result = np.zeros(length, dtype=np.float64)
        pos = 0

        for val, count in runs:
            result[pos : pos + count] = val
            pos += count

        return result
