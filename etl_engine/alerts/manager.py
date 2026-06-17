from __future__ import annotations

import asyncio
import logging

import redis

from etl_engine.config import settings

from .channels import Alert, AlertChannel
from .rules import AlertRule

logger = logging.getLogger(__name__)


class AlertManager:
    def __init__(
        self,
        channels: dict[str, AlertChannel],
        rules: list[AlertRule],
    ) -> None:
        self.channels = channels
        self.rules = rules
        self._redis = redis.from_url(settings.REDIS_URL, decode_responses=True)

    async def notify(self, alert: Alert) -> dict:
        result: dict = {
            "alert_type": alert.alert_type,
            "severity": alert.severity,
            "pipeline_name": alert.pipeline_name,
            "task_name": alert.task_name,
            "channels_notified": [],
            "channels_skipped": [],
            "cooldown_active": False,
        }
        matched_rules = [r for r in self.rules if self._matches_rule(alert, r)]
        if not matched_rules:
            logger.info("No matching rules for alert %s/%s", alert.pipeline_name, alert.alert_type)
            return result

        alert_key = self._generate_alert_key(alert)
        cooldown_minutes = min(r.cooldown_minutes for r in matched_rules)
        if self._check_cooldown(alert_key, cooldown_minutes):
            result["cooldown_active"] = True
            logger.info("Cooldown active for %s, skipping notification", alert_key)
            return result

        channel_names: set[str] = set()
        for rule in matched_rules:
            channel_names.update(rule.channels)

        send_tasks = []
        for name in channel_names:
            channel = self.channels.get(name)
            if channel is None:
                logger.warning("Channel '%s' not found, skipping", name)
                result["channels_skipped"].append(name)
                continue
            send_tasks.append(self._send_to_channel(name, channel, alert))

        outcomes = await asyncio.gather(*send_tasks, return_exceptions=True)
        for name, outcome in zip(channel_names, outcomes):
            if isinstance(outcome, Exception):
                logger.error("Channel %s raised exception: %s", name, outcome)
                result["channels_skipped"].append(name)
            elif outcome:
                result["channels_notified"].append(name)
            else:
                result["channels_skipped"].append(name)

        if result["channels_notified"]:
            self._set_cooldown(alert_key, cooldown_minutes)

        return result

    def _matches_rule(self, alert: Alert, rule: AlertRule) -> bool:
        if not rule.enabled:
            return False
        if alert.alert_type != rule.alert_type:
            return False
        if not rule.severity_met(alert.severity):
            return False
        return True

    def _check_cooldown(self, alert_key: str, cooldown_minutes: int) -> bool:
        return self._redis.exists(alert_key) > 0

    def _set_cooldown(self, alert_key: str, cooldown_minutes: int) -> None:
        self._redis.setex(alert_key, cooldown_minutes * 60, "1")

    def _generate_alert_key(self, alert: Alert) -> str:
        parts = [alert.alert_type, alert.pipeline_name]
        if alert.task_name:
            parts.append(alert.task_name)
        return f"etl:alert:cooldown:{':'.join(parts)}"

    async def _send_to_channel(
        self, name: str, channel: AlertChannel, alert: Alert
    ) -> bool:
        try:
            return await channel.send(alert)
        except Exception:
            logger.exception("Failed to send alert via %s", name)
            return False
