import asyncio
import logging
import time
from typing import Dict, List, Optional, Any, Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
import threading
from collections import defaultdict

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import MonitoringException

logger = logging.getLogger(__name__)


class MetricType(str, Enum):
    COUNTER = "counter"
    GAUGE = "gauge"
    HISTOGRAM = "histogram"
    SUMMARY = "summary"


class AggregationType(str, Enum):
    SUM = "sum"
    AVG = "avg"
    MIN = "min"
    MAX = "max"
    COUNT = "count"
    P50 = "p50"
    P95 = "p95"
    P99 = "p99"


@dataclass
class MetricDataPoint:
    value: float
    timestamp: datetime = field(default_factory=datetime.now)
    labels: Dict[str, str] = field(default_factory=dict)


@dataclass
class Metric:
    name: str
    type: MetricType
    description: str = ""
    labels: List[str] = field(default_factory=list)
    data_points: List[MetricDataPoint] = field(default_factory=list)
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)


class MetricsCollector:
    def __init__(self, retention_seconds: int = 3600):
        self._metrics: Dict[str, Metric] = {}
        self._retention_seconds = retention_seconds
        self._lock = threading.RLock()

    def create_metric(
        self,
        name: str,
        metric_type: MetricType,
        description: str = "",
        labels: Optional[List[str]] = None
    ) -> Metric:
        with self._lock:
            if name in self._metrics:
                return self._metrics[name]

            metric = Metric(
                name=name,
                type=metric_type,
                description=description,
                labels=labels or []
            )
            self._metrics[name] = metric
            return metric

    def record(
        self,
        name: str,
        value: float,
        labels: Optional[Dict[str, str]] = None
    ) -> None:
        with self._lock:
            metric = self._metrics.get(name)
            if not metric:
                metric = self.create_metric(name, MetricType.GAUGE)

            point = MetricDataPoint(value=value, labels=labels or {})
            metric.data_points.append(point)
            metric.updated_at = datetime.now()

            self._cleanup_old_data(metric)

    def increment(
        self,
        name: str,
        amount: float = 1.0,
        labels: Optional[Dict[str, str]] = None
    ) -> None:
        with self._lock:
            metric = self._metrics.get(name)
            if not metric:
                metric = self.create_metric(name, MetricType.COUNTER)

            if metric.type != MetricType.COUNTER:
                raise MonitoringException(f"Metric {name} is not a counter")

            last_value = 0.0
            if metric.data_points:
                last_value = metric.data_points[-1].value

            point = MetricDataPoint(value=last_value + amount, labels=labels or {})
            metric.data_points.append(point)
            metric.updated_at = datetime.now()

            self._cleanup_old_data(metric)

    def _cleanup_old_data(self, metric: Metric) -> None:
        cutoff = datetime.now() - timedelta(seconds=self._retention_seconds)
        metric.data_points = [
            p for p in metric.data_points if p.timestamp >= cutoff
        ]

    def get_metric(self, name: str) -> Optional[Metric]:
        with self._lock:
            return self._metrics.get(name)

    def list_metrics(self) -> List[str]:
        with self._lock:
            return list(self._metrics.keys())

    def aggregate(
        self,
        name: str,
        aggregation: AggregationType,
        time_window_seconds: Optional[int] = None,
        label_filters: Optional[Dict[str, str]] = None
    ) -> float:
        with self._lock:
            metric = self._metrics.get(name)
            if not metric or not metric.data_points:
                return 0.0

            points = metric.data_points.copy()

        if time_window_seconds:
            cutoff = datetime.now() - timedelta(seconds=time_window_seconds)
            points = [p for p in points if p.timestamp >= cutoff]

        if label_filters:
            points = [
                p for p in points
                if all(p.labels.get(k) == v for k, v in label_filters.items())
            ]

        if not points:
            return 0.0

        values = [p.value for p in points]

        if aggregation == AggregationType.SUM:
            return sum(values)
        elif aggregation == AggregationType.AVG:
            return sum(values) / len(values)
        elif aggregation == AggregationType.MIN:
            return min(values)
        elif aggregation == AggregationType.MAX:
            return max(values)
        elif aggregation == AggregationType.COUNT:
            return len(values)
        elif aggregation == AggregationType.P50:
            return self._percentile(values, 50)
        elif aggregation == AggregationType.P95:
            return self._percentile(values, 95)
        elif aggregation == AggregationType.P99:
            return self._percentile(values, 99)

        return 0.0

    def _percentile(self, values: List[float], percentile: int) -> float:
        if not values:
            return 0.0
        sorted_values = sorted(values)
        index = int(len(sorted_values) * percentile / 100)
        index = min(index, len(sorted_values) - 1)
        return sorted_values[index]


class MonitoringManager:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._collector = MetricsCollector(
            retention_seconds=config.get("monitoring.retention_days", 30) * 86400
        )
        self._collection_interval = config.get("monitoring.collection_interval_seconds", 15)
        self._custom_collectors: Dict[str, Callable[[], Dict[str, float]]] = {}
        self._is_running = False
        self._collector_task: Optional[asyncio.Task] = None
        self._alert_rules: List[Dict[str, Any]] = []

    def register_collector(
        self,
        name: str,
        collector_fn: Callable[[], Dict[str, float]]
    ) -> None:
        self._custom_collectors[name] = collector_fn
        logger.info(f"Registered custom collector: {name}")

    def create_metric(
        self,
        name: str,
        metric_type: MetricType,
        description: str = "",
        labels: Optional[List[str]] = None
    ) -> Metric:
        return self._collector.create_metric(name, metric_type, description, labels)

    def record_metric(
        self,
        name: str,
        value: float,
        labels: Optional[Dict[str, str]] = None
    ) -> None:
        self._collector.record(name, value, labels)

    def increment_metric(
        self,
        name: str,
        amount: float = 1.0,
        labels: Optional[Dict[str, str]] = None
    ) -> None:
        self._collector.increment(name, amount, labels)

    def get_metric_value(
        self,
        name: str,
        aggregation: AggregationType = AggregationType.AVG,
        time_window_seconds: Optional[int] = None,
        labels: Optional[Dict[str, str]] = None
    ) -> float:
        return self._collector.aggregate(name, aggregation, time_window_seconds, labels)

    def get_metric(self, name: str) -> Optional[Metric]:
        return self._collector.get_metric(name)

    def list_metrics(self) -> List[str]:
        return self._collector.list_metrics()

    def add_alert_rule(
        self,
        metric_name: str,
        threshold: float,
        comparison: str,
        aggregation: AggregationType = AggregationType.AVG,
        time_window_seconds: int = 60,
        callback: Optional[Callable[[str, float], None]] = None
    ) -> str:
        rule_id = f"alert_{int(time.time())}"
        rule = {
            "rule_id": rule_id,
            "metric_name": metric_name,
            "threshold": threshold,
            "comparison": comparison,
            "aggregation": aggregation,
            "time_window_seconds": time_window_seconds,
            "callback": callback,
            "last_triggered": None
        }
        self._alert_rules.append(rule)
        return rule_id

    def remove_alert_rule(self, rule_id: str) -> None:
        self._alert_rules = [r for r in self._alert_rules if r["rule_id"] != rule_id]

    def _check_alert_rules(self) -> None:
        for rule in self._alert_rules:
            try:
                value = self._collector.aggregate(
                    rule["metric_name"],
                    rule["aggregation"],
                    rule["time_window_seconds"]
                )

                triggered = False
                if rule["comparison"] == ">" and value > rule["threshold"]:
                    triggered = True
                elif rule["comparison"] == "<" and value < rule["threshold"]:
                    triggered = True
                elif rule["comparison"] == ">=" and value >= rule["threshold"]:
                    triggered = True
                elif rule["comparison"] == "<=" and value <= rule["threshold"]:
                    triggered = True
                elif rule["comparison"] == "==" and value == rule["threshold"]:
                    triggered = True

                if triggered:
                    rule["last_triggered"] = datetime.now()

                    if rule["callback"]:
                        rule["callback"](rule["metric_name"], value)

                    self._event_bus.publish(Event(
                        event_type="monitoring.alert.triggered",
                        source="monitoring",
                        payload={
                            "rule_id": rule["rule_id"],
                            "metric_name": rule["metric_name"],
                            "value": value,
                            "threshold": rule["threshold"],
                            "comparison": rule["comparison"]
                        }
                    ))
            except Exception as e:
                logger.error(f"Error checking alert rule: {e}")

    async def _collect_metrics(self) -> None:
        while self._is_running:
            try:
                for name, collector_fn in self._custom_collectors.items():
                    try:
                        metrics = collector_fn()
                        for metric_name, value in metrics.items():
                            self.record_metric(metric_name, value)
                    except Exception as e:
                        logger.error(f"Error in collector {name}: {e}")

                self._check_alert_rules()

            except Exception as e:
                logger.error(f"Error in metrics collection: {e}")

            await asyncio.sleep(self._collection_interval)

    async def start(self) -> None:
        if self._is_running:
            return
        self._is_running = True
        self._collector_task = asyncio.create_task(self._collect_metrics())
        logger.info("Monitoring manager started")

    async def stop(self) -> None:
        self._is_running = False
        if self._collector_task:
            self._collector_task.cancel()
            try:
                await self._collector_task
            except asyncio.CancelledError:
                pass
        logger.info("Monitoring manager stopped")

    def get_dashboard_data(
        self,
        metrics: List[str],
        time_window_seconds: int = 3600
    ) -> Dict[str, Any]:
        dashboard = {}

        for metric_name in metrics:
            metric = self._collector.get_metric(metric_name)
            if not metric:
                continue

            dashboard[metric_name] = {
                "type": metric.type.value,
                "description": metric.description,
                "current": self._collector.aggregate(
                    metric_name, AggregationType.AVG, time_window_seconds
                ),
                "min": self._collector.aggregate(
                    metric_name, AggregationType.MIN, time_window_seconds
                ),
                "max": self._collector.aggregate(
                    metric_name, AggregationType.MAX, time_window_seconds
                ),
                "avg": self._collector.aggregate(
                    metric_name, AggregationType.AVG, time_window_seconds
                ),
                "sum": self._collector.aggregate(
                    metric_name, AggregationType.SUM, time_window_seconds
                ),
                "p95": self._collector.aggregate(
                    metric_name, AggregationType.P95, time_window_seconds
                ),
                "p99": self._collector.aggregate(
                    metric_name, AggregationType.P99, time_window_seconds
                )
            }

        return dashboard

    def get_stats(self) -> Dict[str, Any]:
        return {
            "total_metrics": len(self._collector.list_metrics()),
            "alert_rules": len(self._alert_rules),
            "collection_interval_seconds": self._collection_interval
        }
