from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Optional, Union, Dict
import time
import numpy as np
import pandas as pd


@dataclass
class CompressionStats:
    algorithm: str
    original_size: int
    compressed_size: int
    compression_ratio: float
    encode_time_ms: float
    decode_time_ms: float
    extra: Dict[str, Any] = field(default_factory=dict)

    @property
    def space_saving(self) -> float:
        return 1.0 - (self.compressed_size / self.original_size) if self.original_size > 0 else 0.0


class Codec(ABC):
    @abstractmethod
    def name(self) -> str:
        pass

    @abstractmethod
    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        pass

    @abstractmethod
    def decode(self, encoded: bytes) -> np.ndarray:
        pass

    def encode_with_stats(
        self,
        data: Union[np.ndarray, pd.Series],
    ) -> tuple[bytes, CompressionStats]:
        original_size = _get_byte_size(data)
        start = time.perf_counter()
        encoded = self.encode(data)
        encode_time_ms = (time.perf_counter() - start) * 1000.0

        compressed_size = len(encoded)
        ratio = compressed_size / original_size if original_size > 0 else 1.0

        start = time.perf_counter()
        self.decode(encoded)
        decode_time_ms = (time.perf_counter() - start) * 1000.0

        stats = CompressionStats(
            algorithm=self.name(),
            original_size=original_size,
            compressed_size=compressed_size,
            compression_ratio=ratio,
            encode_time_ms=encode_time_ms,
            decode_time_ms=decode_time_ms,
        )
        return encoded, stats


class AutoCodec(Codec):
    def __init__(self, candidates: Optional[list[Codec]] = None):
        from .compressors import (
            DeltaCodec,
            XORCodec,
            GorillaCodec,
            LZ4Codec,
            ZSTDCodec,
            SnappyCodec,
            RunLengthCodec,
        )

        self.candidates = candidates or [
            DeltaCodec(),
            XORCodec(),
            GorillaCodec(),
            LZ4Codec(),
            ZSTDCodec(),
            SnappyCodec(),
            RunLengthCodec(),
        ]
        self._last_chosen: Optional[str] = None

    def name(self) -> str:
        return f"AutoCodec(last={self._last_chosen})"

    def _select_best(self, data: Union[np.ndarray, pd.Series]) -> Codec:
        sample = _to_numpy(data)
        if len(sample) > 1000:
            idx = np.linspace(0, len(sample) - 1, 1000, dtype=int)
            sample = sample[idx]

        best_score = float("inf")
        best_codec = self.candidates[0]

        for codec in self.candidates:
            try:
                _, stats = codec.encode_with_stats(sample)
                score = stats.compression_ratio * 0.7 + (stats.encode_time_ms / 1000.0) * 0.3
                if score < best_score:
                    best_score = score
                    best_codec = codec
            except Exception:
                continue

        self._last_chosen = best_codec.name()
        return best_codec

    def encode(self, data: Union[np.ndarray, pd.Series]) -> bytes:
        best = self._select_best(data)
        encoded = best.encode(data)
        name_bytes = best.name().encode("utf-8")
        return len(name_bytes).to_bytes(2, "big") + name_bytes + encoded

    def decode(self, encoded: bytes) -> np.ndarray:
        name_len = int.from_bytes(encoded[:2], "big")
        name = encoded[2 : 2 + name_len].decode("utf-8")
        payload = encoded[2 + name_len :]

        codec_map = {c.name(): c for c in self.candidates}
        if name not in codec_map:
            raise ValueError(f"Unknown codec: {name}")

        return codec_map[name].decode(payload)

    def encode_with_stats(
        self,
        data: Union[np.ndarray, pd.Series],
    ) -> tuple[bytes, CompressionStats]:
        original_size = _get_byte_size(data)
        start = time.perf_counter()
        encoded = self.encode(data)
        encode_time_ms = (time.perf_counter() - start) * 1000.0

        compressed_size = len(encoded)
        ratio = compressed_size / original_size if original_size > 0 else 1.0

        start = time.perf_counter()
        self.decode(encoded)
        decode_time_ms = (time.perf_counter() - start) * 1000.0

        stats = CompressionStats(
            algorithm=self.name(),
            original_size=original_size,
            compressed_size=compressed_size,
            compression_ratio=ratio,
            encode_time_ms=encode_time_ms,
            decode_time_ms=decode_time_ms,
            extra={"chosen_codec": self._last_chosen},
        )
        return encoded, stats


def _to_numpy(data: Union[np.ndarray, pd.Series, Any]) -> np.ndarray:
    if isinstance(data, pd.Series):
        return data.to_numpy()
    if isinstance(data, np.ndarray):
        return data
    return np.asarray(data)


def _get_byte_size(data: Union[np.ndarray, pd.Series, Any]) -> int:
    arr = _to_numpy(data)
    return arr.nbytes
