from abc import ABC, abstractmethod
from typing import Union, Optional, Tuple
import numpy as np
import pandas as pd

from .codec import _to_numpy


class Downsampler(ABC):
    @abstractmethod
    def name(self) -> str:
        pass

    @abstractmethod
    def downsample(
        self,
        timestamps: Union[np.ndarray, pd.Series],
        values: Union[np.ndarray, pd.Series],
        target_size: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        pass


class MeanDownsampler(Downsampler):
    def name(self) -> str:
        return "mean"

    def downsample(
        self,
        timestamps: Union[np.ndarray, pd.Series],
        values: Union[np.ndarray, pd.Series],
        target_size: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        ts = _to_numpy(timestamps)
        vals = _to_numpy(values)

        if len(ts) <= target_size:
            return ts, vals

        bucket_size = len(ts) / target_size
        ds_ts = np.zeros(target_size)
        ds_vals = np.zeros(target_size)

        for i in range(target_size):
            start = int(i * bucket_size)
            end = int((i + 1) * bucket_size)
            if end > len(ts):
                end = len(ts)
            if start >= end:
                end = start + 1
            ds_ts[i] = ts[start:end].mean()
            ds_vals[i] = vals[start:end].mean()

        return ds_ts, ds_vals


class MaxDownsampler(Downsampler):
    def name(self) -> str:
        return "max"

    def downsample(
        self,
        timestamps: Union[np.ndarray, pd.Series],
        values: Union[np.ndarray, pd.Series],
        target_size: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        ts = _to_numpy(timestamps)
        vals = _to_numpy(values)

        if len(ts) <= target_size:
            return ts, vals

        bucket_size = len(ts) / target_size
        ds_ts = np.zeros(target_size)
        ds_vals = np.zeros(target_size)

        for i in range(target_size):
            start = int(i * bucket_size)
            end = int((i + 1) * bucket_size)
            if end > len(ts):
                end = len(ts)
            if start >= end:
                end = start + 1
            idx = np.argmax(vals[start:end])
            ds_ts[i] = ts[start + idx]
            ds_vals[i] = vals[start + idx]

        return ds_ts, ds_vals


class MinDownsampler(Downsampler):
    def name(self) -> str:
        return "min"

    def downsample(
        self,
        timestamps: Union[np.ndarray, pd.Series],
        values: Union[np.ndarray, pd.Series],
        target_size: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        ts = _to_numpy(timestamps)
        vals = _to_numpy(values)

        if len(ts) <= target_size:
            return ts, vals

        bucket_size = len(ts) / target_size
        ds_ts = np.zeros(target_size)
        ds_vals = np.zeros(target_size)

        for i in range(target_size):
            start = int(i * bucket_size)
            end = int((i + 1) * bucket_size)
            if end > len(ts):
                end = len(ts)
            if start >= end:
                end = start + 1
            idx = np.argmin(vals[start:end])
            ds_ts[i] = ts[start + idx]
            ds_vals[i] = vals[start + idx]

        return ds_ts, ds_vals


class LTTBDownsampler(Downsampler):
    def name(self) -> str:
        return "lttb"

    def downsample(
        self,
        timestamps: Union[np.ndarray, pd.Series],
        values: Union[np.ndarray, pd.Series],
        target_size: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        ts = _to_numpy(timestamps).astype(np.float64)
        vals = _to_numpy(values).astype(np.float64)

        if len(ts) <= target_size or target_size < 3:
            return ts, vals

        sampled_indices = [0]
        bucket_size = (len(ts) - 2) / (target_size - 2)

        for i in range(target_size - 2):
            avg_range_start = int((i + 1) * bucket_size) + 1
            avg_range_end = int((i + 2) * bucket_size) + 1

            if avg_range_end > len(ts):
                avg_range_end = len(ts)

            avg_x = np.mean(ts[avg_range_start:avg_range_end])
            avg_y = np.mean(vals[avg_range_start:avg_range_end])

            range_offs = int(i * bucket_size) + 1
            range_to = int((i + 1) * bucket_size) + 1

            if range_to > len(ts):
                range_to = len(ts)

            point_a_x = ts[sampled_indices[-1]]
            point_a_y = vals[sampled_indices[-1]]

            max_area = -1.0
            next_point_index = range_offs

            for j in range(range_offs, range_to):
                area = abs(
                    (point_a_x - avg_x) * (vals[j] - point_a_y)
                    - (point_a_x - ts[j]) * (avg_y - point_a_y)
                )
                if area > max_area:
                    max_area = area
                    next_point_index = j

            sampled_indices.append(next_point_index)

        sampled_indices.append(len(ts) - 1)

        return ts[sampled_indices], vals[sampled_indices]


class M4Downsampler(Downsampler):
    def name(self) -> str:
        return "m4"

    def downsample(
        self,
        timestamps: Union[np.ndarray, pd.Series],
        values: Union[np.ndarray, pd.Series],
        target_size: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        ts = _to_numpy(timestamps)
        vals = _to_numpy(values)

        if len(ts) <= target_size * 4:
            return ts, vals

        bucket_size = len(ts) / target_size
        result_ts = []
        result_vals = []

        for i in range(target_size):
            start = int(i * bucket_size)
            end = int((i + 1) * bucket_size)
            if end > len(ts):
                end = len(ts)
            if start >= end:
                end = start + 1

            bucket_ts = ts[start:end]
            bucket_vals = vals[start:end]

            if len(bucket_vals) == 0:
                continue

            min_idx = np.argmin(bucket_vals)
            max_idx = np.argmax(bucket_vals)
            first = 0
            last = len(bucket_vals) - 1

            indices = sorted([first, min(min_idx, max_idx), max(min_idx, max_idx), last])
            indices = list(dict.fromkeys(indices))

            for idx in indices:
                result_ts.append(bucket_ts[idx])
                result_vals.append(bucket_vals[idx])

        return np.array(result_ts), np.array(result_vals)


class RandomSampler(Downsampler):
    def __init__(self, seed: Optional[int] = None):
        self.seed = seed

    def name(self) -> str:
        return "random"

    def downsample(
        self,
        timestamps: Union[np.ndarray, pd.Series],
        values: Union[np.ndarray, pd.Series],
        target_size: int,
    ) -> Tuple[np.ndarray, np.ndarray]:
        ts = _to_numpy(timestamps)
        vals = _to_numpy(values)

        if len(ts) <= target_size:
            return ts, vals

        rng = np.random.RandomState(self.seed)
        indices = np.sort(rng.choice(len(ts), size=target_size, replace=False))

        return ts[indices], vals[indices]
