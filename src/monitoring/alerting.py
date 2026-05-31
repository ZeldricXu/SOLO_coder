from __future__ import annotations

import asyncio
import logging
from collections import deque
from datetime import datetime
from typing import Any, Callable, Deque, Dict, List, Optional

from src.common.utils import async_retry
from src.monitoring.models import (
    AlertCondition,
    AlertConditionOperator,
    AlertEvent,
    AlertRule,
    AlertSeverity,
    AlertStatus,
    MetricPoint,
    NotificationChannel,
    NotificationChannelType,
    AlertNotification,
)

logger = logging.getLogger(__name__)


class MetricStore:
    def __init__(self, max_points: int = 10000) -> None:
        self._metrics: Dict[str, Deque[MetricPoint]] = {}
        self.max_points = max_points

    def record(self, point: MetricPoint) -> None:
        key = self._get_key(point.metric, point.tags)
        if key not in self._metrics:
            self._metrics[key] = deque(maxlen=self.max_points)
        self._metrics[key].append(point)

    def get_points(
        self,
        metric: str,
        tags: Optional[Dict[str, str]] = None,
        since: Optional[datetime] = None,
    ) -> List[MetricPoint]:
        key = self._get_key(metric, tags or {})
        points = list(self._metrics.get(key, []))
        if since:
            points = [p for p in points if p.timestamp >= since]
        return points

    def get_aggregated(
        self,
        metric: str,
        tags: Optional[Dict[str, str]] = None,
        window_seconds: int = 60,
    ) -> Dict[str, float]:
        points = self.get_points(metric, tags)
        if not points:
            return {"avg": 0.0, "max": 0.0, "min": 0.0, "count": 0, "sum": 0.0}
        values = [p.value for p in points]
        return {
            "avg": sum(values) / len(values),
            "max": max(values),
            "min": min(values),
            "count": len(values),
            "sum": sum(values),
        }

    def _get_key(self, metric: str, tags: Dict[str, str]) -> str:
        sorted_tags = sorted(tags.items())
        tag_str = ",".join(f"{k}={v}" for k, v in sorted_tags)
        return f"{metric}:{tag_str}"


class AlertEvaluator:
    def __init__(self, metric_store: MetricStore) -> None:
        self.metric_store = metric_store
        self._active_alerts: Dict[str, AlertEvent] = {}
        self._alert_handlers: List[Callable[[AlertEvent], None]] = []

    def register_handler(self, handler: Callable[[AlertEvent], None]) -> None:
        self._alert_handlers.append(handler)

    def evaluate_condition(self, condition: AlertCondition, current_value: float) -> bool:
        op = condition.operator
        threshold = condition.threshold
        try:
            if op == AlertConditionOperator.GREATER_THAN:
                return current_value > threshold
            if op == AlertConditionOperator.LESS_THAN:
                return current_value < threshold
            if op == AlertConditionOperator.GREATER_EQUAL:
                return current_value >= threshold
            if op == AlertConditionOperator.LESS_EQUAL:
                return current_value <= threshold
            if op == AlertConditionOperator.EQUAL:
                return current_value == threshold
            if op == AlertConditionOperator.NOT_EQUAL:
                return current_value != threshold
            if op == AlertConditionOperator.IN:
                return current_value in threshold
            if op == AlertConditionOperator.NOT_IN:
                return current_value not in threshold
        except (TypeError, ValueError) as e:
            logger.error(f"Error evaluating condition: {e}")
            return False
        return False

    def evaluate_rule(self, rule: AlertRule) -> List[AlertEvent]:
        events: List[AlertEvent] = []
        for condition in rule.conditions:
            aggregated = self.metric_store.get_aggregated(
                condition.metric,
                window_seconds=condition.duration,
            )
            value = aggregated.get("avg", 0.0)
            if self.evaluate_condition(condition, value):
                alert_key = f"{rule.rule_id}:{condition.metric}"
                if alert_key in self._active_alerts:
                    continue
                event = AlertEvent(
                    rule_id=rule.rule_id,
                    rule_name=rule.name,
                    severity=rule.severity,
                    metric=condition.metric,
                    value=value,
                    threshold=condition.threshold,
                    operator=condition.operator.value,
                    message=f"Alert '{rule.name}' triggered: {condition.metric} {condition.operator.value} {condition.threshold}, current value: {value}",
                    labels=rule.labels,
                )
                self._active_alerts[alert_key] = event
                for handler in self._alert_handlers:
                    try:
                        handler(event)
                    except Exception as e:
                        logger.error(f"Error in alert handler: {e}")
                events.append(event)
            else:
                alert_key = f"{rule.rule_id}:{condition.metric}"
                if alert_key in self._active_alerts:
                    event = self._active_alerts[alert_key]
                    event.status = AlertStatus.RESOLVED
                    event.resolved_at = datetime.now()
                    del self._active_alerts[alert_key]
                    events.append(event)
        return events

    def get_active_alerts(self) -> List[AlertEvent]:
        return list(self._active_alerts.values())

    def acknowledge_alert(self, alert_id: str, user_id: str) -> Optional[AlertEvent]:
        for key, alert in self._active_alerts.items():
            if alert.alert_id == alert_id:
                alert.status = AlertStatus.ACKNOWLEDGED
                alert.acknowledged_at = datetime.now()
                alert.acknowledged_by = user_id
                return alert
        return None


class NotificationService:
    def __init__(self) -> None:
        self._channels: Dict[str, NotificationChannel] = {}
        self._notifications: List[AlertNotification] = []

    def register_channel(self, channel: NotificationChannel) -> None:
        self._channels[channel.channel_id] = channel

    def get_channel(self, channel_id: str) -> Optional[NotificationChannel]:
        return self._channels.get(channel_id)

    def list_channels(self) -> List[NotificationChannel]:
        return list(self._channels.values())

    @async_retry(max_attempts=3)
    async def _send_email(self, config: Dict[str, Any], message: str) -> None:
        logger.info(f"[EMAIL] Sending to {config.get('to')}: {message[:100]}")

    @async_retry(max_attempts=3)
    async def _send_slack(self, config: Dict[str, Any], message: str) -> None:
        import httpx
        webhook_url = config.get("webhook_url")
        if webhook_url:
            async with httpx.AsyncClient() as client:
                await client.post(webhook_url, json={"text": message})
        logger.info(f"[SLACK] {message[:100]}")

    @async_retry(max_attempts=3)
    async def _send_webhook(self, config: Dict[str, Any], message: str, alert: AlertEvent) -> None:
        import httpx
        url = config.get("url")
        headers = config.get("headers", {})
        if url:
            async with httpx.AsyncClient() as client:
                await client.post(url, json=alert.model_dump(), headers=headers)
        logger.info(f"[WEBHOOK] {message[:100]}")

    async def send(self, channel_id: str, alert: AlertEvent) -> AlertNotification:
        channel = self._channels.get(channel_id)
        if not channel or not channel.enabled:
            return AlertNotification(
                alert_id=alert.alert_id,
                channel_id=channel_id,
                channel_type=NotificationChannelType.WEBHOOK,
                status="failed",
                error="Channel not found or disabled",
            )
        message = f"[{alert.severity.upper()}] {alert.rule_name}: {alert.message}"
        try:
            if channel.type == NotificationChannelType.EMAIL:
                await self._send_email(channel.config, message)
            elif channel.type == NotificationChannelType.SLACK:
                await self._send_slack(channel.config, message)
            elif channel.type == NotificationChannelType.WEBHOOK:
                await self._send_webhook(channel.config, message, alert)
            else:
                logger.info(f"[{channel.type.upper()}] {message}")

            notification = AlertNotification(
                alert_id=alert.alert_id,
                channel_id=channel_id,
                channel_type=channel.type,
                status="sent",
            )
            self._notifications.append(notification)
            return notification
        except Exception as e:
            logger.error(f"Failed to send notification: {e}")
            return AlertNotification(
                alert_id=alert.alert_id,
                channel_id=channel_id,
                channel_type=channel.type,
                status="failed",
                error=str(e),
            )

    async def notify_alert(self, alert: AlertEvent, channel_ids: List[str]) -> List[AlertNotification]:
        return await asyncio.gather(*[self.send(cid, alert) for cid in channel_ids])


class AlertManager:
    def __init__(self) -> None:
        self.metric_store = MetricStore()
        self.evaluator = AlertEvaluator(self.metric_store)
        self.notification_service = NotificationService()
        self._rules: Dict[str, AlertRule] = {}
        self._running = False

        self.evaluator.register_handler(self._on_alert_triggered)

    def add_rule(self, rule: AlertRule) -> str:
        self._rules[rule.rule_id] = rule
        logger.info(f"Added alert rule: {rule.name}")
        return rule.rule_id

    def remove_rule(self, rule_id: str) -> bool:
        if rule_id in self._rules:
            del self._rules[rule_id]
            return True
        return False

    def get_rule(self, rule_id: str) -> Optional[AlertRule]:
        return self._rules.get(rule_id)

    def list_rules(self) -> List[AlertRule]:
        return list(self._rules.values())

    def record_metric(self, metric: str, value: float, tags: Optional[Dict[str, str]] = None) -> None:
        self.metric_store.record(MetricPoint(metric=metric, value=value, tags=tags or {}))

    def _on_alert_triggered(self, alert: AlertEvent) -> None:
        rule = self._rules.get(alert.rule_id)
        if rule and rule.notification_channels:
            asyncio.create_task(
                self.notification_service.notify_alert(alert, rule.notification_channels)
            )

    async def evaluate_all(self) -> List[AlertEvent]:
        all_events: List[AlertEvent] = []
        for rule in self._rules.values():
            if rule.enabled:
                events = self.evaluator.evaluate_rule(rule)
                all_events.extend(events)
        return all_events

    def get_active_alerts(self) -> List[AlertEvent]:
        return self.evaluator.get_active_alerts()

    def acknowledge_alert(self, alert_id: str, user_id: str) -> Optional[AlertEvent]:
        return self.evaluator.acknowledge_alert(alert_id, user_id)

    async def start(self, interval: int = 60) -> None:
        self._running = True
        while self._running:
            try:
                await self.evaluate_all()
            except Exception as e:
                logger.error(f"Error in alert evaluation: {e}")
            await asyncio.sleep(interval)

    def stop(self) -> None:
        self._running = False
