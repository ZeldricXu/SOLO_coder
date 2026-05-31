import asyncio
import json
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from statistics import mean
from typing import Any, Callable, Dict, Deque, List, Optional
from uuid import uuid4

from top.core.models import SnapshotModel


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class AggregationType(str, Enum):
    SUM = "sum"
    COUNT = "count"
    AVG = "avg"
    P50 = "p50"
    P90 = "p90"
    P95 = "p95"
    P99 = "p99"
    MIN = "min"
    MAX = "max"
    LATEST = "latest"


@dataclass
class Metric:
    name: str
    value: float
    timestamp: datetime = field(default_factory=utc_now)
    dimensions: Dict[str, str] = field(default_factory=dict)
    unit: str = ""


@dataclass
class MetricAggregation:
    metric_name: str
    aggregation: AggregationType
    value: float
    dimensions: Dict[str, str]
    window_start: datetime
    window_end: datetime


class MetricRegistry:
    def __init__(self):
        self._counters: Dict[str, Dict] = {}
        self._gauges: Dict[str, Dict] = {}
        self._histograms: Dict[str, Dict] = {}
        self._timers: Dict[str, Dict] = {}
        self._lock = threading.Lock()

    def counter(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> "Counter":
        key = self._make_key(name, dimensions)
        with self._lock:
            if key not in self._counters:
                self._counters[key] = {"name": name, "value": 0, "dimensions": dimensions or {}}
        return Counter(self._counters[key], self._lock)

    def gauge(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> "Gauge":
        key = self._make_key(name, dimensions)
        with self._lock:
            if key not in self._gauges:
                self._gauges[key] = {"name": name, "value": 0.0, "dimensions": dimensions or {}}
        return Gauge(self._gauges[key], self._lock)

    def histogram(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> "Histogram":
        key = self._make_key(name, dimensions)
        with self._lock:
            if key not in self._histograms:
                self._histograms[key] = {
                    "name": name,
                    "values": deque(maxlen=10000),
                    "dimensions": dimensions or {},
                }
        return Histogram(self._histograms[key], self._lock)

    def timer(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> "Timer":
        key = self._make_key(name, dimensions)
        with self._lock:
            if key not in self._timers:
                self._timers[key] = {"name": name, "total": 0.0, "count": 0, "dimensions": dimensions or {}}
        return Timer(self._timers[key], self._lock)

    def _make_key(self, name: str, dimensions: Optional[Dict[str, str]]) -> str:
        dims = dimensions or {}
        dim_str = ",".join(f"{k}={v}" for k, v in sorted(dims.items()))
        return f"{name}:{dim_str}"

    def collect_all(self) -> List[Dict[str, Any]]:
        metrics = []

        with self._lock:
            for key, counter in self._counters.items():
                metrics.append(
                    {
                        "type": "counter",
                        "name": counter["name"],
                        "value": counter["value"],
                        "dimensions": counter["dimensions"],
                    }
                )

            for key, gauge in self._gauges.items():
                metrics.append(
                    {
                        "type": "gauge",
                        "name": gauge["name"],
                        "value": gauge["value"],
                        "dimensions": gauge["dimensions"],
                    }
                )

            for key, timer in self._timers.items():
                avg = timer["total"] / timer["count"] if timer["count"] > 0 else 0
                metrics.append(
                    {
                        "type": "timer",
                        "name": timer["name"],
                        "total": timer["total"],
                        "count": timer["count"],
                        "avg_ms": avg,
                        "dimensions": timer["dimensions"],
                    }
                )

            for key, hist in self._histograms.items():
                values = list(hist["values"])
                if values:
                    sorted_vals = sorted(values)
                    metrics.append(
                        {
                            "type": "histogram",
                            "name": hist["name"],
                            "count": len(sorted_vals),
                            "p50": self._percentile(sorted_vals, 0.50),
                            "p90": self._percentile(sorted_vals, 0.90),
                            "p95": self._percentile(sorted_vals, 0.95),
                            "p99": self._percentile(sorted_vals, 0.99),
                            "dimensions": hist["dimensions"],
                        }
                    )

        return metrics

    def _percentile(self, sorted_values: List[float], p: float) -> float:
        if not sorted_values:
            return 0.0
        index = int(len(sorted_values) * p)
        if index >= len(sorted_values):
            index = len(sorted_values) - 1
        return sorted_values[index]


class Counter:
    def __init__(self, storage: Dict, lock: threading.Lock):
        self._storage = storage
        self._lock = lock

    def inc(self, amount: int = 1) -> int:
        with self._lock:
            self._storage["value"] += amount
            return self._storage["value"]

    def dec(self, amount: int = 1) -> int:
        with self._lock:
            self._storage["value"] -= amount
            return self._storage["value"]

    def value(self) -> int:
        with self._lock:
            return self._storage["value"]

    def reset(self) -> None:
        with self._lock:
            self._storage["value"] = 0


class Gauge:
    def __init__(self, storage: Dict, lock: threading.Lock):
        self._storage = storage
        self._lock = lock

    def set(self, value: float) -> float:
        with self._lock:
            self._storage["value"] = value
            return self._storage["value"]

    def inc(self, amount: float = 1.0) -> float:
        with self._lock:
            self._storage["value"] += amount
            return self._storage["value"]

    def dec(self, amount: float = 1.0) -> float:
        with self._lock:
            self._storage["value"] -= amount
            return self._storage["value"]

    def value(self) -> float:
        with self._lock:
            return self._storage["value"]


class Histogram:
    def __init__(self, storage: Dict, lock: threading.Lock):
        self._storage = storage
        self._lock = lock

    def record(self, value: float) -> None:
        with self._lock:
            self._storage["values"].append(value)

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            values = list(self._storage["values"])
            if not values:
                return {"count": 0}
            sorted_vals = sorted(values)
            return {
                "count": len(sorted_vals),
                "min": sorted_vals[0],
                "max": sorted_vals[-1],
                "avg": mean(sorted_vals),
                "p50": self._percentile(sorted_vals, 0.50),
                "p90": self._percentile(sorted_vals, 0.90),
                "p95": self._percentile(sorted_vals, 0.95),
                "p99": self._percentile(sorted_vals, 0.99),
            }

    def _percentile(self, sorted_values: List[float], p: float) -> float:
        if not sorted_values:
            return 0.0
        index = int(len(sorted_values) * p)
        if index >= len(sorted_values):
            index = len(sorted_values) - 1
        return sorted_values[index]


class Timer:
    def __init__(self, storage: Dict, lock: threading.Lock):
        self._storage = storage
        self._lock = lock
        self._start_time: Optional[float] = None

    def __enter__(self):
        self._start_time = time.time()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._start_time is not None:
            elapsed = (time.time() - self._start_time) * 1000
            self.record(elapsed)
        return False

    async def __aenter__(self):
        self._start_time = time.time()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if self._start_time is not None:
            elapsed = (time.time() - self._start_time) * 1000
            self.record(elapsed)
        return False

    def record(self, duration_ms: float) -> None:
        with self._lock:
            self._storage["total"] += duration_ms
            self._storage["count"] += 1

    def time(self) -> Dict[str, Any]:
        with self._lock:
            total = self._storage["total"]
            count = self._storage["count"]
            avg = total / count if count > 0 else 0
            return {
                "total_ms": total,
                "count": count,
                "avg_ms": avg,
            }


class MetricCollector:
    def __init__(self, registry: Optional[MetricRegistry] = None):
        self._registry = registry or MetricRegistry()
        self._raw_metrics: Deque[Metric] = deque(maxlen=10000)
        self._lock = threading.Lock()

    @property
    def registry(self) -> MetricRegistry:
        return self._registry

    def record(self, metric: Metric) -> None:
        with self._lock:
            self._raw_metrics.append(metric)

    def record_counter(
        self,
        name: str,
        value: float = 1.0,
        dimensions: Optional[Dict[str, str]] = None,
        unit: str = "",
    ) -> None:
        self.record(
            Metric(
                name=name,
                value=value,
                dimensions=dimensions or {},
                unit=unit,
            )
        )

    def counter(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> Counter:
        return self._registry.counter(name, dimensions)

    def gauge(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> Gauge:
        return self._registry.gauge(name, dimensions)

    def histogram(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> Histogram:
        return self._registry.histogram(name, dimensions)

    def timer(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> Timer:
        return self._registry.timer(name, dimensions)

    def get_recent(self, since: Optional[datetime] = None) -> List[Metric]:
        with self._lock:
            if since is None:
                return list(self._raw_metrics)
            return [m for m in self._raw_metrics if m.timestamp >= since]

    def collect_registry_metrics(self) -> List[Dict[str, Any]]:
        return self._registry.collect_all()


class TimeWindowAggregator:
    def __init__(self, window_seconds: int = 60):
        self._window_seconds = window_seconds
        self._buckets: Dict[str, Deque[Metric]] = defaultdict(lambda: deque())
        self._lock = threading.Lock()

    def add(self, metric: Metric) -> None:
        key = self._make_key(metric.name, metric.dimensions)
        with self._lock:
            self._buckets[key].append(metric)
            self._cleanup_bucket(key, metric.timestamp)

    def aggregate(
        self,
        name: str,
        aggregation: AggregationType,
        dimensions: Optional[Dict[str, str]] = None,
    ) -> Optional[float]:
        key = self._make_key(name, dimensions or {})
        with self._lock:
            bucket = self._buckets.get(key)
            if not bucket:
                return None

            cutoff = datetime.now(timezone.utc).timestamp() - self._window_seconds
            values = [m.value for m in bucket if m.timestamp.timestamp() >= cutoff]

            if not values:
                return None

            return self._apply_aggregation(values, aggregation)

    def aggregate_all(
        self,
        aggregation: AggregationType,
    ) -> Dict[str, Dict[str, Any]]:
        result: Dict[str, Dict[str, Any]] = {}
        with self._lock:
            for key, bucket in self._buckets.items():
                cutoff = datetime.now(timezone.utc).timestamp() - self._window_seconds
                values = [m.value for m in bucket if m.timestamp.timestamp() >= cutoff]
                if values:
                    result[key] = {
                        "aggregation": aggregation.value,
                        "value": self._apply_aggregation(values, aggregation),
                        "window_seconds": self._window_seconds,
                    }
        return result

    def _make_key(self, name: str, dimensions: Dict[str, str]) -> str:
        dim_str = ",".join(f"{k}={v}" for k, v in sorted(dimensions.items()))
        return f"{name}:{dim_str}"

    def _cleanup_bucket(self, key: str, current_time: datetime) -> None:
        bucket = self._buckets[key]
        cutoff = current_time.timestamp() - self._window_seconds
        while bucket and bucket[0].timestamp.timestamp() < cutoff:
            bucket.popleft()

    def _apply_aggregation(self, values: List[float], aggregation: AggregationType) -> float:
        if aggregation == AggregationType.SUM:
            return sum(values)
        elif aggregation == AggregationType.COUNT:
            return float(len(values))
        elif aggregation == AggregationType.AVG:
            return mean(values)
        elif aggregation == AggregationType.MIN:
            return min(values)
        elif aggregation == AggregationType.MAX:
            return max(values)
        elif aggregation == AggregationType.LATEST:
            return values[-1]
        else:
            sorted_vals = sorted(values)
            percentiles = {
                AggregationType.P50: 0.50,
                AggregationType.P90: 0.90,
                AggregationType.P95: 0.95,
                AggregationType.P99: 0.99,
            }
            p = percentiles.get(aggregation, 0.5)
            index = int(len(sorted_vals) * p)
            if index >= len(sorted_vals):
                index = len(sorted_vals) - 1
            return sorted_vals[index]


class MetricsExporter:
    def __init__(self, collector: MetricCollector):
        self._collector = collector

    def export_json(self) -> str:
        data = {
            "timestamp": utc_now().isoformat(),
            "registry_metrics": self._collector.collect_registry_metrics(),
        }
        return json.dumps(data, indent=2)

    def export_prometheus_format(self) -> str:
        lines = []
        for metric in self._collector.collect_registry_metrics():
            metric_type = metric["type"]
            name = metric["name"]
            dims = metric.get("dimensions", {})
            label_str = ",".join(f'{k}="{v}"' for k, v in dims.items())
            labels = f"{{{label_str}}}" if label_str else ""

            if metric_type == "counter":
                lines.append(f"# TYPE {name} counter")
                lines.append(f"{name}{labels} {metric['value']}")
            elif metric_type == "gauge":
                lines.append(f"# TYPE {name} gauge")
                lines.append(f"{name}{labels} {metric['value']}")
            elif metric_type == "timer":
                lines.append(f"# TYPE {name} summary")
                lines.append(f"{name}_total{{{label_str}}} {metric['total']}")
                lines.append(f"{name}_count{{{label_str}}} {metric['count']}")
            elif metric_type == "histogram":
                lines.append(f"# TYPE {name} summary")
                lines.append(f"{name}_count{{{label_str}}} {metric['count']}")
                for p in ["p50", "p90", "p95", "p99"]:
                    if p in metric:
                        lines.append(f'{name}{{{label_str},quantile="{p[1:]}"}} {metric[p]}')

        return "\n".join(lines)


class Monitor:
    def __init__(self):
        self._collector = MetricCollector()
        self._aggregator = TimeWindowAggregator(window_seconds=300)
        self._exporter = MetricsExporter(self._collector)
        self._snapshots: List[SnapshotModel] = []
        self._lock = threading.Lock()

    @property
    def collector(self) -> MetricCollector:
        return self._collector

    @property
    def aggregator(self) -> TimeWindowAggregator:
        return self._aggregator

    @property
    def exporter(self) -> MetricsExporter:
        return self._exporter

    def increment(self, name: str, amount: int = 1, dimensions: Optional[Dict[str, str]] = None) -> None:
        counter = self._collector.counter(name, dimensions)
        counter.inc(amount)

    def set_gauge(self, name: str, value: float, dimensions: Optional[Dict[str, str]] = None) -> None:
        gauge = self._collector.gauge(name, dimensions)
        gauge.set(value)

    def observe(self, name: str, value: float, dimensions: Optional[Dict[str, str]] = None) -> None:
        hist = self._collector.histogram(name, dimensions)
        hist.record(value)
        self._aggregator.add(
            Metric(
                name=name,
                value=value,
                dimensions=dimensions or {},
            )
        )

    def record_latency(
        self,
        name: str,
        duration_ms: float,
        dimensions: Optional[Dict[str, str]] = None,
    ) -> None:
        timer = self._collector.timer(name, dimensions)
        timer.record(duration_ms)
        self.observe(name, duration_ms, dimensions)

    def timing(self, name: str, dimensions: Optional[Dict[str, str]] = None) -> Timer:
        return self._collector.timer(name, dimensions)

    def time_execution(
        self,
        name: str,
        dimensions: Optional[Dict[str, str]] = None,
    ) -> Timer:
        return self._collector.timer(name, dimensions)

    def get_metric(
        self,
        name: str,
        aggregation: AggregationType = AggregationType.LATEST,
        dimensions: Optional[Dict[str, str]] = None,
    ) -> Optional[float]:
        return self._aggregator.aggregate(name, aggregation, dimensions)

    def create_snapshot(
        self,
        dimensions: Optional[Dict[str, str]] = None,
    ) -> SnapshotModel:
        registry_metrics = self._collector.collect_registry_metrics()
        metrics_dict: Dict[str, float] = {}

        for m in registry_metrics:
            key = m["name"]
            if m["type"] in ("counter", "gauge"):
                metrics_dict[key] = float(m["value"])
            elif m["type"] == "timer":
                metrics_dict[f"{key}_avg_ms"] = float(m.get("avg_ms", 0))
                metrics_dict[f"{key}_count"] = float(m.get("count", 0))
            elif m["type"] == "histogram":
                for p in ["p50", "p90", "p95", "p99"]:
                    if p in m:
                        metrics_dict[f"{key}_{p}"] = float(m[p])

        snapshot = SnapshotModel(
            snapshot_id=f"snap_{uuid4().hex[:8]}",
            timestamp=utc_now(),
            metrics=metrics_dict,
            dimensions=dimensions or {},
        )

        with self._lock:
            self._snapshots.append(snapshot)

        return snapshot

    def get_snapshots(self, limit: int = 100) -> List[SnapshotModel]:
        with self._lock:
            return list(self._snapshots[-limit:])

    def export_metrics(self, format: str = "json") -> str:
        if format == "prometheus":
            return self._exporter.export_prometheus_format()
        return self._exporter.export_json()


_monitor_instance: Optional[Monitor] = None


def get_monitor() -> Monitor:
    global _monitor_instance
    if _monitor_instance is None:
        _monitor_instance = Monitor()
    return _monitor_instance
