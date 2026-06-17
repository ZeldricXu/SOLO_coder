import logging
from abc import ABC, abstractmethod
from datetime import datetime
from typing import Literal, Optional

import aiosmtplib
import httpx
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)


class Alert(BaseModel):
    alert_type: Literal["task_failure", "quality_degradation", "sla_timeout"]
    severity: Literal["info", "warning", "error", "critical"]
    pipeline_name: str
    task_name: Optional[str] = None
    message: str
    details: dict = Field(default_factory=dict)
    timestamp: datetime = Field(default_factory=datetime.now)


class AlertChannel(ABC):
    @abstractmethod
    async def send(self, alert: Alert) -> bool:
        ...


class EmailChannel(AlertChannel):
    def __init__(self, config: dict) -> None:
        self.smtp_host = config["smtp_host"]
        self.smtp_port = config.get("smtp_port", 587)
        self.smtp_user = config.get("smtp_user", "")
        self.smtp_password = config.get("smtp_password", "")
        self.from_address = config["from_address"]
        self.recipients = config["recipients"]

    def _format_html(self, alert: Alert) -> str:
        rows = [
            ("Alert Type", alert.alert_type),
            ("Severity", alert.severity),
            ("Pipeline", alert.pipeline_name),
            ("Task", alert.task_name or "N/A"),
            ("Message", alert.message),
            ("Timestamp", alert.timestamp.isoformat()),
        ]
        detail_rows = "".join(
            f"<tr><td><strong>{k}</strong></td><td>{v}</td></tr>"
            for k, v in alert.details.items()
        )
        main_rows = "".join(
            f"<tr><td><strong>{label}</strong></td><td>{value}</td></tr>"
            for label, value in rows
        )
        return (
            "<html><body>"
            f"<h2>ETL Alert: {alert.severity.upper()}</h2>"
            "<table border='1' cellpadding='6' cellspacing='0'>"
            f"{main_rows}{detail_rows}"
            "</table>"
            "</body></html>"
        )

    async def send(self, alert: Alert) -> bool:
        msg = MIMEMultipart("alternative")
        msg["Subject"] = (
            f"[ETL Alert][{alert.severity.upper()}] "
            f"{alert.pipeline_name} - {alert.alert_type}"
        )
        msg["From"] = self.from_address
        msg["To"] = ", ".join(self.recipients)
        msg.attach(MIMEText(self._format_html(alert), "html"))
        try:
            await aiosmtplib.send(
                msg,
                hostname=self.smtp_host,
                port=self.smtp_port,
                username=self.smtp_user or None,
                password=self.smtp_password or None,
                start_tls=True,
            )
            logger.info("Email alert sent for %s/%s", alert.pipeline_name, alert.alert_type)
            return True
        except Exception:
            logger.exception("Failed to send email alert for %s", alert.pipeline_name)
            return False


class SlackChannel(AlertChannel):
    def __init__(self, config: dict) -> None:
        self.webhook_url = config["webhook_url"]
        self.channel = config.get("channel")

    async def send(self, alert: Alert) -> bool:
        emoji = {"info": "ℹ️", "warning": "⚠️", "error": "🔴", "critical": "🚨"}.get(
            alert.severity, "⚠️"
        )
        blocks = [
            {
                "type": "header",
                "text": {
                    "type": "plain_text",
                    "text": f"{emoji} ETL Alert: {alert.severity.upper()}",
                },
            },
            {
                "type": "section",
                "fields": [
                    {"type": "mrkdwn", "text": f"*Type:*\n{alert.alert_type}"},
                    {"type": "mrkdwn", "text": f"*Pipeline:*\n{alert.pipeline_name}"},
                    {"type": "mrkdwn", "text": f"*Task:*\n{alert.task_name or 'N/A'}"},
                    {"type": "mrkdwn", "text": f"*Time:*\n{alert.timestamp.isoformat()}"},
                ],
            },
            {
                "type": "section",
                "text": {"type": "mrkdwn", "text": f"*Message:*\n{alert.message}"},
            },
        ]
        if alert.details:
            detail_text = "\n".join(f"• *{k}:* {v}" for k, v in alert.details.items())
            blocks.append(
                {
                    "type": "section",
                    "text": {"type": "mrkdwn", "text": f"*Details:*\n{detail_text}"},
                }
            )
        payload: dict = {"blocks": blocks}
        if self.channel:
            payload["channel"] = self.channel
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.post(self.webhook_url, json=payload, timeout=10)
            if resp.status_code == 200:
                logger.info("Slack alert sent for %s/%s", alert.pipeline_name, alert.alert_type)
                return True
            logger.error(
                "Slack webhook returned %s: %s", resp.status_code, resp.text
            )
            return False
        except Exception:
            logger.exception("Failed to send Slack alert for %s", alert.pipeline_name)
            return False


class PagerDutyChannel(AlertChannel):
    def __init__(self, config: dict) -> None:
        self.routing_key = config["routing_key"]
        self.severity = config.get("severity", "error")

    def _map_severity(self, severity: str) -> str:
        mapping = {"info": "info", "warning": "warning", "error": "error", "critical": "critical"}
        return mapping.get(severity, self.severity)

    async def send(self, alert: Alert) -> bool:
        payload = {
            "routing_key": self.routing_key,
            "event_action": "trigger",
            "payload": {
                "summary": f"[{alert.severity.upper()}] {alert.pipeline_name}: {alert.message}",
                "severity": self._map_severity(alert.severity),
                "source": alert.pipeline_name,
                "component": alert.task_name or alert.alert_type,
                "group": alert.alert_type,
                "custom_details": {
                    "alert_type": alert.alert_type,
                    "task_name": alert.task_name,
                    **alert.details,
                },
                "timestamp": alert.timestamp.isoformat(),
            },
        }
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.post(
                    "https://events.pagerduty.com/v2/enqueue",
                    json=payload,
                    timeout=10,
                )
            if resp.status_code == 202:
                logger.info("PagerDuty alert sent for %s/%s", alert.pipeline_name, alert.alert_type)
                return True
            logger.error(
                "PagerDuty API returned %s: %s", resp.status_code, resp.text
            )
            return False
        except Exception:
            logger.exception("Failed to send PagerDuty alert for %s", alert.pipeline_name)
            return False
