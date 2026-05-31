import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple

import numpy as np

from src.infrastructure.config.settings import DownsamplingConfig

logger = logging.getLogger(__name__)


class AggregationType(Enum):
    AVG = "avg"
    MIN = "min"
    MAX = "max"
    SUM = "sum"
    COUNT = "count"
    FIRST = "first"
    LAST = "last"
    MEDIAN = "median"
    P50 = "p50"
    P90 = "p90"
    P95 = "p95"
    P99 = "p99"


@dataclass
class DownsampledPoint:
    timestamp: int
    value: float
    aggregation: str
    original_count: int = 0
    min_value: Optional[float] = None
    max_value: Optional[float] = None


@dataclass
class DownsampledSeries:
    metric_name: str
    interval: str
    aggregation: str
    points: List[DownsampledPoint] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "metric_name": self.metric_name,
            "interval": self.interval,
            "aggregation": self.aggregation,
            "points": [
                {
                    "timestamp": p.timestamp,
                    "value": p.value,
                    "aggregation": p.aggregation,
                    "original_count": p.original_count,
                    "min": p.min_value,
                    "max": p.max_value,
                }
                for p in self.points
            ],
        }


class DownsamplingEngine:
    INTERVAL_SECONDS = {
        "1s": 1,
        "1m": 60,
        "5m": 300,
        "10m": 600,
        "15m": 900,
        "30m": 1800,
        "1h": 3600,
        "6h": 21600,
        "12h": 43200,
        "1d": 86400,
        "7d": 604800,
    }

    AGGREGATORS: Dict[str, Callable] = {
        "avg": lambda vals: float(np.mean(vals)),
        "min": lambda vals: float(np.min(vals)),
        "max": lambda vals: float(np.max(vals)),
        "sum": lambda vals: float(np.sum(vals)),
        "count": lambda vals: float(len(vals)),
        "first": lambda vals: float(vals[0]),
        "last": lambda vals: float(vals[-1]),
        "median": lambda vals: float(np.median(vals)),
        "p50": lambda vals: float(np.percentile(vals, 50)),
        "p90": lambda vals: float(np.percentile(vals, 90)),
        "p95": lambda vals: float(np.percentile(vals, 95)),
        "p99": lambda vals: float(np.percentile(vals, 99)),
    }

    def __init__(self, config: Optional[DownsamplingConfig] = None):
        self._config = config or DownsamplingConfig()
        self._custom_aggregators: Dict[str, Callable] = {}

    def register_aggregator(self, name: str, fn: Callable) -> None:
        self._custom_aggregators[name] = fn

    def downsample(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
        interval: str,
        aggregation: Optional[str] = None,
    ) -> DownsampledSeries:
        agg = aggregation or self._config.default_aggregation
        interval_seconds = self.INTERVAL_SECONDS.get(interval, 60)

        if not timestamps or not values:
            return DownsampledSeries(
                metric_name=metric_name,
                interval=interval,
                aggregation=agg,
            )

        min_len = min(len(timestamps), len(values))
        timestamps = timestamps[:min_len]
        values = values[:min_len]

        buckets = self._bucket_data(timestamps, values, interval_seconds)
        points = self._aggregate_buckets(buckets, agg)

        return DownsampledSeries(
            metric_name=metric_name,
            interval=interval,
            aggregation=agg,
            points=points,
        )

    def _bucket_data(
        self,
        timestamps: List[int],
        values: List[float],
        interval_seconds: int,
    ) -> Dict[int, List[float]]:
        buckets: Dict[int, List[float]] = {}

        if not timestamps:
            return buckets

        base_ts = timestamps[0]
        aligned_base = (base_ts // interval_seconds) * interval_seconds

        for ts, val in zip(timestamps, values):
            bucket_key = ((ts - aligned_base) // interval_seconds) * interval_seconds + aligned_base
            if bucket_key not in buckets:
                buckets[bucket_key] = []
            buckets[bucket_key].append(val)

        return buckets

    def _aggregate_buckets(
        self,
        buckets: Dict[int, List[float]],
        aggregation: str,
    ) -> List[DownsampledPoint]:
        aggregator = self.AGGREGATORS.get(aggregation) or self._custom_aggregators.get(aggregation)
        if aggregator is None:
            raise ValueError(f"Unknown aggregation type: {aggregation}")

        points = []
        for bucket_ts in sorted(buckets.keys()):
            vals = np.array(buckets[bucket_ts])
            agg_value = aggregator(vals)
            points.append(DownsampledPoint(
                timestamp=bucket_ts,
                value=agg_value,
                aggregation=aggregation,
                original_count=len(vals),
                min_value=float(np.min(vals)),
                max_value=float(np.max(vals)),
            ))

        return points

    def multi_aggregate_downsample(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
        interval: str,
        aggregations: List[str],
    ) -> Dict[str, DownsampledSeries]:
        results = {}
        for agg in aggregations:
            results[agg] = self.downsample(metric_name, timestamps, values, interval, agg)
        return results

    def cascading_downsample(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
        intervals: List[str],
        aggregation: Optional[str] = None,
    ) -> Dict[str, DownsampledSeries]:
        results = {}
        current_ts = timestamps
        current_vals = values

        for interval in intervals:
            series = self.downsample(metric_name, current_ts, current_vals, interval, aggregation)
            results[interval] = series
            current_ts = [p.timestamp for p in series.points]
            current_vals = [p.value for p in series.points]

        return results
