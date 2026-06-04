import asyncio
from typing import Dict, Any, Optional

import httpx
from sqlalchemy.orm import Session

from app.models import AlertRule, AlertHistory
from app.config import settings
from app.services.email_service import email_service


class NotificationService:
    """告警通知服务，负责将告警信息推送到各个通知渠道。

    支持的通知渠道：
    - dingtalk: 钉钉机器人 Webhook
    - wechat: 企业微信机器人 Webhook
    - phone: 电话告警（通过外部API）
    - email: SMTP 邮件通知（HTML模板）
    - webhook: 通用 Webhook 回调

    通知渠道通过告警规则的 notification_channels 字段配置，
    多个渠道用逗号分隔，例如 "dingtalk,email"。
    """

    def __init__(self, db: Session):
        self.db = db

    async def send(self, notification_channels: str, alert: AlertHistory):
        """根据配置的通知渠道分发告警通知。

        :param notification_channels: 逗号分隔的渠道名称，如 "dingtalk,email"
        :param alert: 告警历史记录对象
        """
        channels = notification_channels.split(",")

        for channel in channels:
            channel = channel.strip()
            try:
                handler = self._get_handler(channel)
                if handler:
                    await handler(alert)
            except Exception as e:
                print(f"Failed to send {channel} notification: {e}")

    def _get_handler(self, channel: str):
        """获取指定渠道的通知处理函数。

        :param channel: 渠道名称
        :return: 异步处理函数，如果渠道未配置则返回 None
        """
        handlers = {
            "dingtalk": self._send_dingtalk if settings.dingtalk_webhook else None,
            "wechat": self._send_wechat if settings.wechat_webhook else None,
            "phone": self._send_phone if settings.phone_notify_url else None,
            "webhook": self._send_webhook,
            "email": self._send_email if email_service.is_configured() else None,
        }
        return handlers.get(channel)

    async def _send_dingtalk(self, alert: AlertHistory):
        """发送钉钉机器人通知。

        :param alert: 告警历史记录对象
        """
        level_colors = {
            "P0": "#ef4444",
            "P1": "#f97316",
            "P2": "#f59e0b",
            "P3": "#3b82f6",
        }
        payload = {
            "msgtype": "markdown",
            "markdown": {
                "title": f"[{alert.level}] 运维告警",
                "text": (
                    f"## <font color='{level_colors.get(alert.level, '#3b82f6')}'>"
                    f"{alert.level} 告警</font>\n\n"
                    f"**告警时间**: {alert.triggered_at}\n\n"
                    f"**告警内容**: {alert.message}\n\n"
                    f"**状态**: 🔴 触发中\n\n"
                    f"请相关人员及时处理！"
                ),
            },
        }
        async with httpx.AsyncClient() as client:
            await client.post(settings.dingtalk_webhook, json=payload)

    async def _send_wechat(self, alert: AlertHistory):
        """发送企业微信机器人通知。

        :param alert: 告警历史记录对象
        """
        payload = {
            "msgtype": "markdown",
            "markdown": {
                "content": (
                    f"## <font color='warning'>[{alert.level}] 运维告警</font>\n\n"
                    f"**告警时间**: {alert.triggered_at}\n\n"
                    f"**告警内容**: {alert.message}"
                ),
            },
        }
        async with httpx.AsyncClient() as client:
            await client.post(settings.wechat_webhook, json=payload)

    async def _send_phone(self, alert: AlertHistory):
        """发送电话告警通知。

        :param alert: 告警历史记录对象
        """
        payload = {
            "phone": "13800138000",
            "message": f"{alert.level}告警: {alert.message}",
            "alert_id": alert.id,
        }
        async with httpx.AsyncClient() as client:
            await client.post(settings.phone_notify_url, json=payload)

    async def _send_webhook(self, alert: AlertHistory):
        """发送通用 Webhook 通知（预留接口）。

        :param alert: 告警历史记录对象
        """
        pass

    async def _send_email(self, alert: AlertHistory):
        """发送 SMTP 邮件通知。

        邮件内容通过 Jinja2 模板渲染，包含告警摘要、指标快照和快速操作链接。

        :param alert: 告警历史记录对象
        """
        rule = self.db.query(AlertRule).filter(AlertRule.id == alert.rule_id).first()
        if rule:
            alert.rule_name = rule.name

        loop = asyncio.get_event_loop()
        await loop.run_in_executor(
            None,
            email_service.send_alert_email,
            alert,
            self._get_metrics_snapshot(alert),
        )

    def _get_metrics_snapshot(self, alert: AlertHistory) -> Dict[str, Any]:
        """获取告警触发时的指标快照，用于邮件通知。

        :param alert: 告警历史记录对象
        :return: 包含关键指标的字典，如 error_rate、avg_response_time 等
        """
        try:
            from app.services.alert_service import AlertService
            alert_service = AlertService(self.db)
            context = alert_service._get_metrics_context(AlertRule(window_seconds=300))
            return {
                "error_rate": context.get("error_rate", 0),
                "avg_response_time": context.get("avg_response_time", 0),
                "critical_count": context.get("critical_count", 0),
                "warning_count": context.get("warning_count", 0),
            }
        except Exception:
            return {}
