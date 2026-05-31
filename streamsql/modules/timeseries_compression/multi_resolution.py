from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional

import numpy as np

from streamsql.core.models import generate_id
from streamsql.modules.timeseries_compression.downsampler import (
    DownsampledData,
    DownsamplerFactory,
    TimeSeriesDownsampler,
)


class ResolutionLevel(str, Enum):
    RAW = "raw"
    SECOND = "second"
    MINUTE = "minute"
    HOUR = "hour"
    DAY = "day"
    WEEK = "week"
    MONTH = "month"


@dataclass
class ResolutionConfig:
    level: ResolutionLevel
    retention_seconds: int
    downsample_method: str = "lttb"
    target_interval_ms: int = 1000


@dataclass
class ResolutionData:
    level: ResolutionLevel
    timestamps: list[int] = field(default_factory=list)
    values: list[float] = field(default_factory=list)
    downsample_info: Optional[DownsampledData] = None

    def add(self, timestamp: int, value: float) -> None:
        self.timestamps.append(timestamp)
        self.values.append(value)

    def add_batch(self, timestamps: list[int], values: list[float]) -> None:
        self.timestamps.extend(timestamps)
        self.values.extend(values)

    def count(self) -> int:
        return len(self.timestamps)

    def to_dict(self) -> dict[str, Any]:
        return {
            "level": self.level.value,
            "count": self.count(),
            "time_range": {
                "start": self.timestamps[0] if self.timestamps else None,
                "end": self.timestamps[-1] if self.timestamps else None,
            },
            "stats": self._get_stats(),
        }

    def _get_stats(self) -> dict[str, float]:
        if not self.values:
            return {}
        vals = np.array(self.values)
        return {
            "min": float(vals.min()),
            "max": float(vals.max()),
            "mean": float(vals.mean()),
            "std": float(vals.std()),
        }


class MultiResolutionStorage:
    def __init__(
        self,
        resolutions: Optional[list[ResolutionConfig]] = None,
        auto_downsample: bool = True,
    ):
        self.auto_downsample = auto_downsample
        self.resolutions: dict[ResolutionLevel, ResolutionData] = {}
        self.configs: dict[ResolutionLevel, ResolutionConfig] = {}

        if resolutions is None:
            resolutions = [
                ResolutionConfig(
                    level=ResolutionLevel.RAW,
                    retention_seconds=3600,
                    target_interval_ms=100,
                ),
                ResolutionConfig(
                    level=ResolutionLevel.SECOND,
                    retention_seconds=3600 * 24,
                    downsample_method="mean",
                    target_interval_ms=1000,
                ),
                ResolutionConfig(
                    level=ResolutionLevel.MINUTE,
                    retention_seconds=3600 * 24 * 7,
                    downsample_method="mean",
                    target_interval_ms=60000,
                ),
                ResolutionConfig(
                    level=ResolutionLevel.HOUR,
                    retention_seconds=3600 * 24 * 30,
                    downsample_method="lttb",
                    target_interval_ms=3600000,
                ),
                ResolutionConfig(
                    level=ResolutionLevel.DAY,
                    retention_seconds=3600 * 24 * 365,
                    downsample_method="minmax",
                    target_interval_ms=86400000,
                ),
            ]

        for cfg in resolutions:
            self.configs[cfg.level] = cfg
            self.resolutions[cfg.level] = ResolutionData(level=cfg.level)

    def add(self, timestamp: int, value: float) -> None:
        if ResolutionLevel.RAW in self.resolutions:
            self.resolutions[ResolutionLevel.RAW].add(timestamp, value)

        if self.auto_downsample:
            self._downsample_to_levels(timestamp, value)

    def add_batch(self, timestamps: list[int], values: list[float]) -> None:
        if len(timestamps) != len(values):
            raise ValueError("Timestamps and values must have the same length")

        if ResolutionLevel.RAW in self.resolutions:
            self.resolutions[ResolutionLevel.RAW].add_batch(timestamps, values)

        if self.auto_downsample:
            for ts, v in zip(timestamps, values):
                self._downsample_to_levels(ts, v)

    def _downsample_to_levels(self, timestamp: int, value: float) -> None:
        for level, cfg in self.configs.items():
            if level == ResolutionLevel.RAW:
                continue

            level_data = self.resolutions[level]
            interval = cfg.target_interval_ms

            if not level_data.timestamps:
                level_data.add(timestamp, value)
                continue

            last_ts = level_data.timestamps[-1]
            if timestamp - last_ts >= interval:
                level_data.add(timestamp, value)

    def get_resolution(self, level: ResolutionLevel) -> ResolutionData:
        if level not in self.resolutions:
            raise ValueError(f"Resolution level {level} not found")
        return self.resolutions[level]

    def query(
        self,
        start_time: Optional[int] = None,
        end_time: Optional[int] = None,
        preferred_level: Optional[ResolutionLevel] = None,
    ) -> ResolutionData:
        level = preferred_level or self._select_level(start_time, end_time)
        data = self.resolutions[level]

        result = ResolutionData(level=level)

        if not data.timestamps:
            return result

        timestamps = np.array(data.timestamps)
        values = np.array(data.values)

        mask = np.ones_like(timestamps, dtype=bool)
        if start_time is not None:
            mask &= timestamps >= start_time
        if end_time is not None:
            mask &= timestamps <= end_time

        result.timestamps = timestamps[mask].tolist()
        result.values = values[mask].tolist()

        return result

    def _select_level(
        self, start_time: Optional[int], end_time: Optional[int]
    ) -> ResolutionLevel:
        if start_time is None or end_time is None:
            return ResolutionLevel.RAW

        duration = end_time - start_time

        thresholds = [
            (ResolutionLevel.RAW, 3600000),
            (ResolutionLevel.SECOND, 86400000),
            (ResolutionLevel.MINUTE, 604800000),
            (ResolutionLevel.HOUR, 2592000000),
        ]

        for level, threshold in thresholds:
            if duration <= threshold:
                return level

        return ResolutionLevel.DAY

    def downsample_to_level(
        self, source_level: ResolutionLevel, target_level: ResolutionLevel
    ) -> None:
        if target_level not in self.configs:
            raise ValueError(f"Target level {target_level} not configured")

        source = self.resolutions[source_level]
        if source.count() == 0:
            return

        cfg = self.configs[target_level]
        target_count = max(10, source.count() // 10)

        downsampler = DownsamplerFactory.create(cfg.downsample_method)
        result = downsampler.downsample(source.timestamps, source.values, target_count)

        target_data = self.resolutions[target_level]
        target_data.timestamps = result.timestamps
        target_data.values = result.values
        target_data.downsample_info = result

    def compact(self, current_time_ms: int) -> dict[str, Any]:
        removed: dict[str, int] = {}

        for level, cfg in self.configs.items():
            data = self.resolutions[level]
            cutoff = current_time_ms - cfg.retention_seconds * 1000

            if data.timestamps:
                keep_from = 0
                for i, ts in enumerate(data.timestamps):
                    if ts >= cutoff:
                        keep_from = i
                        break

                if keep_from > 0:
                    removed_count = keep_from
                    data.timestamps = data.timestamps[keep_from:]
                    data.values = data.values[keep_from:]
                    removed[level.value] = removed_count

        return {"removed": removed, "total_removed": sum(removed.values())}

    def get_summary(self) -> dict[str, Any]:
        return {
            "resolutions": {
                level.value: data.to_dict()
                for level, data in self.resolutions.items()
            },
            "auto_downsample": self.auto_downsample,
        }

    def merge(self, other: "MultiResolutionStorage") -> None:
        for level in self.resolutions:
            if level in other.resolutions:
                self.resolutions[level].add_batch(
                    other.resolutions[level].timestamps,
                    other.resolutions[level].values,
                )
