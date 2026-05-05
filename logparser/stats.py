import json
import os
import random
import math
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any, Iterator, Tuple, Callable, Set
from dataclasses import dataclass, field
from collections import defaultdict
from enum import Enum
from abc import ABC, abstractmethod

from .parser import LogEntry, LogLevel


class AggregationType(Enum):
    COUNT = "count"
    SUM = "sum"
    AVG = "avg"
    MIN = "min"
    MAX = "max"


class TimeGranularity(Enum):
    SECOND = "second"
    MINUTE = "minute"
    HOUR = "hour"
    DAY = "day"
    WEEK = "week"
    MONTH = "month"


class SamplingStrategy(Enum):
    RESERVOIR = "reservoir"
    SKETCH = "sketch"
    PROBABILISTIC = "probabilistic"
    NONE = "none"


@dataclass
class HighCardinalityConfig:
    enabled: bool = True
    threshold: int = 10000
    strategy: SamplingStrategy = SamplingStrategy.RESERVOIR
    reservoir_size: int = 2000
    min_confidence_interval: float = 0.95
    max_error_rate: float = 0.05

    def to_dict(self) -> Dict[str, Any]:
        return {
            "enabled": self.enabled,
            "threshold": self.threshold,
            "strategy": self.strategy.value,
            "reservoir_size": self.reservoir_size,
            "min_confidence_interval": self.min_confidence_interval,
            "max_error_rate": self.max_error_rate
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "HighCardinalityConfig":
        return cls(
            enabled=data.get("enabled", True),
            threshold=data.get("threshold", 10000),
            strategy=SamplingStrategy(data.get("strategy", "reservoir")),
            reservoir_size=data.get("reservoir_size", 2000),
            min_confidence_interval=data.get("min_confidence_interval", 0.95),
            max_error_rate=data.get("max_error_rate", 0.05)
        )


@dataclass
class AggregationDimension:
    name: str
    field_path: str
    description: str = ""
    enabled: bool = True
    top_n: int = 10
    high_cardinality: Optional[HighCardinalityConfig] = None

    def to_dict(self) -> Dict[str, Any]:
        result = {
            "name": self.name,
            "field_path": self.field_path,
            "description": self.description,
            "enabled": self.enabled,
            "top_n": self.top_n
        }
        if self.high_cardinality:
            result["high_cardinality"] = self.high_cardinality.to_dict()
        return result

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "AggregationDimension":
        high_cardinality = None
        if data.get("high_cardinality"):
            high_cardinality = HighCardinalityConfig.from_dict(data["high_cardinality"])
        
        return cls(
            name=data["name"],
            field_path=data["field_path"],
            description=data.get("description", ""),
            enabled=data.get("enabled", True),
            top_n=data.get("top_n", 10),
            high_cardinality=high_cardinality
        )


@dataclass
class AggregationConfig:
    dimensions: List[AggregationDimension] = field(default_factory=list)
    time_granularity: TimeGranularity = TimeGranularity.HOUR
    peak_threshold: float = 1.5
    top_n: int = 10
    default_high_cardinality: HighCardinalityConfig = field(default_factory=HighCardinalityConfig)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "dimensions": [d.to_dict() for d in self.dimensions],
            "time_granularity": self.time_granularity.value,
            "peak_threshold": self.peak_threshold,
            "top_n": self.top_n,
            "default_high_cardinality": self.default_high_cardinality.to_dict()
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "AggregationConfig":
        dimensions = []
        for dim_data in data.get("dimensions", []):
            dimensions.append(AggregationDimension.from_dict(dim_data))
        
        time_granularity = TimeGranularity(
            data.get("time_granularity", "hour").lower()
        )
        
        default_high_cardinality = HighCardinalityConfig()
        if data.get("default_high_cardinality"):
            default_high_cardinality = HighCardinalityConfig.from_dict(data["default_high_cardinality"])
        
        return cls(
            dimensions=dimensions,
            time_granularity=time_granularity,
            peak_threshold=data.get("peak_threshold", 1.5),
            top_n=data.get("top_n", 10),
            default_high_cardinality=default_high_cardinality
        )


def load_aggregation_config(file_path: str) -> AggregationConfig:
    if not os.path.exists(file_path):
        return get_default_aggregation_config()
    
    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    return AggregationConfig.from_dict(data)


def get_default_aggregation_config() -> AggregationConfig:
    return AggregationConfig(
        dimensions=[
            AggregationDimension(
                name="level",
                field_path="level.value",
                description="按日志级别统计",
                enabled=True,
                top_n=10
            ),
            AggregationDimension(
                name="source",
                field_path="source",
                description="按源模块统计",
                enabled=True,
                top_n=10
            ),
        ],
        time_granularity=TimeGranularity.HOUR,
        peak_threshold=1.5,
        top_n=10
    )


@dataclass
class SamplingEstimate:
    is_sampled: bool = False
    sample_size: int = 0
    total_estimate: int = 0
    confidence_interval: Tuple[float, float] = (0.0, 0.0)
    margin_of_error: float = 0.0
    unique_count_estimate: int = 0
    unique_count_actual: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "is_sampled": self.is_sampled,
            "sample_size": self.sample_size,
            "total_estimate": self.total_estimate,
            "confidence_interval": self.confidence_interval,
            "margin_of_error": self.margin_of_error,
            "unique_count_estimate": self.unique_count_estimate,
            "unique_count_actual": self.unique_count_actual
        }


class ReservoirSampler:
    def __init__(self, reservoir_size: int = 2000, random_seed: int = 42):
        self.reservoir_size = reservoir_size
        self.random = random.Random(random_seed)
        self.reservoir: List[Any] = []
        self.seen_count = 0
        self.item_counts: Dict[Any, int] = defaultdict(int)

    def sample(self, item: Any) -> None:
        self.seen_count += 1
        self.item_counts[item] += 1
        
        if len(self.reservoir) < self.reservoir_size:
            self.reservoir.append(item)
        else:
            j = self.random.randint(0, self.seen_count - 1)
            if j < self.reservoir_size:
                self.reservoir[j] = item

    def get_estimate(self) -> SamplingEstimate:
        if self.seen_count <= self.reservoir_size:
            return SamplingEstimate(
                is_sampled=False,
                sample_size=self.seen_count,
                total_estimate=self.seen_count,
                unique_count_estimate=len(self.item_counts),
                unique_count_actual=len(self.item_counts)
            )
        
        sample_total = len(self.reservoir)
        sample_counts: Dict[Any, int] = defaultdict(int)
        for item in self.reservoir:
            sample_counts[item] += 1
        
        unique_in_sample = len(sample_counts)
        total_estimate = self.seen_count
        
        margin_of_error = 1.96 * (0.5 / (sample_total ** 0.5))
        ci_low = 0.5 - margin_of_error
        ci_high = 0.5 + margin_of_error
        
        return SamplingEstimate(
            is_sampled=True,
            sample_size=sample_total,
            total_estimate=total_estimate,
            confidence_interval=(ci_low, ci_high),
            margin_of_error=margin_of_error,
            unique_count_estimate=unique_in_sample,
            unique_count_actual=len(self.item_counts)
        )

    def get_top_n(self, n: int) -> List[Tuple[Any, int]]:
        if self.seen_count <= self.reservoir_size:
            return sorted(self.item_counts.items(), key=lambda x: x[1], reverse=True)[:n]
        
        sample_counts: Dict[Any, int] = defaultdict(int)
        for item in self.reservoir:
            sample_counts[item] += 1
        
        sorted_sample = sorted(sample_counts.items(), key=lambda x: x[1], reverse=True)
        
        result = []
        for item, sample_count in sorted_sample[:n]:
            estimated_count = int(sample_count * (self.seen_count / self.reservoir_size))
            result.append((item, estimated_count))
        
        return result

    def get_distribution(self) -> Dict[Any, float]:
        if self.seen_count <= self.reservoir_size:
            total = sum(self.item_counts.values())
            return {k: v / total for k, v in self.item_counts.items()} if total else {}
        
        sample_counts: Dict[Any, int] = defaultdict(int)
        for item in self.reservoir:
            sample_counts[item] += 1
        
        sample_total = len(self.reservoir)
        return {k: v / sample_total for k, v in sample_counts.items()} if sample_total else {}


class HyperLogLog:
    def __init__(self, precision: int = 12):
        self.precision = precision
        self.m = 1 << precision
        self.registers = [0] * self.m
        self.alpha = self._get_alpha()

    def _get_alpha(self) -> float:
        if self.m == 16:
            return 0.673
        elif self.m == 32:
            return 0.697
        elif self.m == 64:
            return 0.709
        else:
            return 0.7213 / (1 + 1.079 / self.m)

    def _hash(self, value: Any) -> int:
        import hashlib
        h = hashlib.md5(str(value).encode()).hexdigest()
        return int(h, 16)

    def _rho(self, x: int) -> int:
        if x == 0:
            return self.precision + 1
        p = 1
        while (x & 1) == 0:
            p += 1
            x >>= 1
        return min(p, self.precision + 1)

    def add(self, value: Any) -> None:
        x = self._hash(value)
        idx = x & (self.m - 1)
        x >>= self.precision
        self.registers[idx] = max(self.registers[idx], self._rho(x))

    def count(self) -> float:
        z = sum(1.0 / (1.0 << r) for r in self.registers)
        estimate = self.alpha * self.m * self.m / z
        
        if estimate <= 2.5 * self.m:
            zeros = sum(1 for r in self.registers if r == 0)
            if zeros > 0:
                estimate = self.m * math.log(self.m / zeros)
        
        return estimate


class CardinalityEstimator:
    def __init__(self, use_sketch: bool = True):
        self.use_sketch = use_sketch
        if use_sketch:
            self.hll = HyperLogLog()
        self.seen_set: Set[Any] = set()
        self._is_approximate = False

    def add(self, value: Any) -> bool:
        if not self.use_sketch:
            self.seen_set.add(value)
            return False
        
        if len(self.seen_set) < 10000:
            self.seen_set.add(value)
            return False
        else:
            self._is_approximate = True
            self.hll.add(value)
            return True

    def get_count(self) -> int:
        if self._is_approximate:
            return int(self.hll.count() + len(self.seen_set))
        return len(self.seen_set)

    @property
    def is_approximate(self) -> bool:
        return self._is_approximate


@dataclass
class LevelStats:
    level: str
    count: int
    percentage: float
    source_distribution: Dict[str, int]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "level": self.level,
            "count": self.count,
            "percentage": self.percentage,
            "source_distribution": self.source_distribution
        }


@dataclass
class TimeBucket:
    bucket_key: str
    start_time: Optional[datetime]
    end_time: Optional[datetime]
    count: int
    error_count: int
    warning_count: int
    info_count: int
    error_rate: float
    top_sources: List[Tuple[str, int]]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "bucket_key": self.bucket_key,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "count": self.count,
            "error_count": self.error_count,
            "warning_count": self.warning_count,
            "info_count": self.info_count,
            "error_rate": self.error_rate,
            "top_sources": [{"source": s, "count": c} for s, c in self.top_sources]
        }


@dataclass
class SourceStats:
    source: str
    count: int
    percentage: float
    level_distribution: Dict[str, int]
    error_rate: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source": self.source,
            "count": self.count,
            "percentage": self.percentage,
            "level_distribution": self.level_distribution,
            "error_rate": self.error_rate
        }


@dataclass
class CustomDimensionStats:
    dimension_name: str
    field_path: str
    values: Dict[str, int]
    percentages: Dict[str, float]
    sampling_estimate: Optional[SamplingEstimate] = None

    def to_dict(self) -> Dict[str, Any]:
        result = {
            "dimension_name": self.dimension_name,
            "field_path": self.field_path,
            "values": self.values,
            "percentages": self.percentages
        }
        if self.sampling_estimate:
            result["sampling_estimate"] = self.sampling_estimate.to_dict()
        return result


@dataclass
class StatisticsReport:
    total_logs: int
    time_range: Tuple[Optional[datetime], Optional[datetime]]
    level_stats: List[LevelStats]
    time_buckets: List[TimeBucket]
    source_stats: List[SourceStats]
    custom_dimension_stats: List[CustomDimensionStats]
    overall_error_rate: float
    peak_periods: List[Dict[str, Any]]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_logs": self.total_logs,
            "time_range": {
                "start": self.time_range[0].isoformat() if self.time_range[0] else None,
                "end": self.time_range[1].isoformat() if self.time_range[1] else None
            },
            "level_stats": [ls.to_dict() for ls in self.level_stats],
            "time_buckets": [tb.to_dict() for tb in self.time_buckets],
            "source_stats": [ss.to_dict() for ss in self.source_stats],
            "custom_dimension_stats": [cs.to_dict() for cs in self.custom_dimension_stats],
            "overall_error_rate": self.overall_error_rate,
            "peak_periods": self.peak_periods
        }


class DimensionExtractor:
    @staticmethod
    def extract_field(entry: LogEntry, field_path: str) -> Any:
        parts = field_path.split(".")
        value: Any = entry
        
        for part in parts:
            if value is None:
                return None
            
            if hasattr(value, part):
                value = getattr(value, part)
            elif isinstance(value, dict) and part in value:
                value = value[part]
            else:
                return None
        
        return value

    @staticmethod
    def extract_as_string(entry: LogEntry, field_path: str) -> str:
        value = DimensionExtractor.extract_field(entry, field_path)
        if value is None:
            return "UNKNOWN"
        if isinstance(value, Enum):
            return value.value
        return str(value)


class DimensionAggregator(ABC):
    @abstractmethod
    def process_value(self, value: str) -> None:
        pass

    @abstractmethod
    def get_result(self, top_n: int) -> Tuple[Dict[str, int], Dict[str, float], Optional[SamplingEstimate]]:
        pass


class FullAggregation(DimensionAggregator):
    def __init__(self):
        self.counts: Dict[str, int] = defaultdict(int)
        self.total = 0

    def process_value(self, value: str) -> None:
        self.counts[value] += 1
        self.total += 1

    def get_result(self, top_n: int) -> Tuple[Dict[str, int], Dict[str, float], Optional[SamplingEstimate]]:
        sorted_items = sorted(self.counts.items(), key=lambda x: x[1], reverse=True)[:top_n]
        values = dict(sorted_items)
        
        percentages = {}
        if self.total > 0:
            for v, c in values.items():
                percentages[v] = c / self.total
        
        return values, percentages, None


class SamplingAggregation(DimensionAggregator):
    def __init__(self, reservoir_size: int = 2000, threshold: int = 10000):
        self.sampler = ReservoirSampler(reservoir_size=reservoir_size)
        self.threshold = threshold
        self._triggered_sampling = False

    def process_value(self, value: str) -> None:
        self.sampler.sample(value)

    def get_result(self, top_n: int) -> Tuple[Dict[str, int], Dict[str, float], Optional[SamplingEstimate]]:
        estimate = self.sampler.get_estimate()
        
        top_items = self.sampler.get_top_n(top_n)
        values = dict(top_items)
        
        distribution = self.sampler.get_distribution()
        percentages = {k: distribution.get(k, 0.0) for k in values.keys()}
        
        return values, percentages, estimate


class StreamingAggregator:
    def __init__(
        self,
        config: Optional[AggregationConfig] = None,
        by_level: bool = True,
        by_time: bool = True,
        by_source: bool = True,
        time_granularity: TimeGranularity = TimeGranularity.HOUR
    ):
        self.config = config or get_default_aggregation_config()
        self.by_level = by_level
        self.by_time = by_time
        self.by_source = by_source
        self.time_granularity = time_granularity
        
        self.total_count = 0
        self.error_count = 0
        self.warning_count = 0
        self.min_time: Optional[datetime] = None
        self.max_time: Optional[datetime] = None
        
        self.level_counts: Dict[str, int] = defaultdict(int)
        self.level_sources: Dict[str, Dict[str, int]] = defaultdict(lambda: defaultdict(int))
        
        self.source_counts: Dict[str, int] = defaultdict(int)
        self.source_levels: Dict[str, Dict[str, int]] = defaultdict(lambda: defaultdict(int))
        self.source_errors: Dict[str, int] = defaultdict(int)
        
        self.time_buckets_data: Dict[str, Dict[str, Any]] = defaultdict(
            lambda: {
                "count": 0,
                "error_count": 0,
                "warning_count": 0,
                "info_count": 0,
                "sources": defaultdict(int),
                "start_time": None,
                "end_time": None
            }
        )
        
        self.custom_aggregators: Dict[str, DimensionAggregator] = {}
        for dim in self.config.dimensions:
            if dim.enabled:
                hc_config = dim.high_cardinality or self.config.default_high_cardinality
                if hc_config.enabled and hc_config.strategy != SamplingStrategy.NONE:
                    self.custom_aggregators[dim.name] = SamplingAggregation(
                        reservoir_size=hc_config.reservoir_size,
                        threshold=hc_config.threshold
                    )
                else:
                    self.custom_aggregators[dim.name] = FullAggregation()

    def _get_time_bucket_key(self, timestamp: datetime) -> str:
        granularity = self.time_granularity
        if granularity == TimeGranularity.SECOND:
            return timestamp.strftime("%Y-%m-%d %H:%M:%S")
        elif granularity == TimeGranularity.MINUTE:
            return timestamp.strftime("%Y-%m-%d %H:%M")
        elif granularity == TimeGranularity.HOUR:
            return timestamp.strftime("%Y-%m-%d %H")
        elif granularity == TimeGranularity.DAY:
            return timestamp.strftime("%Y-%m-%d")
        elif granularity == TimeGranularity.WEEK:
            week_start = timestamp - timedelta(days=timestamp.weekday())
            return week_start.strftime("%Y-%m-%d")
        elif granularity == TimeGranularity.MONTH:
            return timestamp.strftime("%Y-%m")
        else:
            return timestamp.strftime("%Y-%m-%d %H")

    def process_entry(self, entry: LogEntry) -> None:
        self.total_count += 1
        
        if entry.timestamp:
            if self.min_time is None or entry.timestamp < self.min_time:
                self.min_time = entry.timestamp
            if self.max_time is None or entry.timestamp > self.max_time:
                self.max_time = entry.timestamp
        
        if entry.level == LogLevel.ERROR or entry.level == LogLevel.CRITICAL:
            self.error_count += 1
        elif entry.level == LogLevel.WARNING:
            self.warning_count += 1
        
        if self.by_level:
            level_name = entry.level.value
            self.level_counts[level_name] += 1
            self.level_sources[level_name][entry.source] += 1
        
        if self.by_source:
            self.source_counts[entry.source] += 1
            self.source_levels[entry.source][entry.level.value] += 1
            if entry.level in (LogLevel.ERROR, LogLevel.CRITICAL):
                self.source_errors[entry.source] += 1
        
        if self.by_time and entry.timestamp:
            bucket_key = self._get_time_bucket_key(entry.timestamp)
            bucket = self.time_buckets_data[bucket_key]
            
            bucket["count"] += 1
            bucket["sources"][entry.source] += 1
            
            if bucket["start_time"] is None or entry.timestamp < bucket["start_time"]:
                bucket["start_time"] = entry.timestamp
            if bucket["end_time"] is None or entry.timestamp > bucket["end_time"]:
                bucket["end_time"] = entry.timestamp
            
            if entry.level in (LogLevel.ERROR, LogLevel.CRITICAL):
                bucket["error_count"] += 1
            elif entry.level == LogLevel.WARNING:
                bucket["warning_count"] += 1
            elif entry.level == LogLevel.INFO:
                bucket["info_count"] += 1
        
        for dim in self.config.dimensions:
            if dim.enabled and dim.name in self.custom_aggregators:
                value = DimensionExtractor.extract_as_string(entry, dim.field_path)
                self.custom_aggregators[dim.name].process_value(value)

    def process_stream(self, entries: Iterator[LogEntry]) -> None:
        for entry in entries:
            self.process_entry(entry)

    def get_overall_error_rate(self) -> float:
        return self.error_count / self.total_count if self.total_count > 0 else 0.0

    def build_level_stats(self) -> List[LevelStats]:
        if not self.by_level:
            return []
        
        LEVEL_ORDER = {
            LogLevel.CRITICAL: 0,
            LogLevel.ERROR: 1,
            LogLevel.WARNING: 2,
            LogLevel.INFO: 3,
            LogLevel.DEBUG: 4,
            LogLevel.UNKNOWN: 5,
        }
        
        sorted_levels = sorted(
            self.level_counts.keys(),
            key=lambda x: LEVEL_ORDER.get(LogLevel(x), 100)
        )
        
        level_stats_list = []
        for level in sorted_levels:
            count = self.level_counts[level]
            percentage = count / self.total_count if self.total_count > 0 else 0.0
            
            sorted_sources = sorted(
                self.level_sources[level].items(),
                key=lambda x: x[1],
                reverse=True
            )[:self.config.top_n]

            level_stats = LevelStats(
                level=level,
                count=count,
                percentage=percentage,
                source_distribution=dict(sorted_sources)
            )
            level_stats_list.append(level_stats)

        return level_stats_list

    def build_source_stats(self) -> List[SourceStats]:
        if not self.by_source:
            return []
        
        sorted_sources = sorted(
            self.source_counts.items(),
            key=lambda x: x[1],
            reverse=True
        )[:self.config.top_n]

        source_stats_list = []
        for source, count in sorted_sources:
            percentage = count / self.total_count if self.total_count > 0 else 0.0
            error_rate = self.source_errors[source] / count if count > 0 else 0.0

            source_stats = SourceStats(
                source=source,
                count=count,
                percentage=percentage,
                level_distribution=dict(self.source_levels[source]),
                error_rate=error_rate
            )
            source_stats_list.append(source_stats)

        return source_stats_list

    def _parse_bucket_key(self, key: str) -> Optional[datetime]:
        granularity = self.time_granularity
        try:
            if granularity == TimeGranularity.SECOND:
                return datetime.strptime(key, "%Y-%m-%d %H:%M:%S")
            elif granularity == TimeGranularity.MINUTE:
                return datetime.strptime(key, "%Y-%m-%d %H:%M")
            elif granularity == TimeGranularity.HOUR:
                return datetime.strptime(key, "%Y-%m-%d %H")
            elif granularity == TimeGranularity.DAY:
                return datetime.strptime(key, "%Y-%m-%d")
            elif granularity == TimeGranularity.WEEK:
                return datetime.strptime(key, "%Y-%m-%d")
            elif granularity == TimeGranularity.MONTH:
                return datetime.strptime(key, "%Y-%m")
        except (ValueError, TypeError):
            pass
        return None

    def _get_bucket_duration(self) -> timedelta:
        granularity = self.time_granularity
        if granularity == TimeGranularity.SECOND:
            return timedelta(seconds=1)
        elif granularity == TimeGranularity.MINUTE:
            return timedelta(minutes=1)
        elif granularity == TimeGranularity.HOUR:
            return timedelta(hours=1)
        elif granularity == TimeGranularity.DAY:
            return timedelta(days=1)
        elif granularity == TimeGranularity.WEEK:
            return timedelta(weeks=1)
        elif granularity == TimeGranularity.MONTH:
            return timedelta(days=30)
        else:
            return timedelta(hours=1)

    def build_time_buckets(self) -> List[TimeBucket]:
        if not self.by_time:
            return []
        
        sorted_keys = sorted(self.time_buckets_data.keys())
        time_buckets = []
        duration = self._get_bucket_duration()

        for key in sorted_keys:
            bucket = self.time_buckets_data[key]
            bucket_start = bucket["start_time"] or self._parse_bucket_key(key)
            bucket_end = bucket["end_time"]
            
            if bucket_end is None and bucket_start:
                bucket_end = bucket_start + duration

            error_rate = bucket["error_count"] / bucket["count"] if bucket["count"] > 0 else 0.0

            top_sources = sorted(
                bucket["sources"].items(),
                key=lambda x: x[1],
                reverse=True
            )[:5]

            time_bucket = TimeBucket(
                bucket_key=key,
                start_time=bucket_start,
                end_time=bucket_end,
                count=bucket["count"],
                error_count=bucket["error_count"],
                warning_count=bucket["warning_count"],
                info_count=bucket["info_count"],
                error_rate=error_rate,
                top_sources=top_sources
            )
            time_buckets.append(time_bucket)

        return time_buckets

    def build_custom_dimension_stats(self) -> List[CustomDimensionStats]:
        stats_list = []
        
        for dim in self.config.dimensions:
            if not dim.enabled or dim.name not in self.custom_aggregators:
                continue
            
            aggregator = self.custom_aggregators[dim.name]
            values, percentages, sampling_estimate = aggregator.get_result(dim.top_n)
            
            stats = CustomDimensionStats(
                dimension_name=dim.name,
                field_path=dim.field_path,
                values=values,
                percentages=percentages,
                sampling_estimate=sampling_estimate
            )
            stats_list.append(stats)
        
        return stats_list

    def identify_peak_periods(self, time_buckets: List[TimeBucket]) -> List[Dict[str, Any]]:
        if not time_buckets:
            return []

        counts = [b.count for b in time_buckets]
        if not counts:
            return []

        avg_count = sum(counts) / len(counts)
        threshold = avg_count * self.config.peak_threshold

        peak_periods = []
        for bucket in time_buckets:
            if bucket.count >= threshold and bucket.count > 0:
                peak_periods.append({
                    "start": bucket.start_time.isoformat() if bucket.start_time else None,
                    "end": bucket.end_time.isoformat() if bucket.end_time else None,
                    "count": bucket.count,
                    "error_rate": bucket.error_rate,
                    "is_above_average": bucket.count > avg_count
                })

        return sorted(peak_periods, key=lambda x: x["count"], reverse=True)[:5]

    def build_report(self) -> StatisticsReport:
        level_stats = self.build_level_stats()
        time_buckets = self.build_time_buckets()
        source_stats = self.build_source_stats()
        custom_stats = self.build_custom_dimension_stats()
        peak_periods = self.identify_peak_periods(time_buckets)

        return StatisticsReport(
            total_logs=self.total_count,
            time_range=(self.min_time, self.max_time),
            level_stats=level_stats,
            time_buckets=time_buckets,
            source_stats=source_stats,
            custom_dimension_stats=custom_stats,
            overall_error_rate=self.get_overall_error_rate(),
            peak_periods=peak_periods
        )


class StatisticsEngine:
    LEVEL_ORDER = {
        LogLevel.CRITICAL: 0,
        LogLevel.ERROR: 1,
        LogLevel.WARNING: 2,
        LogLevel.INFO: 3,
        LogLevel.DEBUG: 4,
        LogLevel.UNKNOWN: 5,
    }

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        self.config = config or {}
        
        aggregation_config = self.config.get("aggregation_config")
        if aggregation_config and isinstance(aggregation_config, AggregationConfig):
            self.aggregation_config = aggregation_config
        else:
            self.aggregation_config = get_default_aggregation_config()
        
        self.default_time_granularity = TimeGranularity(
            self.config.get("time_granularity", "hour")
        )
        self.top_n = self.config.get("top_n", 10)
        self.peak_threshold = self.config.get("peak_threshold", 1.5)

    def _get_time_bucket_key(
        self,
        timestamp: datetime,
        granularity: TimeGranularity
    ) -> str:
        if granularity == TimeGranularity.SECOND:
            return timestamp.strftime("%Y-%m-%d %H:%M:%S")
        elif granularity == TimeGranularity.MINUTE:
            return timestamp.strftime("%Y-%m-%d %H:%M")
        elif granularity == TimeGranularity.HOUR:
            return timestamp.strftime("%Y-%m-%d %H")
        elif granularity == TimeGranularity.DAY:
            return timestamp.strftime("%Y-%m-%d")
        elif granularity == TimeGranularity.WEEK:
            week_start = timestamp - timedelta(days=timestamp.weekday())
            return week_start.strftime("%Y-%m-%d")
        elif granularity == TimeGranularity.MONTH:
            return timestamp.strftime("%Y-%m")
        else:
            return timestamp.strftime("%Y-%m-%d %H")

    def _parse_bucket_key(
        self,
        key: str,
        granularity: TimeGranularity
    ) -> Optional[datetime]:
        try:
            if granularity == TimeGranularity.SECOND:
                return datetime.strptime(key, "%Y-%m-%d %H:%M:%S")
            elif granularity == TimeGranularity.MINUTE:
                return datetime.strptime(key, "%Y-%m-%d %H:%M")
            elif granularity == TimeGranularity.HOUR:
                return datetime.strptime(key, "%Y-%m-%d %H")
            elif granularity == TimeGranularity.DAY:
                return datetime.strptime(key, "%Y-%m-%d")
            elif granularity == TimeGranularity.WEEK:
                return datetime.strptime(key, "%Y-%m-%d")
            elif granularity == TimeGranularity.MONTH:
                return datetime.strptime(key, "%Y-%m")
        except (ValueError, TypeError):
            pass
        return None

    def _get_bucket_duration(self, granularity: TimeGranularity) -> timedelta:
        if granularity == TimeGranularity.SECOND:
            return timedelta(seconds=1)
        elif granularity == TimeGranularity.MINUTE:
            return timedelta(minutes=1)
        elif granularity == TimeGranularity.HOUR:
            return timedelta(hours=1)
        elif granularity == TimeGranularity.DAY:
            return timedelta(days=1)
        elif granularity == TimeGranularity.WEEK:
            return timedelta(weeks=1)
        elif granularity == TimeGranularity.MONTH:
            return timedelta(days=30)
        else:
            return timedelta(hours=1)

    def aggregate_by_level(
        self,
        entries: Iterator[LogEntry],
        total: Optional[int] = None
    ) -> Tuple[List[LevelStats], int]:
        level_counts: Dict[str, int] = defaultdict(int)
        level_sources: Dict[str, Dict[str, int]] = defaultdict(lambda: defaultdict(int))
        count = 0

        for entry in entries:
            count += 1
            level_name = entry.level.value
            level_counts[level_name] += 1
            level_sources[level_name][entry.source] += 1

        total_logs = total if total is not None else count

        sorted_levels = sorted(
            level_counts.keys(),
            key=lambda x: self.LEVEL_ORDER.get(LogLevel(x), 100)
        )

        level_stats_list = []
        for level in sorted_levels:
            level_count = level_counts[level]
            percentage = level_count / total_logs if total_logs > 0 else 0.0
            
            sorted_sources = sorted(
                level_sources[level].items(),
                key=lambda x: x[1],
                reverse=True
            )[:self.top_n]

            level_stats = LevelStats(
                level=level,
                count=level_count,
                percentage=percentage,
                source_distribution=dict(sorted_sources)
            )
            level_stats_list.append(level_stats)

        return level_stats_list, total_logs

    def aggregate_by_time(
        self,
        entries: Iterator[LogEntry],
        granularity: Optional[TimeGranularity] = None
    ) -> List[TimeBucket]:
        if granularity is None:
            granularity = self.default_time_granularity

        buckets: Dict[str, Dict[str, Any]] = defaultdict(
            lambda: {
                "count": 0,
                "error_count": 0,
                "warning_count": 0,
                "info_count": 0,
                "sources": defaultdict(int),
                "start_time": None,
                "end_time": None
            }
        )

        for entry in entries:
            if entry.timestamp is None:
                continue

            bucket_key = self._get_time_bucket_key(entry.timestamp, granularity)
            bucket = buckets[bucket_key]

            bucket["count"] += 1
            bucket["sources"][entry.source] += 1

            if bucket["start_time"] is None or entry.timestamp < bucket["start_time"]:
                bucket["start_time"] = entry.timestamp
            if bucket["end_time"] is None or entry.timestamp > bucket["end_time"]:
                bucket["end_time"] = entry.timestamp

            if entry.level in (LogLevel.ERROR, LogLevel.CRITICAL):
                bucket["error_count"] += 1
            elif entry.level == LogLevel.WARNING:
                bucket["warning_count"] += 1
            elif entry.level == LogLevel.INFO:
                bucket["info_count"] += 1

        sorted_keys = sorted(buckets.keys())
        time_buckets = []
        duration = self._get_bucket_duration(granularity)

        for key in sorted_keys:
            bucket = buckets[key]
            bucket_start = bucket["start_time"] or self._parse_bucket_key(key, granularity)
            bucket_end = bucket["end_time"]
            
            if bucket_end is None and bucket_start:
                bucket_end = bucket_start + duration

            error_rate = bucket["error_count"] / bucket["count"] if bucket["count"] > 0 else 0.0

            top_sources = sorted(
                bucket["sources"].items(),
                key=lambda x: x[1],
                reverse=True
            )[:5]

            time_bucket = TimeBucket(
                bucket_key=key,
                start_time=bucket_start,
                end_time=bucket_end,
                count=bucket["count"],
                error_count=bucket["error_count"],
                warning_count=bucket["warning_count"],
                info_count=bucket["info_count"],
                error_rate=error_rate,
                top_sources=top_sources
            )
            time_buckets.append(time_bucket)

        return time_buckets

    def aggregate_by_source(
        self,
        entries: Iterator[LogEntry],
        total: Optional[int] = None
    ) -> List[SourceStats]:
        source_counts: Dict[str, int] = defaultdict(int)
        source_levels: Dict[str, Dict[str, int]] = defaultdict(lambda: defaultdict(int))
        source_errors: Dict[str, int] = defaultdict(int)
        count = 0

        for entry in entries:
            count += 1
            source_counts[entry.source] += 1
            source_levels[entry.source][entry.level.value] += 1
            
            if entry.level in (LogLevel.ERROR, LogLevel.CRITICAL):
                source_errors[entry.source] += 1

        total_logs = total if total is not None else count

        sorted_sources = sorted(
            source_counts.items(),
            key=lambda x: x[1],
            reverse=True
        )[:self.top_n]

        source_stats_list = []
        for source, src_count in sorted_sources:
            percentage = src_count / total_logs if total_logs > 0 else 0.0
            error_rate = source_errors[source] / src_count if src_count > 0 else 0.0

            source_stats = SourceStats(
                source=source,
                count=src_count,
                percentage=percentage,
                level_distribution=dict(source_levels[source]),
                error_rate=error_rate
            )
            source_stats_list.append(source_stats)

        return source_stats_list

    def _identify_peak_periods(
        self,
        time_buckets: List[TimeBucket],
        granularity: TimeGranularity
    ) -> List[Dict[str, Any]]:
        if not time_buckets:
            return []

        counts = [b.count for b in time_buckets]
        if not counts:
            return []

        avg_count = sum(counts) / len(counts)
        threshold = avg_count * self.peak_threshold

        peak_periods = []
        for bucket in time_buckets:
            if bucket.count >= threshold and bucket.count > 0:
                peak_periods.append({
                    "start": bucket.start_time.isoformat() if bucket.start_time else None,
                    "end": bucket.end_time.isoformat() if bucket.end_time else None,
                    "count": bucket.count,
                    "error_rate": bucket.error_rate,
                    "is_above_average": bucket.count > avg_count
                })

        return sorted(peak_periods, key=lambda x: x["count"], reverse=True)[:5]

    def generate_report(
        self,
        entries: Iterator[LogEntry],
        by_level: bool = True,
        by_time: bool = True,
        by_source: bool = True,
        time_granularity: str = "hour"
    ) -> StatisticsReport:
        granularity = TimeGranularity(time_granularity)
        
        aggregator = StreamingAggregator(
            config=self.aggregation_config,
            by_level=by_level,
            by_time=by_time,
            by_source=by_source,
            time_granularity=granularity
        )
        
        aggregator.process_stream(entries)
        
        return aggregator.build_report()


def generate_statistics(
    entries: Iterator[LogEntry],
    by_level: bool = True,
    by_time: bool = True,
    by_source: bool = True,
    time_granularity: str = "hour",
    config: Optional[Dict[str, Any]] = None
) -> StatisticsReport:
    engine = StatisticsEngine(config)
    return engine.generate_report(
        entries,
        by_level=by_level,
        by_time=by_time,
        by_source=by_source,
        time_granularity=time_granularity
    )
