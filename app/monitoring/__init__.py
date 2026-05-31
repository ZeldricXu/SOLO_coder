"""
Monitoring and Statistics Module.
Implements metric collection, alert rule evaluation, and notification triggering.
"""

import asyncio
import time
from abc import ABC, abstractmethod
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Set
from statistics import mean, median, stdev

from app.logging import get_logger
from app.models import StatsSnapshot, AlertSeverity


class MetricType(str, Enum):
    COUNTER = "counter"
    GAUGE = "gauge"
    HISTOGRAM = "histogram"
    TIMER = "timer"


class ComparisonOperator(str, Enum):
    GREATER_THAN = "gt"
    GREATER_THAN_OR_EQUAL = "gte"
    LESS_THAN = "lt"
    LESS_THAN_OR_EQUAL = "lte"
    EQUALS = "eq"
    NOT_EQUALS = "neq"


class AggregationType(str, Enum):
    COUNT = "count"
    SUM = "sum"
    AVG = "avg"
    MIN = "min"
    MAX = "max"
    MEDIAN = "median"
    P50 = "p50"
    P90 = "p90"
    P95 = "p95"
    P99 = "p99"


@dataclass
class MetricData:
    name: str
    type: MetricType
    value: float
    timestamp: float = field(default_factory=time.time)
    tags: Dict[str, str] = field(default_factory=dict)


@dataclass
class AlertRule:
    rule_id: str
    name: str
    metric_name: str
    aggregation: AggregationType
    operator: ComparisonOperator
    threshold: float
    window_seconds: int
    severity: AlertSeverity
    enabled: bool = True
    description: str = ""
    tags: Dict[str, str] = field(default_factory=dict)


@dataclass
class Alert:
    alert_id: str
    rule_id: str
    rule_name: str
    metric_name: str
    severity: AlertSeverity
    value: float
    threshold: float
    timestamp: datetime = field(default_factory=datetime.utcnow)
    status: str = "firing"
    description: str = ""
    tags: Dict[str, str] = field(default_factory=dict)
    resolved_at: Optional[datetime] = None


class MetricStore:
    def __init__(self, max_history: int = 10000, retention_seconds: int = 3600):
        self._data: Dict[str, deque] = defaultdict(lambda: deque(maxlen=max_history))
        self._max_history = max_history
        self._retention_seconds = retention_seconds
        self._lock = asyncio.Lock()
    
    def _get_key(self, name: str, tags: Dict[str, str]) -> str:
        if not tags:
            return name
        sorted_tags = sorted(tags.items())
        tag_str = ",".join(f"{k}={v}" for k, v in sorted_tags)
        return f"{name}|{tag_str}"
    
    async def record(self, metric: MetricData):
        key = self._get_key(metric.name, metric.tags)
        self._data[key].append(metric)
        await self._cleanup()
    
    def record_sync(self, metric: MetricData):
        key = self._get_key(metric.name, metric.tags)
        self._data[key].append(metric)
    
    async def _cleanup(self):
        cutoff = time.time() - self._retention_seconds
        keys_to_remove = []
        for key, points in self._data.items():
            while points and points[0].timestamp < cutoff:
                points.popleft()
            if not points:
                keys_to_remove.append(key)
        for key in keys_to_remove:
            del self._data[key]
    
    def query(
        self,
        name: str,
        tags: Optional[Dict[str, str]] = None,
        start_time: Optional[float] = None,
        end_time: Optional[float] = None
    ) -> List[MetricData]:
        if tags:
            key = self._get_key(name, tags)
            points = list(self._data.get(key, []))
        else:
            points = []
            for key, values in self._data.items():
                if key.startswith(f"{name}|") or key == name:
                    points.extend(list(values))
        
        if start_time:
            points = [p for p in points if p.timestamp >= start_time]
        if end_time:
            points = [p for p in points if p.timestamp <= end_time]
        
        return sorted(points, key=lambda x: x.timestamp)
    
    def get_metric_names(self) -> List[str]:
        names: Set[str] = set()
        for key in self._data.keys():
            names.add(key.split("|")[0])
        return list(names)


class MetricsRegistry:
    def __init__(self):
        self._store = MetricStore()
        self._counters: Dict[str, float] = defaultdict(float)
        self._gauges: Dict[str, float] = {}
        self._logger = get_logger("metrics_registry")
    
    def counter(self, name: str, value: float = 1.0, tags: Optional[Dict[str, str]] = None):
        self._counters[name] += value
        metric = MetricData(
            name=name,
            type=MetricType.COUNTER,
            value=self._counters[name],
            tags=tags or {}
        )
        self._store.record_sync(metric)
        return metric
    
    def gauge(self, name: str, value: float, tags: Optional[Dict[str, str]] = None):
        self._gauges[name] = value
        metric = MetricData(
            name=name,
            type=MetricType.GAUGE,
            value=value,
            tags=tags or {}
        )
        self._store.record_sync(metric)
        return metric
    
    def histogram(self, name: str, value: float, tags: Optional[Dict[str, str]] = None):
        metric = MetricData(
            name=name,
            type=MetricType.HISTOGRAM,
            value=value,
            tags=tags or {}
        )
        self._store.record_sync(metric)
        return metric
    
    def timer(self, name: str):
        start = time.time()
        
        def record():
            elapsed = time.time() - start
            self.histogram(f"{name}.duration", elapsed * 1000)
            return elapsed
        
        return record
    
    def query(
        self,
        name: str,
        tags: Optional[Dict[str, str]] = None,
        window_seconds: Optional[int] = None
    ) -> List[MetricData]:
        start_time = None
        if window_seconds:
            start_time = time.time() - window_seconds
        return self._store.query(name, tags, start_time=start_time)
    
    def get_counter(self, name: str) -> float:
        return self._counters.get(name, 0.0)
    
    def get_gauge(self, name: str) -> float:
        return self._gauges.get(name, 0.0)
    
    def create_snapshot(
        self,
        metrics: List[str],
        dimensions: Optional[Dict[str, str]] = None
    ) -> StatsSnapshot:
        snapshot_data: Dict[str, float] = {}
        for metric_name in metrics:
            points = self._store.query(metric_name)
            if points:
                values = [p.value for p in points]
                snapshot_data[f"{metric_name}.count"] = len(values)
                snapshot_data[f"{metric_name}.avg"] = mean(values)
                snapshot_data[f"{metric_name}.max"] = max(values)
                snapshot_data[f"{metric_name}.min"] = min(values)
                if len(values) >= 2:
                    snapshot_data[f"{metric_name}.p99"] = sorted(values)[int(len(values) * 0.99)]
        
        return StatsSnapshot(
            snapshot_id=f"snap_{int(time.time())}",
            metrics=snapshot_data,
            dimensions=dimensions or {}
        )


class AlertEvaluator:
    def __init__(self, metrics_registry: MetricsRegistry):
        self._registry = metrics_registry
        self._rules: Dict[str, AlertRule] = {}
        self._active_alerts: Dict[str, Alert] = {}
        self._logger = get_logger("alert_evaluator")
        self._handlers: List[Callable[[Alert], None]] = []
    
    def add_rule(self, rule: AlertRule):
        self._rules[rule.rule_id] = rule
        self._logger.info("Alert rule added", rule_id=rule.rule_id, name=rule.name)
    
    def remove_rule(self, rule_id: str):
        if rule_id in self._rules:
            del self._rules[rule_id]
            self._logger.info("Alert rule removed", rule_id=rule_id)
    
    def list_rules(self) -> List[AlertRule]:
        return list(self._rules.values())
    
    def add_alert_handler(self, handler: Callable[[Alert], None]):
        self._handlers.append(handler)
    
    def _compare(self, value: float, operator: ComparisonOperator, threshold: float) -> bool:
        if operator == ComparisonOperator.GREATER_THAN:
            return value > threshold
        elif operator == ComparisonOperator.GREATER_THAN_OR_EQUAL:
            return value >= threshold
        elif operator == ComparisonOperator.LESS_THAN:
            return value < threshold
        elif operator == ComparisonOperator.LESS_THAN_OR_EQUAL:
            return value <= threshold
        elif operator == ComparisonOperator.EQUALS:
            return value == threshold
        elif operator == ComparisonOperator.NOT_EQUALS:
            return value != threshold
        return False
    
    def _aggregate(self, values: List[float], aggregation: AggregationType) -> float:
        if not values:
            return 0.0
        
        if aggregation == AggregationType.COUNT:
            return len(values)
        elif aggregation == AggregationType.SUM:
            return sum(values)
        elif aggregation == AggregationType.AVG:
            return mean(values)
        elif aggregation == AggregationType.MIN:
            return min(values)
        elif aggregation == AggregationType.MAX:
            return max(values)
        elif aggregation == AggregationType.MEDIAN:
            return median(values)
        elif aggregation == AggregationType.P50:
            return sorted(values)[int(len(values) * 0.50)]
        elif aggregation == AggregationType.P90:
            return sorted(values)[int(len(values) * 0.90)]
        elif aggregation == AggregationType.P95:
            return sorted(values)[int(len(values) * 0.95)]
        elif aggregation == AggregationType.P99:
            return sorted(values)[int(len(values) * 0.99)]
        
        return mean(values)
    
    def _create_alert(self, rule: AlertRule, value: float) -> Alert:
        return Alert(
            alert_id=f"alert_{rule.rule_id}_{int(time.time())}",
            rule_id=rule.rule_id,
            rule_name=rule.name,
            metric_name=rule.metric_name,
            severity=rule.severity,
            value=value,
            threshold=rule.threshold,
            description=rule.description,
            tags=rule.tags
        )
    
    def evaluate(self) -> List[Alert]:
        fired_alerts: List[Alert] = []
        
        for rule_id, rule in self._rules.items():
            if not rule.enabled:
                continue
            
            points = self._registry.query(
                rule.metric_name,
                window_seconds=rule.window_seconds
            )
            
            if not points:
                continue
            
            values = [p.value for p in points]
            current_value = self._aggregate(values, rule.aggregation)
            
            should_fire = self._compare(current_value, rule.operator, rule.threshold)
            alert_key = f"{rule.rule_id}"
            
            if should_fire:
                if alert_key not in self._active_alerts:
                    alert = self._create_alert(rule, current_value)
                    self._active_alerts[alert_key] = alert
                    fired_alerts.append(alert)
                    self._logger.warning(
                        "Alert fired",
                        rule_id=rule.rule_id,
                        value=current_value,
                        threshold=rule.threshold
                    )
                    for handler in self._handlers:
                        handler(alert)
            else:
                if alert_key in self._active_alerts:
                    alert = self._active_alerts[alert_key]
                    alert.status = "resolved"
                    alert.resolved_at = datetime.utcnow()
                    del self._active_alerts[alert_key]
                    self._logger.info(
                        "Alert resolved",
                        rule_id=rule.rule_id
                    )
                    for handler in self._handlers:
                        handler(alert)
        
        return fired_alerts
    
    def get_active_alerts(self) -> List[Alert]:
        return list(self._active_alerts.values())
    
    def resolve_alert(self, alert_id: str) -> bool:
        for key, alert in self._active_alerts.items():
            if alert.alert_id == alert_id:
                alert.status = "resolved"
                alert.resolved_at = datetime.utcnow()
                del self._active_alerts[key]
                return True
        return False


class MonitoringService:
    def __init__(self):
        self._registry = MetricsRegistry()
        self._evaluator = AlertEvaluator(self._registry)
        self._running = False
        self._eval_task: Optional[asyncio.Task] = None
        self._logger = get_logger("monitoring_service")
    
    @property
    def metrics(self) -> MetricsRegistry:
        return self._registry
    
    @property
    def alerts(self) -> AlertEvaluator:
        return self._evaluator
    
    async def _evaluation_loop(self, interval_seconds: float):
        while self._running:
            self._evaluator.evaluate()
            await asyncio.sleep(interval_seconds)
    
    def start(self, evaluation_interval_seconds: float = 60.0):
        if self._running:
            return
        self._running = True
        self._eval_task = asyncio.create_task(
            self._evaluation_loop(evaluation_interval_seconds)
        )
        self._logger.info(
            "Monitoring service started",
            interval=evaluation_interval_seconds
        )
    
    def stop(self):
        if not self._running:
            return
        self._running = False
        if self._eval_task:
            self._eval_task.cancel()
        self._logger.info("Monitoring service stopped")
    
    def is_running(self) -> bool:
        return self._running
