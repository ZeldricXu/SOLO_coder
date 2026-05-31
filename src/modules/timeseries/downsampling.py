"""Time series data downsampling strategies."""
from __future__ import annotations

from enum import Enum
from typing import Any, Callable, Dict, List, Optional

import numpy as np

from ...domain.models.common import TimeSeriesDataPoint
from ...infrastructure.logging.structured_logger import LogManager


class DownsamplingMethod(str, Enum):
    AVERAGE = "average"
    MEDIAN = "median"
    MIN = "min"
    MAX = "max"
    FIRST = "first"
    LAST = "last"
    SUM = "sum"
    LTTB = "lttb"
    M4 = "m4"


class DownsamplingEngine:
    def __init__(self, default_method: DownsamplingMethod = DownsamplingMethod.AVERAGE) -> None:
        self._default_method = default_method
        self._logger = LogManager().get_logger(__name__)
        self._methods: Dict[DownsamplingMethod, Callable] = {
            DownsamplingMethod.AVERAGE: self._downsample_average,
            DownsamplingMethod.MEDIAN: self._downsample_median,
            DownsamplingMethod.MIN: self._downsample_min,
            DownsamplingMethod.MAX: self._downsample_max,
            DownsamplingMethod.FIRST: self._downsample_first,
            DownsamplingMethod.LAST: self._downsample_last,
            DownsamplingMethod.SUM: self._downsample_sum,
            DownsamplingMethod.LTTB: self._downsample_lttb,
            DownsamplingMethod.M4: self._downsample_m4,
        }

    async def downsample(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        method: Optional[DownsamplingMethod] = None,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        if not data_points:
            return []

        if target_points >= len(data_points):
            return data_points.copy()

        if target_points < 2:
            target_points = 2

        downsample_method = method or self._default_method
        downsampler = self._methods.get(downsample_method)

        if not downsampler:
            raise ValueError(f"Unsupported downsampling method: {downsample_method}")

        self._logger.debug(
            f"Downsampling {len(data_points)} points to {target_points} using {downsample_method.value}"
        )

        result = downsampler(data_points, target_points, **kwargs)

        self._logger.debug(
            f"Downsampling completed: {len(data_points)} -> {len(result)} points"
        )

        return result

    def _extract_numeric_values(self, data_points: List[TimeSeriesDataPoint]) -> np.ndarray:
        values = []
        for dp in data_points:
            if isinstance(dp.value, (int, float)):
                values.append(float(dp.value))
            else:
                values.append(0.0)
        return np.array(values)

    def _create_downsampled_points(
        self,
        original_points: List[TimeSeriesDataPoint],
        indices: np.ndarray,
        values: np.ndarray,
    ) -> List[TimeSeriesDataPoint]:
        result: List[TimeSeriesDataPoint] = []
        for i, idx in enumerate(indices.astype(int)):
            if 0 <= idx < len(original_points):
                result.append(TimeSeriesDataPoint(
                    timestamp=original_points[idx].timestamp,
                    value=float(values[i]),
                    tags=original_points[idx].tags,
                ))
        return result

    def _downsample_average(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result_indices = []
        result_values = []

        for i in range(target_points):
            start = i * bucket_size
            end = start + bucket_size if i < target_points - 1 else len(values)
            bucket = values[start:end]
            avg_value = np.mean(bucket)
            result_indices.append(start + bucket_size // 2)
            result_values.append(avg_value)

        return self._create_downsampled_points(
            data_points,
            np.array(result_indices),
            np.array(result_values),
        )

    def _downsample_median(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result_indices = []
        result_values = []

        for i in range(target_points):
            start = i * bucket_size
            end = start + bucket_size if i < target_points - 1 else len(values)
            bucket = values[start:end]
            median_value = np.median(bucket)
            result_indices.append(start + bucket_size // 2)
            result_values.append(median_value)

        return self._create_downsampled_points(
            data_points,
            np.array(result_indices),
            np.array(result_values),
        )

    def _downsample_min(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result_indices = []
        result_values = []

        for i in range(target_points):
            start = i * bucket_size
            end = start + bucket_size if i < target_points - 1 else len(values)
            bucket = values[start:end]
            min_idx = np.argmin(bucket)
            result_indices.append(start + min_idx)
            result_values.append(bucket[min_idx])

        return self._create_downsampled_points(
            data_points,
            np.array(result_indices),
            np.array(result_values),
        )

    def _downsample_max(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result_indices = []
        result_values = []

        for i in range(target_points):
            start = i * bucket_size
            end = start + bucket_size if i < target_points - 1 else len(values)
            bucket = values[start:end]
            max_idx = np.argmax(bucket)
            result_indices.append(start + max_idx)
            result_values.append(bucket[max_idx])

        return self._create_downsampled_points(
            data_points,
            np.array(result_indices),
            np.array(result_values),
        )

    def _downsample_first(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result_indices = []
        result_values = []

        for i in range(target_points):
            start = i * bucket_size
            result_indices.append(start)
            result_values.append(values[start])

        return self._create_downsampled_points(
            data_points,
            np.array(result_indices),
            np.array(result_values),
        )

    def _downsample_last(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result_indices = []
        result_values = []

        for i in range(target_points):
            end = (i + 1) * bucket_size if i < target_points - 1 else len(values)
            idx = end - 1
            result_indices.append(idx)
            result_values.append(values[idx])

        return self._create_downsampled_points(
            data_points,
            np.array(result_indices),
            np.array(result_values),
        )

    def _downsample_sum(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result_indices = []
        result_values = []

        for i in range(target_points):
            start = i * bucket_size
            end = start + bucket_size if i < target_points - 1 else len(values)
            bucket = values[start:end]
            sum_value = np.sum(bucket)
            result_indices.append(start + bucket_size // 2)
            result_values.append(sum_value)

        return self._create_downsampled_points(
            data_points,
            np.array(result_indices),
            np.array(result_values),
        )

    def _downsample_lttb(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        if target_points >= len(data_points):
            return data_points.copy()

        values = self._extract_numeric_values(data_points)
        n = len(values)

        sampled_indices = np.zeros(target_points, dtype=int)
        sampled_indices[0] = 0
        sampled_indices[-1] = n - 1

        bucket_size = (n - 2) / (target_points - 2)
        a = 0

        for i in range(target_points - 2):
            avg_x = 0.0
            avg_y = 0.0
            range_start = int((i + 1) * bucket_size) + 1
            range_end = int((i + 2) * bucket_size) + 1

            if range_end > n:
                range_end = n

            avg_x = (range_start + range_end) / 2.0

            for j in range(range_start, range_end):
                avg_y += values[j]

            avg_y /= (range_end - range_start)

            range_offs = int(i * bucket_size) + 1
            range_offe = int((i + 1) * bucket_size) + 1

            max_area = -1.0
            next_a = a

            for j in range(range_offs, range_offe):
                area = abs(
                    (data_points[a].timestamp.timestamp() - avg_x) * (values[j] - values[a]) -
                    (data_points[a].timestamp.timestamp() - data_points[j].timestamp.timestamp()) * (avg_y - values[a])
                ) / 2.0

                if area > max_area:
                    max_area = area
                    next_a = j

            sampled_indices[i + 1] = a
            a = next_a

        result: List[TimeSeriesDataPoint] = []
        for idx in sampled_indices:
            result.append(TimeSeriesDataPoint(
                timestamp=data_points[idx].timestamp,
                value=float(values[idx]),
                tags=data_points[idx].tags,
            ))

        return result

    def _downsample_m4(
        self,
        data_points: List[TimeSeriesDataPoint],
        target_points: int,
        **kwargs: Any,
    ) -> List[TimeSeriesDataPoint]:
        if target_points * 4 >= len(data_points):
            return data_points.copy()

        values = self._extract_numeric_values(data_points)
        bucket_size = len(values) // target_points

        result: List[TimeSeriesDataPoint] = []

        for i in range(target_points):
            start = i * bucket_size
            end = start + bucket_size if i < target_points - 1 else len(values)
            bucket = values[start:end]

            first_idx = start
            min_idx = start + np.argmin(bucket)
            max_idx = start + np.argmax(bucket)
            last_idx = end - 1

            for idx in sorted([first_idx, min_idx, max_idx, last_idx]):
                result.append(TimeSeriesDataPoint(
                    timestamp=data_points[idx].timestamp,
                    value=float(values[idx]),
                    tags=data_points[idx].tags,
                ))

        return result
