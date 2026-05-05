from typing import Dict, Any, Optional, List, Callable
from datetime import datetime, timedelta
import asyncio
import logging
import uuid
import json
import re

from app.alerts.channels import channel_manager, NotificationChannel
from app.core.config import settings
from app.core.models import (
    MetricResult,
    MetricConfig,
    AlertRule,
    AlertNotification,
    AlertSeverity,
    NotificationChannelType
)
from app.metrics.manager import metric_manager

logger = logging.getLogger(__name__)


class ConditionEvaluator:
    @staticmethod
    def evaluate(condition: str, value: float) -> bool:
        try:
            safe_globals = {
                'value': value,
                '__builtins__': {}
            }

            processed_condition = ConditionEvaluator._preprocess_condition(condition)

            result = eval(processed_condition, safe_globals)

            return bool(result)

        except Exception as e:
            logger.error(f"Error evaluating condition '{condition}': {e}")
            return False

    @staticmethod
    def _preprocess_condition(condition: str) -> str:
        condition = condition.replace('&&', ' and ')
        condition = condition.replace('||', ' or ')
        condition = condition.replace('!', ' not ')

        if re.search(r'\bvalue\s*<\s*value\b', condition):
            return 'False'
        if re.search(r'\bvalue\s*>\s*value\b', condition):
            return 'False'
        if re.search(r'\bvalue\s*==\s*value\b', condition):
            return 'True'

        return condition

    @staticmethod
    def validate_condition(condition: str) -> tuple[bool, str]:
        try:
            safe_globals = {'__builtins__': {}}
            test_value = 100.0
            safe_globals['value'] = test_value

            processed = ConditionEvaluator._preprocess_condition(condition)
            eval(processed, safe_globals)

            return True, "Valid condition"
        except SyntaxError as e:
            return False, f"Syntax error: {e}"
        except Exception as e:
            return False, f"Invalid condition: {e}"


class AlertEngine:
    def __init__(self):
        self._condition_evaluator = ConditionEvaluator()
        self._channel_manager = channel_manager

        self._alert_history: Dict[str, List[AlertNotification]] = {}
        self._suppression_windows: Dict[str, datetime] = {}
        self._default_suppression_seconds = 60

        self._on_alert_callback: Optional[Callable[[AlertNotification], None]] = None
        self._alert_queue: asyncio.Queue = asyncio.Queue()
        self._process_task: Optional[asyncio.Task] = None
        self._is_running = False

    def set_alert_callback(self, callback: Callable[[AlertNotification], None]):
        self._on_alert_callback = callback

    async def start(self):
        self._is_running = True

        await self._channel_manager.initialize_default_channels()

        self._process_task = asyncio.create_task(self._process_queue())
        logger.info("Alert engine started with multi-channel support")

    async def stop(self):
        self._is_running = False

        if self._process_task and not self._process_task.done():
            self._process_task.cancel()
            try:
                await self._process_task
            except asyncio.CancelledError:
                pass

        await self._channel_manager.close_all()
        logger.info("Alert engine stopped")

    async def check_metric(self, result: MetricResult, config: MetricConfig) -> List[AlertNotification]:
        if not config.alert_rules:
            return []

        triggered_alerts = []

        for rule in config.alert_rules:
            if self._condition_evaluator.evaluate(rule.condition, result.value):
                suppression_key = self._build_suppression_key(
                    result.metric_id,
                    rule.condition,
                    result.group_key
                )

                if self._is_suppressed(suppression_key):
                    logger.debug(f"Alert suppressed: {suppression_key}")
                    continue

                notification = self._create_notification(
                    result,
                    config,
                    rule
                )

                self._record_alert(suppression_key, notification)

                triggered_alerts.append(notification)

                if self._on_alert_callback:
                    try:
                        self._on_alert_callback(notification)
                    except Exception as e:
                        logger.error(f"Error in alert callback: {e}")

        return triggered_alerts

    def _build_suppression_key(
        self,
        metric_id: str,
        condition: str,
        group_key: Dict[str, Any]
    ) -> str:
        group_str = json.dumps(group_key, sort_keys=True)
        return f"{metric_id}:{condition}:{group_str}"

    def _is_suppressed(self, key: str) -> bool:
        if key not in self._suppression_windows:
            return False

        now = datetime.utcnow()
        window_end = self._suppression_windows[key]

        if now < window_end:
            return True

        del self._suppression_windows[key]
        return False

    def _create_notification(
        self,
        result: MetricResult,
        config: MetricConfig,
        rule: AlertRule
    ) -> AlertNotification:
        if rule.message_template:
            message = rule.message_template.format(
                metric_id=result.metric_id,
                metric_name=config.metric_name,
                value=result.value,
                condition=rule.condition
            )
        else:
            message = (
                f"指标 '{config.metric_name}' 触发告警: "
                f"当前值 {result.value} 满足条件 {rule.condition}"
            )

        return AlertNotification(
            alert_id=f"alert_{uuid.uuid4().hex[:8]}",
            metric_id=result.metric_id,
            metric_name=config.metric_name,
            severity=rule.severity,
            message=message,
            value=result.value,
            threshold_condition=rule.condition,
            timestamp=datetime.utcnow(),
            group_key=result.group_key.copy()
        )

    def _record_alert(self, key: str, notification: AlertNotification):
        if key not in self._alert_history:
            self._alert_history[key] = []

        self._alert_history[key].append(notification)

        if len(self._alert_history[key]) > 100:
            self._alert_history[key] = self._alert_history[key][-50:]

        self._suppression_windows[key] = (
            datetime.utcnow() + timedelta(seconds=self._default_suppression_seconds)
        )

    async def send_notification(
        self,
        notification: AlertNotification,
        channel_type: NotificationChannelType = None,
        channel_name: str = None
    ) -> bool:
        if channel_name:
            return await self._channel_manager.send_to_channel(
                notification,
                channel_name=channel_name
            )
        elif channel_type:
            return await self._channel_manager.send_to_channel(
                notification,
                channel_type=channel_type
            )
        else:
            return await self._channel_manager.send_to_channel(
                notification,
                channel_type=NotificationChannelType.SLACK
            )

    async def send_to_multiple_channels(
        self,
        notification: AlertNotification,
        channel_types: List[NotificationChannelType] = None,
        channel_names: List[str] = None
    ) -> Dict[str, bool]:
        return await self._channel_manager.send_to_channels(
            notification,
            channel_names=channel_names,
            channel_types=channel_types
        )

    async def queue_alert(self, notification: AlertNotification, channel_types: List[NotificationChannelType] = None):
        await self._alert_queue.put({
            'notification': notification,
            'channel_types': channel_types or [NotificationChannelType.SLACK]
        })

    async def _process_queue(self):
        while self._is_running:
            try:
                item = await asyncio.wait_for(
                    self._alert_queue.get(),
                    timeout=1.0
                )

                notification = item['notification']
                channel_types = item.get('channel_types', [NotificationChannelType.SLACK])

                results = await self.send_to_multiple_channels(
                    notification,
                    channel_types=channel_types
                )

                success_count = sum(1 for success in results.values() if success)
                if success_count > 0:
                    logger.info(
                        f"Alert {notification.alert_id} sent to {success_count} channels"
                    )
                else:
                    logger.warning(
                        f"Alert {notification.alert_id} failed to send to all channels"
                    )

            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error processing alert queue: {e}")

    def get_alert_history(
        self,
        metric_id: str = None,
        limit: int = 50
    ) -> List[AlertNotification]:
        all_alerts = []

        for key, alerts in self._alert_history.items():
            if metric_id is None or key.startswith(f"{metric_id}:"):
                all_alerts.extend(alerts)

        all_alerts.sort(key=lambda x: x.timestamp, reverse=True)
        return all_alerts[:limit]

    def get_status(self) -> Dict[str, Any]:
        channel_status = self._channel_manager.get_all_status()
        available_channels = self._channel_manager.get_available_channels()

        return {
            'total_alerts_recorded': sum(len(a) for a in self._alert_history.values()),
            'active_suppressions': len(self._suppression_windows),
            'queue_size': self._alert_queue.qsize(),
            'channels': channel_status,
            'available_channels': available_channels,
            'slack_configured': settings.SLACK_WEBHOOK_URL is not None,
            'email_configured': settings.SMTP_ENABLED and bool(settings.SMTP_HOST)
        }

    def get_channel_manager(self):
        return self._channel_manager


alert_engine = AlertEngine()
