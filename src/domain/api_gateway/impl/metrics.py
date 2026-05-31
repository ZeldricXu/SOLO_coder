from typing import Dict, Any, Optional, List
from datetime import datetime, timedelta
from collections import defaultdict
import time
import asyncio
import logging

logger = logging.getLogger(__name__)


class RequestMetrics:
    def __init__(self):
        self._total_requests = 0
        self._success_count = 0
        self._error_count = 0
        self._timing_history: List[float] = []
        self._status_codes: Dict[int, int] = defaultdict(int)
        self._route_stats: Dict[str, Dict[str, Any]] = defaultdict(lambda: {
            "count": 0,
            "total_time": 0.0,
            "min_time": float("inf"),
            "max_time": 0.0,
            "error_count": 0,
        })
        self._lock = asyncio.Lock()

    async def record_request(
        self,
        route_id: str,
        duration: float,
        status_code: int,
        success: bool,
    ) -> None:
        async with self._lock:
            self._total_requests += 1
            self._timing_history.append(duration)

            if len(self._timing_history) > 10000:
                self._timing_history = self._timing_history[-5000:]

            self._status_codes[status_code] += 1

            if success:
                self._success_count += 1
            else:
                self._error_count += 1

            route_stat = self._route_stats[route_id]
            route_stat["count"] += 1
            route_stat["total_time"] += duration
            route_stat["min_time"] = min(route_stat["min_time"], duration)
            route_stat["max_time"] = max(route_stat["max_time"], duration)
            if not success:
                route_stat["error_count"] += 1

    def get_summary(self) -> Dict[str, Any]:
        avg_time = sum(self._timing_history) / len(self._timing_history) if self._timing_history else 0
        sorted_times = sorted(self._timing_history)

        p95 = 0
        p99 = 0
        if sorted_times:
            p95_idx = int(len(sorted_times) * 0.95)
            p99_idx = int(len(sorted_times) * 0.99)
            p95 = sorted_times[min(p95_idx, len(sorted_times) - 1)]
            p99 = sorted_times[min(p99_idx, len(sorted_times) - 1)]

        return {
            "total_requests": self._total_requests,
            "success_count": self._success_count,
            "error_count": self._error_count,
            "success_rate": self._success_count / self._total_requests if self._total_requests > 0 else 0,
            "avg_latency_ms": round(avg_time * 1000, 2),
            "p95_latency_ms": round(p95 * 1000, 2),
            "p99_latency_ms": round(p99 * 1000, 2),
            "status_codes": dict(self._status_codes),
            "route_count": len(self._route_stats),
        }

    def get_route_stats(self, route_id: Optional[str] = None) -> Dict[str, Any]:
        if route_id:
            stat = self._route_stats.get(route_id)
            if not stat:
                return {}
            avg = stat["total_time"] / stat["count"] if stat["count"] > 0 else 0
            return {
                "route_id": route_id,
                "count": stat["count"],
                "avg_latency_ms": round(avg * 1000, 2),
                "min_latency_ms": round(stat["min_time"] * 1000, 2) if stat["min_time"] != float("inf") else 0,
                "max_latency_ms": round(stat["max_time"] * 1000, 2),
                "error_count": stat["error_count"],
                "error_rate": stat["error_count"] / stat["count"] if stat["count"] > 0 else 0,
            }

        return {
            route_id: {
                "count": stat["count"],
                "avg_latency_ms": round(stat["total_time"] / stat["count"] * 1000, 2) if stat["count"] > 0 else 0,
                "error_count": stat["error_count"],
            }
            for route_id, stat in self._route_stats.items()
        }


class PrometheusExporter:
    def __init__(self, app_name: str = "api_gateway"):
        self._app_name = app_name
        self._counters: Dict[str, float] = defaultdict(float)
        self._gauges: Dict[str, float] = {}
        self._histograms: Dict[str, List[float]] = defaultdict(list)
        self._label_templates: Dict[str, List[str]] = {}

    def increment_counter(self, name: str, value: float = 1.0, labels: Optional[Dict[str, str]] = None) -> None:
        key = self._make_key(name, labels)
        self._counters[key] += value

    def set_gauge(self, name: str, value: float, labels: Optional[Dict[str, str]] = None) -> None:
        key = self._make_key(name, labels)
        self._gauges[key] = value

    def observe_histogram(self, name: str, value: float, labels: Optional[Dict[str, str]] = None) -> None:
        key = self._make_key(name, labels)
        self._histograms[key].append(value)
        if len(self._histograms[key]) > 1000:
            self._histograms[key] = self._histograms[key][-500:]

    def _make_key(self, name: str, labels: Optional[Dict[str, str]] = None) -> str:
        if labels:
            label_str = ",".join(f"{k}={v}" for k, v in sorted(labels.items()))
            return f"{name}{{{label_str}}}"
        return name

    def generate_metrics(self) -> str:
        lines = []

        for key, value in self._counters.items():
            lines.append(f"# TYPE {self._app_name}_{key.split('{')[0]} counter")
            lines.append(f"{self._app_name}_{key} {value}")

        for key, value in self._gauges.items():
            lines.append(f"# TYPE {self._app_name}_{key.split('{')[0]} gauge")
            lines.append(f"{self._app_name}_{key} {value}")

        for key, values in self._histograms.items():
            name = key.split("{")[0]
            lines.append(f"# TYPE {self._app_name}_{name} histogram")
            if values:
                sorted_values = sorted(values)
                for quantile, label in [(0.5, "0.5"), (0.9, "0.9"), (0.99, "0.99")]:
                    idx = int(len(sorted_values) * quantile)
                    val = sorted_values[min(idx, len(sorted_values) - 1)]
                    lines.append(f'{self._app_name}_{key} {{{{quantile="{label}"}}}} {val}')
                lines.append(f"{self._app_name}_{key}_sum {sum(values)}")
                lines.append(f"{self._app_name}_{key}_count {len(values)}")

        return "\n".join(lines)

    def reset(self) -> None:
        self._counters.clear()
        self._gauges.clear()
        self._histograms.clear()
