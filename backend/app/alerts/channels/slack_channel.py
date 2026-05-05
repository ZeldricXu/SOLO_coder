from typing import Dict, Any, Optional
from datetime import datetime
import aiohttp
import logging
import json

from app.alerts.channels.base import NotificationChannel
from app.core.config import settings
from app.core.models import (
    AlertNotification,
    AlertSeverity,
    NotificationChannelType
)

logger = logging.getLogger(__name__)


class SlackChannel(NotificationChannel):
    channel_type = NotificationChannelType.SLACK

    def __init__(
        self,
        name: str = "slack",
        enabled: bool = True,
        config: Dict[str, Any] = None
    ):
        super().__init__(name, enabled, config)

        self._webhook_url = config.get(
            'webhook_url',
            settings.SLACK_WEBHOOK_URL
        ) if config else settings.SLACK_WEBHOOK_URL

        self._channel = config.get(
            'channel',
            settings.SLACK_CHANNEL
        ) if config else settings.SLACK_CHANNEL

        self._session: Optional[aiohttp.ClientSession] = None

    async def initialize(self) -> bool:
        if self._webhook_url is None:
            logger.warning("Slack webhook URL not configured")
            self._initialized = False
            return False

        try:
            self._session = aiohttp.ClientSession()
            self._initialized = True
            logger.info(f"Slack channel '{self.name}' initialized")
            return True
        except Exception as e:
            logger.error(f"Failed to initialize Slack channel: {e}")
            self._initialized = False
            return False

    async def send(self, notification: AlertNotification) -> bool:
        if not self._session:
            logger.error("Slack channel not initialized")
            return False

        try:
            payload = self._build_payload(notification)

            async with self._session.post(
                self._webhook_url,
                json=payload,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                if response.status in (200, 204):
                    logger.info(
                        f"Sent Slack notification for alert: {notification.alert_id}"
                    )
                    return True
                else:
                    error_text = await response.text()
                    logger.error(
                        f"Slack notification failed with status {response.status}: {error_text}"
                    )
                    return False

        except Exception as e:
            logger.error(f"Failed to send Slack notification: {e}")
            return False

    def _build_payload(self, notification: AlertNotification) -> Dict[str, Any]:
        severity_colors = {
            AlertSeverity.INFO: "#36a64f",
            AlertSeverity.WARNING: "#ffc107",
            AlertSeverity.CRITICAL: "#dc3545"
        }

        color = severity_colors.get(notification.severity, "#6c757d")

        fields = [
            {
                "title": "指标ID",
                "value": notification.metric_id,
                "short": True
            },
            {
                "title": "指标名称",
                "value": notification.metric_name,
                "short": True
            },
            {
                "title": "当前值",
                "value": str(notification.value),
                "short": True
            },
            {
                "title": "触发条件",
                "value": notification.threshold_condition,
                "short": True
            }
        ]

        if notification.group_key:
            fields.append({
                "title": "分组",
                "value": json.dumps(notification.group_key, ensure_ascii=False),
                "short": True
            })

        attachments = [{
            "fallback": notification.message,
            "color": color,
            "pretext": f"【{notification.severity.value.upper()}】告警触发",
            "title": notification.message,
            "fields": fields,
            "footer": "DataFlow 实时数据流分析平台",
            "ts": int(notification.timestamp.timestamp())
        }]

        return {
            "channel": self._channel,
            "attachments": attachments,
            "text": f"告警: {notification.metric_name}"
        }

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()
            self._session = None
        self._initialized = False
        logger.info(f"Slack channel '{self.name}' closed")
