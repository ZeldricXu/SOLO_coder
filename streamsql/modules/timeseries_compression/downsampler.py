from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any

import numpy as np

from streamsql.core.models import generate_id


@dataclass
class DownsampledData:
    downsample_id: str = field(default_factory=lambda: generate_id("ds"))
    original_count: int = 0
    downsampled_count: int = 0
    ratio: float = 0.0
    timestamps: list[int] = field(default_factory=list)
    values: list[float] = field(default_factory=list)
    method: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)


class TimeSeriesDownsampler(ABC):
    @abstractmethod
    def downsample(
        self, timestamps: list[int], values: list[float], target_count: int
    ) -> DownsampledData: ...


class MeanDownsampler(TimeSeriesDownsampler):
    def __init__(self):
        self.name = "mean"

    def downsample(
        self, timestamps: list[int], values: list[float], target_count: int
    ) -> DownsampledData:
        if len(timestamps) <= target_count or target_count <= 0:
            return DownsampledData(
                original_count=len(timestamps),
                downsampled_count=len(timestamps),
                ratio=1.0,
                timestamps=timestamps,
                values=values,
                method=self.name,
            )

        bucket_size = len(timestamps) / target_count
        ds_timestamps: list[int] = []
        ds_values: list[float] = []

        for i in range(target_count):
            start = int(i * bucket_size)
            end = int((i + 1) * bucket_size)
            if start >= len(timestamps):
                break
            end = min(end, len(timestamps))

            bucket_ts = timestamps[start:end]
            bucket_vals = values[start:end]

            ds_timestamps.append(bucket_ts[len(bucket_ts) // 2])
            ds_values.append(float(np.mean(bucket_vals)))

        return DownsampledData(
            original_count=len(timestamps),
            downsampled_count=len(ds_timestamps),
            ratio=len(ds_timestamps) / len(timestamps),
            timestamps=ds_timestamps,
            values=ds_values,
            method=self.name,
            metadata={"bucket_size": bucket_size},
        )


class MinMaxDownsampler(TimeSeriesDownsampler):
    def __init__(self):
        self.name = "minmax"

    def downsample(
        self, timestamps: list[int], values: list[float], target_count: int
    ) -> DownsampledData:
        if len(timestamps) <= target_count or target_count <= 0:
            return DownsampledData(
                original_count=len(timestamps),
                downsampled_count=len(timestamps),
                ratio=1.0,
                timestamps=timestamps,
                values=values,
                method=self.name,
            )

        bucket_size = max(1, len(timestamps) // target_count)
        ds_timestamps: list[int] = []
        ds_values: list[float] = []

        for i in range(0, len(timestamps), bucket_size):
            end = min(i + bucket_size, len(timestamps))
            bucket_ts = timestamps[i:end]
            bucket_vals = values[i:end]

            min_idx = int(np.argmin(bucket_vals))
            max_idx = int(np.argmax(bucket_vals))

            if min_idx < max_idx:
                ds_timestamps.append(bucket_ts[min_idx])
                ds_values.append(bucket_vals[min_idx])
                ds_timestamps.append(bucket_ts[max_idx])
                ds_values.append(bucket_vals[max_idx])
            else:
                ds_timestamps.append(bucket_ts[max_idx])
                ds_values.append(bucket_vals[max_idx])
                ds_timestamps.append(bucket_ts[min_idx])
                ds_values.append(bucket_vals[min_idx])

        return DownsampledData(
            original_count=len(timestamps),
            downsampled_count=len(ds_timestamps),
            ratio=len(ds_timestamps) / len(timestamps),
            timestamps=ds_timestamps,
            values=ds_values,
            method=self.name,
            metadata={"bucket_size": bucket_size},
        )


class LTTBDownsampler(TimeSeriesDownsampler):
    def __init__(self):
        self.name = "lttb"

    def downsample(
        self, timestamps: list[int], values: list[float], target_count: int
    ) -> DownsampledData:
        if len(timestamps) <= target_count or target_count <= 0:
            return DownsampledData(
                original_count=len(timestamps),
                downsampled_count=len(timestamps),
                ratio=1.0,
                timestamps=timestamps,
                values=values,
                method=self.name,
            )

        if target_count < 3:
            return DownsampledData(
                original_count=len(timestamps),
                downsampled_count=len(timestamps),
                ratio=1.0,
                timestamps=timestamps,
                values=values,
                method=self.name,
            )

        data_len = len(timestamps)
        sampled_idx: list[int] = [0]

        bucket_size = (data_len - 2) / (target_count - 2)

        for i in range(target_count - 2):
            avg_range_start = int(i * bucket_size) + 1
            avg_range_end = int((i + 1) * bucket_size) + 1
            avg_range_end = min(avg_range_end, data_len)

            avg_range_length = avg_range_end - avg_range_start

            avg_x = 0.0
            avg_y = 0.0
            for j in range(avg_range_start, avg_range_end):
                avg_x += timestamps[j]
                avg_y += values[j]
            avg_x /= avg_range_length
            avg_y /= avg_range_length

            range_offs = int(i * bucket_size) + 1
            range_to = int((i + 1) * bucket_size) + 1

            point_a_x = timestamps[sampled_idx[-1]]
            point_a_y = values[sampled_idx[-1]]

            max_area = -1.0
            next_sample = range_offs

            for j in range(range_offs, range_to):
                area = abs(
                    (point_a_x - avg_x) * (values[j] - point_a_y)
                    - (point_a_x - timestamps[j]) * (avg_y - point_a_y)
                )
                area /= 2.0
                if area > max_area:
                    max_area = area
                    next_sample = j

            sampled_idx.append(next_sample)

        sampled_idx.append(data_len - 1)

        ds_timestamps = [timestamps[i] for i in sampled_idx]
        ds_values = [values[i] for i in sampled_idx]

        return DownsampledData(
            original_count=len(timestamps),
            downsampled_count=len(ds_timestamps),
            ratio=len(ds_timestamps) / len(timestamps),
            timestamps=ds_timestamps,
            values=ds_values,
            method=self.name,
            metadata={"bucket_size": bucket_size},
        )


class DownsamplerFactory:
    _downsamplers: dict[str, type[TimeSeriesDownsampler]] = {
        "mean": MeanDownsampler,
        "minmax": MinMaxDownsampler,
        "lttb": LTTBDownsampler,
    }

    @classmethod
    def create(cls, downsampler_type: str) -> TimeSeriesDownsampler:
        ds_cls = cls._downsamplers.get(downsampler_type.lower())
        if not ds_cls:
            raise ValueError(f"Unknown downsampler type: {downsampler_type}")
        return ds_cls()

    @classmethod
    def get_available_downsamplers(cls) -> list[str]:
        return list(cls._downsamplers.keys())
