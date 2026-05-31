import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional
from uuid import uuid4


class MetricType(str, Enum):
    COUNTER = "counter"
    GAUGE = "gauge"
    HISTOGRAM = "histogram"
    TIMER = "timer"


@dataclass
class MetricData:
    name: str
    type: MetricType
    value: float
    labels: Dict[str, str] = field(default_factory=dict)
    timestamp: datetime = field(default_factory=datetime.utcnow)


@dataclass
class MetricAggregate:
    name: str
    type: MetricType
    count: int = 0
    sum: float = 0.0
    min: float = float("inf")
    max: float = float("-inf")
    avg: float = 0.0
    p50: float = 0.0
    p95: float = 0.0
    p99: float = 0.0
    labels: Dict[str, str] = field(default_factory=dict)


class MetricsCollector:
    def __init__(self, max_history_seconds: int = 3600, use_plugins: bool = True):
        self.max_history_seconds = max_history_seconds
        self._metrics: Dict[str, deque] = defaultdict(lambda: deque(maxlen=10000))
        self._counters: Dict[str, float] = defaultdict(float)
        self._gauges: Dict[str, float] = {}
        self._histograms: Dict[str, List[float]] = defaultdict(list)
        self._lock = threading.Lock()
        self._snapshots: deque = deque(maxlen=100)
        self._use_plugins = use_plugins

    def _get_key(self, name: str, labels: Optional[Dict[str, str]] = None) -> str:
        if not labels:
            return name
        sorted_labels = sorted(labels.items())
        label_str = ",".join(f"{k}={v}" for k, v in sorted_labels)
        return f"{name}[{label_str}]"

    def _notify_plugin_counter(self, name: str, value: float, labels: Dict[str, str], ts: datetime) -> None:
        if not self._use_plugins:
            return
        try:
            from app.monitoring.plugin import get_plugin_manager
            manager = get_plugin_manager()
            manager.notify_counter(name, value, labels, ts)
        except Exception:
            pass

    def _notify_plugin_gauge(self, name: str, value: float, labels: Dict[str, str], ts: datetime) -> None:
        if not self._use_plugins:
            return
        try:
            from app.monitoring.plugin import get_plugin_manager
            manager = get_plugin_manager()
            manager.notify_gauge(name, value, labels, ts)
        except Exception:
            pass

    def _notify_plugin_histogram(self, name: str, value: float, labels: Dict[str, str], ts: datetime) -> None:
        if not self._use_plugins:
            return
        try:
            from app.monitoring.plugin import get_plugin_manager
            manager = get_plugin_manager()
            manager.notify_histogram(name, value, labels, ts)
        except Exception:
            pass

    def _notify_plugin_snapshot(self, snapshot: Dict[str, Any]) -> None:
        if not self._use_plugins:
            return
        try:
            from app.monitoring.plugin import get_plugin_manager
            manager = get_plugin_manager()
            manager.notify_snapshot(snapshot)
        except Exception:
            pass

    def increment_counter(self, name: str, value: float = 1.0, labels: Optional[Dict[str, str]] = None) -> None:
        labels_dict = labels or {}
        key = self._get_key(name, labels_dict)
        ts = datetime.utcnow()
        with self._lock:
            self._counters[key] += value
            self._metrics[key].append(MetricData(
                name=name,
                type=MetricType.COUNTER,
                value=value,
                labels=labels_dict,
                timestamp=ts
            ))
        self._notify_plugin_counter(name, value, labels_dict, ts)

    def set_gauge(self, name: str, value: float, labels: Optional[Dict[str, str]] = None) -> None:
        labels_dict = labels or {}
        key = self._get_key(name, labels_dict)
        ts = datetime.utcnow()
        with self._lock:
            self._gauges[key] = value
            self._metrics[key].append(MetricData(
                name=name,
                type=MetricType.GAUGE,
                value=value,
                labels=labels_dict,
                timestamp=ts
            ))
        self._notify_plugin_gauge(name, value, labels_dict, ts)

    def record_histogram(self, name: str, value: float, labels: Optional[Dict[str, str]] = None) -> None:
        labels_dict = labels or {}
        key = self._get_key(name, labels_dict)
        ts = datetime.utcnow()
        with self._lock:
            self._histograms[key].append(value)
            if len(self._histograms[key]) > 10000:
                self._histograms[key] = self._histograms[key][-5000:]
            self._metrics[key].append(MetricData(
                name=name,
                type=MetricType.HISTOGRAM,
                value=value,
                labels=labels_dict,
                timestamp=ts
            ))
        self._notify_plugin_histogram(name, value, labels_dict, ts)

    def record_timer(self, name: str, duration_ms: float, labels: Optional[Dict[str, str]] = None) -> None:
        self.record_histogram(name, duration_ms, labels)

    def timeit(self, name: str, labels: Optional[Dict[str, str]] = None):
        class Timer:
            def __init__(self, collector, metric_name, metric_labels):
                self.collector = collector
                self.metric_name = metric_name
                self.metric_labels = metric_labels
                self.start_time = None

            def __enter__(self):
                self.start_time = time.perf_counter()
                return self

            def __exit__(self, exc_type, exc_val, exc_tb):
                elapsed = (time.perf_counter() - self.start_time) * 1000
                self.collector.record_timer(self.metric_name, elapsed, self.metric_labels)

        return Timer(self, name, labels)

    def get_counter(self, name: str, labels: Optional[Dict[str, str]] = None) -> float:
        key = self._get_key(name, labels)
        with self._lock:
            return self._counters.get(key, 0.0)

    def get_gauge(self, name: str, labels: Optional[Dict[str, str]] = None) -> Optional[float]:
        key = self._get_key(name, labels)
        with self._lock:
            return self._gauges.get(key)

    def get_histogram_stats(self, name: str, labels: Optional[Dict[str, str]] = None) -> Optional[MetricAggregate]:
        key = self._get_key(name, labels)
        with self._lock:
            values = self._histograms.get(key, [])
            if not values:
                return None
            sorted_values = sorted(values)
            n = len(sorted_values)
            return MetricAggregate(
                name=name,
                type=MetricType.HISTOGRAM,
                count=n,
                sum=sum(values),
                min=sorted_values[0],
                max=sorted_values[-1],
                avg=sum(values) / n,
                p50=sorted_values[int(n * 0.5)],
                p95=sorted_values[int(n * 0.95)] if n > 1 else sorted_values[-1],
                p99=sorted_values[int(n * 0.99)] if n > 1 else sorted_values[-1],
                labels=labels or {}
            )

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            snapshot_data = {
                "timestamp": datetime.utcnow().isoformat(),
                "counters": {
                    k: v for k, v in self._counters.items()
                },
                "gauges": {
                    k: v for k, v in self._gauges.items()
                },
                "histograms": {}
            }
            for key, values in self._histograms.items():
                if values:
                    sorted_values = sorted(values)
                    n = len(sorted_values)
                    snapshot_data["histograms"][key] = {
                        "count": n,
                        "sum": sum(values),
                        "min": sorted_values[0],
                        "max": sorted_values[-1],
                        "avg": sum(values) / n,
                        "p50": sorted_values[int(n * 0.5)],
                        "p95": sorted_values[int(n * 0.95)] if n > 1 else sorted_values[-1],
                        "p99": sorted_values[int(n * 0.99)] if n > 1 else sorted_values[-1],
                    }
        self._snapshots.append(snapshot_data)
        self._notify_plugin_snapshot(snapshot_data)
        return snapshot_data

    def get_all_counters(self) -> Dict[str, float]:
        with self._lock:
            return dict(self._counters)

    def get_all_gauges(self) -> Dict[str, float]:
        with self._lock:
            return dict(self._gauges)

    def reset(self) -> None:
        with self._lock:
            self._counters.clear()
            self._gauges.clear()
            self._histograms.clear()
            self._metrics.clear()

    def export_prometheus(self) -> str:
        lines: List[str] = []
        with self._lock:
            for key, value in self._counters.items():
                name, labels_str = self._parse_key(key)
                lines.append(f"# HELP {name} Counter metric")
                lines.append(f"# TYPE {name} counter")
                if labels_str:
                    lines.append(f"{name}{{{labels_str}}} {value}")
                else:
                    lines.append(f"{name} {value}")

            for key, value in self._gauges.items():
                name, labels_str = self._parse_key(key)
                lines.append(f"# HELP {name} Gauge metric")
                lines.append(f"# TYPE {name} gauge")
                if labels_str:
                    lines.append(f"{name}{{{labels_str}}} {value}")
                else:
                    lines.append(f"{name} {value}")

        return "\n".join(lines) + "\n"

    @staticmethod
    def _parse_key(key: str) -> tuple:
        if "[" in key and "]" in key:
            name = key[:key.index("[")]
            labels_part = key[key.index("[")+1:key.index("]")]
            label_pairs = []
            for pair in labels_part.split(","):
                if "=" in pair:
                    k, v = pair.split("=", 1)
                    label_pairs.append(f'{k}="{v}"')
            labels_str = ",".join(label_pairs)
            return name, labels_str
        return key, ""


_metrics_collector_instance: Optional[MetricsCollector] = None
_metrics_lock = threading.Lock()


def get_metrics_collector() -> MetricsCollector:
    global _metrics_collector_instance
    if _metrics_collector_instance is None:
        with _metrics_lock:
            if _metrics_collector_instance is None:
                _metrics_collector_instance = MetricsCollector()
    return _metrics_collector_instance


def record_metric(
    metric_type: MetricType,
    name: str,
    value: float = 1.0,
    labels: Optional[Dict[str, str]] = None
) -> None:
    collector = get_metrics_collector()
    if metric_type == MetricType.COUNTER:
        collector.increment_counter(name, value, labels)
    elif metric_type == MetricType.GAUGE:
        collector.set_gauge(name, value, labels)
    elif metric_type in (MetricType.HISTOGRAM, MetricType.TIMER):
        collector.record_histogram(name, value, labels)
