from dataclasses import dataclass, field
from typing import Optional, Union, List, Dict, Any, Callable
from enum import Enum
import numpy as np
import pandas as pd

from .codec import Codec, AutoCodec, _to_numpy
from .downsampler import Downsampler


class MissingValueStrategy(str, Enum):
    FORWARD_FILL = "ffill"
    BACKWARD_FILL = "bfill"
    LINEAR = "linear"
    ZERO = "zero"
    MEAN = "mean"
    DROP = "drop"


@dataclass
class TimeSeries:
    timestamps: np.ndarray
    values: np.ndarray
    name: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    _codec: Codec = field(default_factory=AutoCodec)
    _compressed_cache: Optional[bytes] = None
    _stats_cache: Optional[Any] = None

    def __post_init__(self):
        self.timestamps = _to_numpy(self.timestamps).astype(np.float64)
        self.values = _to_numpy(self.values).astype(np.float64)
        if len(self.timestamps) != len(self.values):
            raise ValueError(
                f"Timestamps and values length mismatch: {len(self.timestamps)} vs {len(self.values)}"
            )
        sort_idx = np.argsort(self.timestamps)
        self.timestamps = self.timestamps[sort_idx]
        self.values = self.values[sort_idx]

    def __len__(self) -> int:
        return len(self.timestamps)

    @property
    def start_time(self) -> float:
        return self.timestamps[0] if len(self.timestamps) > 0 else 0.0

    @property
    def end_time(self) -> float:
        return self.timestamps[-1] if len(self.timestamps) > 0 else 0.0

    @property
    def duration(self) -> float:
        return self.end_time - self.start_time

    @property
    def sampling_interval(self) -> Optional[float]:
        if len(self.timestamps) < 2:
            return None
        diffs = np.diff(self.timestamps)
        return float(np.mean(diffs))

    def to_pandas(self) -> pd.Series:
        idx = pd.to_datetime(self.timestamps, unit="s")
        return pd.Series(self.values, index=idx, name=self.name)

    @classmethod
    def from_pandas(cls, series: pd.Series, name: Optional[str] = None) -> "TimeSeries":
        timestamps = series.index.astype("int64").to_numpy() / 1e9
        values = series.to_numpy()
        return cls(
            timestamps=timestamps,
            values=values,
            name=name or series.name,
        )

    def align_to_frequency(self, freq_seconds: float, strategy: MissingValueStrategy = MissingValueStrategy.LINEAR) -> "TimeSeries":
        if len(self.timestamps) == 0:
            return self

        start = np.floor(self.timestamps[0] / freq_seconds) * freq_seconds
        end = np.ceil(self.timestamps[-1] / freq_seconds) * freq_seconds
        aligned_ts = np.arange(start, end + freq_seconds, freq_seconds)

        aligned_vals = self._interpolate(aligned_ts, strategy)

        return TimeSeries(
            timestamps=aligned_ts,
            values=aligned_vals,
            name=self.name,
            metadata={**self.metadata, "aligned_freq": freq_seconds},
            _codec=self._codec,
        )

    def fill_missing(self, strategy: MissingValueStrategy = MissingValueStrategy.LINEAR) -> "TimeSeries":
        mask = np.isnan(self.values)
        if not mask.any():
            return self

        if strategy == MissingValueStrategy.DROP:
            valid_idx = ~mask
            return TimeSeries(
                timestamps=self.timestamps[valid_idx],
                values=self.values[valid_idx],
                name=self.name,
                metadata={**self.metadata, "dropped_nan": int(mask.sum())},
                _codec=self._codec,
            )

        filled_vals = self.values.copy()
        valid_idx = ~mask

        if strategy == MissingValueStrategy.FORWARD_FILL:
            for i in range(len(filled_vals)):
                if mask[i] and i > 0:
                    filled_vals[i] = filled_vals[i - 1]
        elif strategy == MissingValueStrategy.BACKWARD_FILL:
            for i in range(len(filled_vals) - 1, -1, -1):
                if mask[i] and i < len(filled_vals) - 1:
                    filled_vals[i] = filled_vals[i + 1]
        elif strategy == MissingValueStrategy.ZERO:
            filled_vals[mask] = 0.0
        elif strategy == MissingValueStrategy.MEAN:
            mean_val = np.nanmean(self.values)
            filled_vals[mask] = mean_val
        elif strategy == MissingValueStrategy.LINEAR:
            if len(valid_idx.nonzero()[0]) >= 2:
                filled_vals = np.interp(
                    self.timestamps,
                    self.timestamps[valid_idx],
                    self.values[valid_idx],
                )

        return TimeSeries(
            timestamps=self.timestamps,
            values=filled_vals,
            name=self.name,
            metadata={**self.metadata, "filled_nan": int(mask.sum()), "fill_strategy": strategy},
            _codec=self._codec,
        )

    def split(self, chunk_size: int) -> List["TimeSeries"]:
        if chunk_size <= 0:
            raise ValueError("chunk_size must be positive")

        chunks = []
        for i in range(0, len(self), chunk_size):
            end = min(i + chunk_size, len(self))
            chunks.append(
                TimeSeries(
                    timestamps=self.timestamps[i:end],
                    values=self.values[i:end],
                    name=self.name,
                    metadata={**self.metadata, "chunk_index": i // chunk_size},
                    _codec=self._codec,
                )
            )
        return chunks

    def split_by_time(self, interval_seconds: float) -> List["TimeSeries"]:
        if len(self) == 0:
            return []

        chunks = []
        start_time = self.timestamps[0]
        current_chunk_start = 0

        for i in range(len(self.timestamps)):
            if self.timestamps[i] - start_time >= interval_seconds:
                chunks.append(
                    TimeSeries(
                        timestamps=self.timestamps[current_chunk_start:i],
                        values=self.values[current_chunk_start:i],
                        name=self.name,
                        metadata={
                            **self.metadata,
                            "chunk_start": self.timestamps[current_chunk_start],
                            "chunk_end": self.timestamps[i - 1],
                        },
                        _codec=self._codec,
                    )
                )
                start_time = self.timestamps[i]
                current_chunk_start = i

        if current_chunk_start < len(self):
            chunks.append(
                TimeSeries(
                    timestamps=self.timestamps[current_chunk_start:],
                    values=self.values[current_chunk_start:],
                    name=self.name,
                    metadata={
                        **self.metadata,
                        "chunk_start": self.timestamps[current_chunk_start],
                        "chunk_end": self.timestamps[-1],
                    },
                    _codec=self._codec,
                )
            )

        return chunks

    def compress(self, codec: Optional[Codec] = None) -> bytes:
        if codec is None:
            codec = self._codec
        self._compressed_cache, self._stats_cache = codec.encode_with_stats(self.values)
        return self._compressed_cache

    def decompress(self, encoded: Optional[bytes] = None) -> np.ndarray:
        if encoded is None:
            if self._compressed_cache is None:
                raise ValueError("No compressed data available. Call compress() first.")
            encoded = self._compressed_cache
        return self._codec.decode(encoded)

    def downsample(self, downsampler: Downsampler, target_size: int) -> "TimeSeries":
        ds_ts, ds_vals = downsampler.downsample(self.timestamps, self.values, target_size)
        return TimeSeries(
            timestamps=ds_ts,
            values=ds_vals,
            name=self.name,
            metadata={
                **self.metadata,
                "downsampler": downsampler.name(),
                "original_size": len(self),
                "target_size": target_size,
            },
            _codec=self._codec,
        )

    def slice(self, start_time: Optional[float] = None, end_time: Optional[float] = None) -> "TimeSeries":
        mask = np.ones(len(self.timestamps), dtype=bool)
        if start_time is not None:
            mask &= self.timestamps >= start_time
        if end_time is not None:
            mask &= self.timestamps <= end_time

        return TimeSeries(
            timestamps=self.timestamps[mask],
            values=self.values[mask],
            name=self.name,
            metadata={**self.metadata, "sliced": True, "start": start_time, "end": end_time},
            _codec=self._codec,
        )

    def _interpolate(self, target_ts: np.ndarray, strategy: MissingValueStrategy) -> np.ndarray:
        if len(self.values) == 0:
            return np.zeros_like(target_ts)

        if strategy == MissingValueStrategy.LINEAR:
            return np.interp(target_ts, self.timestamps, self.values, left=np.nan, right=np.nan)
        elif strategy == MissingValueStrategy.FORWARD_FILL:
            result = np.zeros_like(target_ts)
            for i, t in enumerate(target_ts):
                idx = np.searchsorted(self.timestamps, t, side="right") - 1
                if idx >= 0:
                    result[i] = self.values[idx]
                else:
                    result[i] = np.nan
            return result
        elif strategy == MissingValueStrategy.BACKWARD_FILL:
            result = np.zeros_like(target_ts)
            for i, t in enumerate(target_ts):
                idx = np.searchsorted(self.timestamps, t, side="left")
                if idx < len(self.timestamps):
                    result[i] = self.values[idx]
                else:
                    result[i] = np.nan
            return result
        else:
            return np.interp(target_ts, self.timestamps, self.values, left=np.nan, right=np.nan)
