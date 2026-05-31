import time
import asyncio
import json
import uuid
from datetime import datetime
from typing import Any, Dict, Optional, List, Callable
from collections import defaultdict, deque
from dataclasses import dataclass
from ..models.metrics import MetricsSnapshot, MetricAlert
from ..config import settings
from .logging_module import get_logger

logger = get_logger(__name__)


@dataclass
class NotificationChannel:
    name: str
    channel_type: str
    config: Dict[str, Any]


class MetricsCollector:
    def __init__(self):
        self._counters: Dict[str, int] = defaultdict(int)
        self._gauges: Dict[str, float] = {}
        self._histograms: Dict[str, deque] = defaultdict(lambda: deque(maxlen=1000))
        self._timers: Dict[str, float] = {}
        self._snapshots: deque[MetricsSnapshot] = deque(maxlen=100)
        self._dimensions: Dict[str, str] = {}

    def increment(self, name: str, value: int = 1, dimensions: Optional[Dict[str, str]] = None) -> None:
        self._counters[name] += value

    def decrement(self, name: str, value: int = 1) -> None:
        self._counters[name] -= value

    def set_gauge(self, name: str, value: float) -> None:
        self._gauges[name] = value

    def record_histogram(self, name: str, value: float) -> None:
        self._histograms[name].append(value)

    def start_timer(self, name: str) -> None:
        self._timers[name] = time.time()

    def stop_timer(self, name: str) -> float:
        if name in self._timers:
            elapsed = time.time() - self._timers[name]
            self.record_histogram(f"{name}_duration", elapsed)
            del self._timers[name]
            return elapsed
        return 0.0

    def set_dimension(self, key: str, value: str) -> None:
        self._dimensions[key] = value

    def create_snapshot(self, snapshot_id: Optional[str] = None) -> MetricsSnapshot:
        metrics: Dict[str, float] = {}

        for name, value in self._counters.items():
            metrics[f"counter.{name}"] = float(value)

        for name, value in self._gauges.items():
            metrics[f"gauge.{name}"] = value

        for name, values in self._histograms.items():
            if values:
                sorted_values = sorted(values)
                metrics[f"histogram.{name}.count"] = float(len(values))
                metrics[f"histogram.{name}.avg"] = sum(values) / len(values)
                metrics[f"histogram.{name}.p50"] = sorted_values[len(values) // 2]
                metrics[f"histogram.{name}.p95"] = sorted_values[int(len(values) * 0.95)]
                metrics[f"histogram.{name}.p99"] = sorted_values[int(len(values) * 0.99)]
                metrics[f"histogram.{name}.min"] = sorted_values[0]
                metrics[f"histogram.{name}.max"] = sorted_values[-1]

        snapshot = MetricsSnapshot(
            snapshot_id=snapshot_id or f"snap_{uuid.uuid4().hex[:8]}",
            metrics=metrics,
            dimensions=self._dimensions.copy()
        )
        self._snapshots.append(snapshot)
        return snapshot

    def get_metric(self, name: str) -> Optional[float]:
        for snapshot in reversed(self._snapshots):
            if name in snapshot.metrics:
                return snapshot.metrics[name]
        return None

    def get_snapshots(self, limit: int = 10) -> List[MetricsSnapshot]:
        return list(self._snapshots)[-limit:]

    def reset(self) -> None:
        self._counters.clear()
        self._gauges.clear()
        self._histograms.clear()
        self._timers.clear()


class AlertEvaluator:
    def __init__(self, collector: MetricsCollector):
        self._collector = collector
        self._alerts: Dict[str, MetricAlert] = {}
        self._notification_channels: Dict[str, NotificationChannel] = {}
        self._evaluation_interval: float = 30.0
        self._running: bool = False

    def add_alert(self, alert: MetricAlert) -> None:
        self._alerts[alert.alert_id] = alert
        logger.info(f"Alert registered: {alert.alert_id} for metric {alert.metric_name}")

    def remove_alert(self, alert_id: str) -> None:
        if alert_id in self._alerts:
            del self._alerts[alert_id]

    def get_alerts(self) -> List[MetricAlert]:
        return list(self._alerts.values())

    def add_notification_channel(self, channel: NotificationChannel) -> None:
        self._notification_channels[channel.name] = channel

    def _evaluate_condition(self, value: float, threshold: float, operator: str) -> bool:
        operators = {
            "gt": lambda v, t: v > t,
            "lt": lambda v, t: v < t,
            "gte": lambda v, t: v >= t,
            "lte": lambda v, t: v <= t,
            "eq": lambda v, t: v == t,
            "ne": lambda v, t: v != t,
        }
        op_func = operators.get(operator)
        if not op_func:
            raise ValueError(f"Unknown operator: {operator}")
        return op_func(value, threshold)

    def evaluate(self) -> List[MetricAlert]:
        triggered_alerts: List[MetricAlert] = []

        for alert_id, alert in self._alerts.items():
            if not alert.triggered:
                current_value = self._collector.get_metric(alert.metric_name)
                if current_value is None:
                    continue

                alert.last_value = current_value
                is_triggered = self._evaluate_condition(
                    current_value, alert.threshold, alert.operator
                )

                if is_triggered:
                    alert.triggered = True
                    alert.triggered_at = datetime.utcnow()
                    triggered_alerts.append(alert)
                    self._send_notifications(alert)
                    logger.warning(
                        f"Alert {alert.alert_id} triggered: {alert.metric_name} = {current_value} "
                        f"(threshold: {alert.operator} {alert.threshold})"
                    )

        return triggered_alerts

    def _send_notifications(self, alert: MetricAlert) -> None:
        for channel_name in alert.notification_channels:
            channel = self._notification_channels.get(channel_name)
            if not channel:
                logger.warning(f"Notification channel not found: {channel_name}")
                continue

            try:
                self._send_to_channel(channel, alert)
            except Exception as e:
                logger.error(f"Failed to send notification to {channel_name}: {e}")

    def _send_to_channel(self, channel: NotificationChannel, alert: MetricAlert) -> None:
        message = {
            "alert_id": alert.alert_id,
            "metric_name": alert.metric_name,
            "current_value": alert.last_value,
            "threshold": alert.threshold,
            "operator": alert.operator,
            "severity": alert.severity,
            "triggered_at": alert.triggered_at.isoformat() if alert.triggered_at else None,
        }

        if channel.channel_type == "webhook":
            self._send_webhook(channel.config.get("url", ""), message)
        elif channel.channel_type == "email":
            logger.info(f"Email notification would be sent: {message}")
        elif channel.channel_type == "slack":
            logger.info(f"Slack notification would be sent: {message}")
        else:
            logger.info(f"Notification to {channel.name}: {json.dumps(message, indent=2)}")

    def _send_webhook(self, url: str, payload: Dict[str, Any]) -> None:
        try:
            import httpx
            httpx.post(url, json=payload, timeout=5.0)
        except ImportError:
            logger.info(f"Webhook notification (httpx not available): {payload}")
        except Exception as e:
            logger.error(f"Webhook send failed: {e}")

    def acknowledge_alert(self, alert_id: str) -> bool:
        if alert_id in self._alerts:
            self._alerts[alert_id].triggered = False
            self._alerts[alert_id].triggered_at = None
            logger.info(f"Alert {alert_id} acknowledged")
            return True
        return False

    async def start_evaluation_loop(self) -> None:
        self._running = True
        logger.info("Alert evaluation loop started")
        while self._running:
            self.evaluate()
            await asyncio.sleep(self._evaluation_interval)

    def stop_evaluation_loop(self) -> None:
        self._running = False
        logger.info("Alert evaluation loop stopped")


class MonitoringManager:
    _instance: Optional['MonitoringManager'] = None

    def __new__(cls) -> 'MonitoringManager':
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialize()
        return cls._instance

    def _initialize(self) -> None:
        self.collector = MetricsCollector()
        self.alert_evaluator = AlertEvaluator(self.collector)

    def get_collector(self) -> MetricsCollector:
        return self.collector

    def get_alert_evaluator(self) -> AlertEvaluator:
        return self.alert_evaluator


def get_monitoring() -> MonitoringManager:
    return MonitoringManager()
