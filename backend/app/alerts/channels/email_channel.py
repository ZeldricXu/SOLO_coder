from typing import Dict, Any, Optional, List
from datetime import datetime
import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from email.header import Header
import logging
import json
import asyncio

from app.alerts.channels.base import NotificationChannel
from app.core.config import settings
from app.core.models import (
    AlertNotification,
    AlertSeverity,
    NotificationChannelType
)

logger = logging.getLogger(__name__)


class EmailChannel(NotificationChannel):
    channel_type = NotificationChannelType.EMAIL

    def __init__(
        self,
        name: str = "email",
        enabled: bool = True,
        config: Dict[str, Any] = None
    ):
        super().__init__(name, enabled, config)

        config = config or {}

        self._smtp_host = config.get('smtp_host', settings.SMTP_HOST)
        self._smtp_port = config.get('smtp_port', settings.SMTP_PORT)
        self._smtp_user = config.get('smtp_user', settings.SMTP_USER)
        self._smtp_password = config.get('smtp_password', settings.SMTP_PASSWORD)
        self._from_addr = config.get('from_addr', settings.SMTP_FROM)
        self._to_addrs = config.get('to_addrs', settings.SMTP_TO)
        self._use_tls = config.get('use_tls', settings.SMTP_USE_TLS)

        self._connection: Optional[smtplib.SMTP] = None
        self._last_connection_check: Optional[datetime] = None
        self._connection_timeout = 60

    async def initialize(self) -> bool:
        if not all([self._smtp_host, self._from_addr, self._to_addrs]):
            logger.warning("Email channel configuration incomplete")
            self._initialized = False
            return False

        if not settings.SMTP_ENABLED:
            logger.info("Email channel is disabled in settings")
            self._initialized = False
            return False

        try:
            connected = await self._ensure_connection()
            self._initialized = connected

            if self._initialized:
                logger.info(f"Email channel '{self.name}' initialized")

            return self._initialized

        except Exception as e:
            logger.error(f"Failed to initialize Email channel: {e}")
            self._initialized = False
            return False

    async def _ensure_connection(self) -> bool:
        now = datetime.utcnow()

        if self._connection and self._last_connection_check:
            elapsed = (now - self._last_connection_check).total_seconds()
            if elapsed < self._connection_timeout:
                return True

        try:
            if self._connection:
                try:
                    self._connection.quit()
                except Exception:
                    pass

            loop = asyncio.get_running_loop()

            def connect_smtp():
                if self._use_tls:
                    conn = smtplib.SMTP(self._smtp_host, self._smtp_port)
                    conn.ehlo()
                    conn.starttls()
                    conn.ehlo()
                else:
                    conn = smtplib.SMTP(self._smtp_host, self._smtp_port)
                    conn.ehlo()

                if self._smtp_user and self._smtp_password:
                    conn.login(self._smtp_user, self._smtp_password)

                return conn

            self._connection = await loop.run_in_executor(None, connect_smtp)
            self._last_connection_check = now

            return True

        except Exception as e:
            logger.error(f"Failed to connect to SMTP server: {e}")
            self._connection = None
            return False

    async def send(self, notification: AlertNotification) -> bool:
        if not await self._ensure_connection():
            logger.error("Email channel not connected")
            return False

        try:
            message = self._build_message(notification)

            loop = asyncio.get_running_loop()

            def send_email():
                to_addrs = self._to_addrs if isinstance(self._to_addrs, list) else [self._to_addrs]
                self._connection.sendmail(
                    self._from_addr,
                    to_addrs,
                    message.as_string()
                )

            await loop.run_in_executor(None, send_email)

            logger.info(
                f"Sent Email notification for alert: {notification.alert_id}"
            )
            return True

        except Exception as e:
            logger.error(f"Failed to send Email notification: {e}")
            self._connection = None
            return False

    def _build_message(self, notification: AlertNotification) -> MIMEMultipart:
        msg = MIMEMultipart('alternative')

        msg['From'] = self._from_addr
        msg['To'] = ', '.join(
            self._to_addrs if isinstance(self._to_addrs, list) else [self._to_addrs]
        )

        subject_prefix = self._get_severity_prefix(notification.severity)
        subject = f"{subject_prefix} DataFlow 告警: {notification.metric_name}"
        msg['Subject'] = Header(subject, 'utf-8')

        html_content = self._build_html_content(notification)
        text_content = self._build_text_content(notification)

        msg.attach(MIMEText(text_content, 'plain', 'utf-8'))
        msg.attach(MIMEText(html_content, 'html', 'utf-8'))

        return msg

    def _get_severity_prefix(self, severity: AlertSeverity) -> str:
        prefixes = {
            AlertSeverity.INFO: "[信息]",
            AlertSeverity.WARNING: "[警告]",
            AlertSeverity.CRITICAL: "[严重]"
        }
        return prefixes.get(severity, "[告警]")

    def _get_severity_color(self, severity: AlertSeverity) -> str:
        colors = {
            AlertSeverity.INFO: "#36a64f",
            AlertSeverity.WARNING: "#ffc107",
            AlertSeverity.CRITICAL: "#dc3545"
        }
        return colors.get(severity, "#6c757d")

    def _build_html_content(self, notification: AlertNotification) -> str:
        color = self._get_severity_color(notification.severity)

        group_key_html = ""
        if notification.group_key:
            group_key_html = f"""
            <tr>
                <td style="padding: 8px; border-bottom: 1px solid #eee;"><strong>分组信息:</strong></td>
                <td style="padding: 8px; border-bottom: 1px solid #eee;">
                    <code>{json.dumps(notification.group_key, ensure_ascii=False, indent=2)}</code>
                </td>
            </tr>
            """

        html = f"""
        <html>
        <head>
            <style>
                body {{ font-family: Arial, sans-serif; line-height: 1.6; }}
                .header {{
                    background-color: {color};
                    color: white;
                    padding: 15px;
                    border-radius: 5px 5px 0 0;
                }}
                .content {{ padding: 20px; border: 1px solid #ddd; border-top: none; }}
                .message {{
                    font-size: 16px;
                    font-weight: bold;
                    margin-bottom: 15px;
                }}
                table {{ width: 100%; border-collapse: collapse; }}
                td {{ padding: 8px; border-bottom: 1px solid #eee; }}
                td:first-child {{ font-weight: bold; width: 30%; }}
                .footer {{
                    margin-top: 20px;
                    padding: 10px;
                    color: #666;
                    font-size: 12px;
                    border-top: 1px solid #eee;
                }}
            </style>
        </head>
        <body>
            <div class="header">
                <h2>DataFlow 实时数据流分析平台 - 告警通知</h2>
                <p>级别: {notification.severity.value.upper()}</p>
            </div>
            <div class="content">
                <div class="message">{notification.message}</div>
                <table>
                    <tr>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;"><strong>指标ID:</strong></td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;">{notification.metric_id}</td>
                    </tr>
                    <tr>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;"><strong>指标名称:</strong></td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;">{notification.metric_name}</td>
                    </tr>
                    <tr>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;"><strong>当前值:</strong></td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;"><strong>{notification.value}</strong></td>
                    </tr>
                    <tr>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;"><strong>触发条件:</strong></td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;"><code>{notification.threshold_condition}</code></td>
                    </tr>
                    {group_key_html}
                    <tr>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;"><strong>告警时间:</strong></td>
                        <td style="padding: 8px; border-bottom: 1px solid #eee;">{notification.timestamp.isoformat()}Z</td>
                    </tr>
                </table>
            </div>
            <div class="footer">
                <p>此邮件由 DataFlow 实时数据流分析平台自动发送，请勿回复。</p>
            </div>
        </body>
        </html>
        """
        return html

    def _build_text_content(self, notification: AlertNotification) -> str:
        group_key_text = ""
        if notification.group_key:
            group_key_text = f"""
分组信息: {json.dumps(notification.group_key, ensure_ascii=False)}
"""

        text = f"""
DataFlow 实时数据流分析平台 - 告警通知
============================================

【{notification.severity.value.upper()}】

{notification.message}

--------------------------------------------

指标ID: {notification.metric_id}
指标名称: {notification.metric_name}
当前值: {notification.value}
触发条件: {notification.threshold_condition}
{group_key_text}
告警时间: {notification.timestamp.isoformat()}Z

============================================
此邮件由 DataFlow 实时数据流分析平台自动发送，请勿回复。
"""
        return text

    async def close(self):
        if self._connection:
            try:
                self._connection.quit()
            except Exception as e:
                logger.warning(f"Error closing SMTP connection: {e}")
            self._connection = None

        self._initialized = False
        logger.info(f"Email channel '{self.name}' closed")
