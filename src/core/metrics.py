from typing import Dict, Any, Optional
from datetime import datetime
from collections import defaultdict
import time
import threading
import logging

logger = logging.getLogger(__name__)


class MetricsCollector:
    def __init__(self):
        self._counters: Dict[str, int] = defaultdict(int)
        self._gauges: Dict[str, float] = {}
        self._histograms: Dict[str, list] = defaultdict(list)
        self._timers: Dict[str, float] = {}
        self._lock = threading.Lock()

    def increment(self, name: str, value: int = 1, labels: Optional[Dict[str, str]] = None) -> None:
        key = self._build_key(name, labels)
        with self._lock:
            self._counters[key] += value

    def decrement(self, name: str, value: int = 1, labels: Optional[Dict[str, str]] = None) -> None:
        key = self._build_key(name, labels)
        with self._lock:
            self._counters[key] -= value

    def gauge(self, name: str, value: float, labels: Optional[Dict[str, str]] = None) -> None:
        key = self._build_key(name, labels)
        with self._lock:
            self._gauges[key] = value

    def histogram(self, name: str, value: float, labels: Optional[Dict[str, str]] = None, max_samples: int = 1000) -> None:
        key = self._build_key(name, labels)
        with self._lock:
            samples = self._histograms[key]
            samples.append(value)
            if len(samples) > max_samples:
                self._histograms[key] = samples[-max_samples:]

    def start_timer(self, name: str, labels: Optional[Dict[str, str]] = None) -> str:
        timer_id = f"{name}_{int(time.time() * 1000)}"
        key = self._build_key(name, labels)
        with self._lock:
            self._timers[timer_id] = time.time()
        return timer_id

    def stop_timer(self, timer_id: str) -> Optional[float]:
        with self._lock:
            start_time = self._timers.pop(timer_id, None)
        if start_time is not None:
            duration = time.time() - start_time
            name = timer_id.rsplit("_", 1)[0]
            self.histogram(f"{name}_duration", duration)
            return duration
        return None

    def _build_key(self, name: str, labels: Optional[Dict[str, str]]) -> str:
        if not labels:
            return name
        label_str = ",".join(f"{k}={v}" for k, v in sorted(labels.items()))
        return f"{name}|{label_str}"

    def get_snapshot(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "counters": dict(self._counters),
                "gauges": dict(self._gauges),
                "histograms": {
                    k: self._compute_histogram_stats(v)
                    for k, v in self._histograms.items()
                },
                "timestamp": datetime.utcnow().isoformat()
            }

    def _compute_histogram_stats(self, samples: list) -> Dict[str, float]:
        if not samples:
            return {"count": 0, "avg": 0, "p50": 0, "p95": 0, "p99": 0, "min": 0, "max": 0}

        sorted_samples = sorted(samples)
        n = len(sorted_samples)
        return {
            "count": n,
            "avg": sum(samples) / n,
            "p50": self._percentile(sorted_samples, 50),
            "p95": self._percentile(sorted_samples, 95),
            "p99": self._percentile(sorted_samples, 99),
            "min": sorted_samples[0],
            "max": sorted_samples[-1],
        }

    def _percentile(self, sorted_data: list, percentile: float) -> float:
        if not sorted_data:
            return 0.0
        k = (len(sorted_data) - 1) * (percentile / 100.0)
        f = int(k)
        c = min(f + 1, len(sorted_data) - 1)
        if f == c:
            return sorted_data[f]
        return sorted_data[f] + (sorted_data[c] - sorted_data[f]) * (k - f)


_metrics_collector = MetricsCollector()


def get_metrics_collector() -> MetricsCollector:
    return _metrics_collector
