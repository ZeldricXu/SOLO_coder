import hmac
import json
import logging
import smtplib
import time
import urllib.parse
from datetime import datetime
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from email.header import Header
from hashlib import sha256
from typing import Dict, List, Any, Optional

import requests

from app.models.alert import AlertEvent, AlertSeverity

logger = logging.getLogger(__name__)


class EmailNotifier:
    def __init__(self, config: Dict[str, Any]):
        self.smtp_server = config.get('smtp_server', '')
        self.smtp_port = config.get('smtp_port', 587)
        self.sender = config.get('sender', '')
        self.password = config.get('password', '')
        self.recipients = config.get('recipients', [])
        
        self.enabled = bool(self.smtp_server and self.sender and self.recipients)
        logger.info(f"EmailNotifier initialized, enabled: {self.enabled}")
    
    def send(self, event: AlertEvent, is_resolved: bool = False) -> bool:
        if not self.enabled:
            logger.warning("Email notifier is not configured")
            return False
        
        if not self.recipients:
            logger.warning("No email recipients configured")
            return False
        
        try:
            message = self._build_message(event, is_resolved)
            
            with smtplib.SMTP(self.smtp_server, self.smtp_port) as server:
                server.ehlo()
                server.starttls()
                server.ehlo()
                server.login(self.sender, self.password)
                server.sendmail(
                    self.sender,
                    self.recipients,
                    message.as_string()
                )
            
            logger.info(f"Email sent successfully for alert {event.alert_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to send email for alert {event.alert_id}: {e}")
            return False
    
    def _build_message(self, event: AlertEvent, is_resolved: bool) -> MIMEMultipart:
        msg = MIMEMultipart()
        
        subject_prefix = "[告警恢复]" if is_resolved else "[告警触发]"
        severity_desc = self._get_severity_desc(event.severity)
        subject = f"{subject_prefix} {severity_desc} - {event.metric_type} on {event.server_id}"
        
        msg['From'] = self.sender
        msg['To'] = ', '.join(self.recipients)
        msg['Subject'] = Header(subject, 'utf-8')
        
        body = self._build_body(event, is_resolved)
        msg.attach(MIMEText(body, 'plain', 'utf-8'))
        
        return msg
    
    def _build_body(self, event: AlertEvent, is_resolved: bool) -> str:
        lines = []
        
        if is_resolved:
            lines.append("=" * 50)
            lines.append("           告警恢复通知")
            lines.append("=" * 50)
        else:
            lines.append("=" * 50)
            lines.append("           告警触发通知")
            lines.append("=" * 50)
        
        lines.append("")
        lines.append(f"告警ID: {event.alert_id}")
        lines.append(f"规则ID: {event.rule_id}")
        lines.append(f"服务器: {event.server_id}")
        lines.append("")
        lines.append("-" * 50)
        lines.append("指标详情:")
        lines.append(f"  指标类型: {event.metric_type}")
        lines.append(f"  当前值: {event.metric_value}")
        lines.append(f"  阈值: {event.threshold}")
        lines.append(f"  操作符: {event.operator}")
        lines.append("")
        lines.append("-" * 50)
        lines.append("告警信息:")
        lines.append(f"  状态: {event.status.value if hasattr(event.status, 'value') else event.status}")
        lines.append(f"  严重程度: {self._get_severity_desc(event.severity)}")
        lines.append(f"  触发时间: {event.triggered_at.strftime('%Y-%m-%d %H:%M:%S UTC') if event.triggered_at else 'N/A'}")
        
        if is_resolved and event.resolved_at:
            lines.append(f"  恢复时间: {event.resolved_at.strftime('%Y-%m-%d %H:%M:%S UTC')}")
        
        lines.append("")
        lines.append("-" * 50)
        lines.append("详细信息:")
        lines.append(event.message)
        
        if event.details:
            lines.append("")
            lines.append("额外字段:")
            for key, value in event.details.items():
                lines.append(f"  {key}: {value}")
        
        lines.append("")
        lines.append("=" * 50)
        
        return "\n".join(lines)
    
    def _get_severity_desc(self, severity) -> str:
        if hasattr(severity, 'value'):
            severity = severity.value
        
        mapping = {
            'info': '信息',
            'warning': '警告',
            'critical': '严重'
        }
        return mapping.get(severity, str(severity))


class DingTalkNotifier:
    def __init__(self, config: Dict[str, Any]):
        self.webhook_url = config.get('webhook_url', '')
        self.secret = config.get('secret', '')
        
        self.enabled = bool(self.webhook_url)
        logger.info(f"DingTalkNotifier initialized, enabled: {self.enabled}")
    
    def send(self, event: AlertEvent, is_resolved: bool = False) -> bool:
        if not self.enabled:
            logger.warning("DingTalk notifier is not configured")
            return False
        
        try:
            payload = self._build_payload(event, is_resolved)
            url = self._build_signed_url() if self.secret else self.webhook_url
            
            response = requests.post(
                url,
                headers={'Content-Type': 'application/json'},
                json=payload,
                timeout=10
            )
            
            response.raise_for_status()
            result = response.json()
            
            if result.get('errcode') == 0:
                logger.info(f"DingTalk message sent successfully for alert {event.alert_id}")
                return True
            else:
                logger.error(f"DingTalk API error: {result.get('errmsg')}")
                return False
        except Exception as e:
            logger.error(f"Failed to send DingTalk message for alert {event.alert_id}: {e}")
            return False
    
    def _build_signed_url(self) -> str:
        timestamp = str(round(time.time() * 1000))
        secret_enc = self.secret.encode('utf-8')
        string_to_sign = f"{timestamp}\n{self.secret}"
        string_to_sign_enc = string_to_sign.encode('utf-8')
        
        hmac_code = hmac.new(secret_enc, string_to_sign_enc, digestmod=sha256).digest()
        sign = urllib.parse.quote_plus(hmac_code.hex())
        
        return f"{self.webhook_url}&timestamp={timestamp}&sign={sign}"
    
    def _build_payload(self, event: AlertEvent, is_resolved: bool) -> Dict[str, Any]:
        severity_desc = self._get_severity_desc(event.severity)
        status_emoji = "✅" if is_resolved else "⚠️"
        status_text = "告警恢复" if is_resolved else "告警触发"
        
        title = f"{status_emoji} {status_text} - {severity_desc}"
        
        lines = [
            f"### {title}",
            f"",
            f"**服务器**: {event.server_id}",
            f"**指标类型**: {event.metric_type}",
            f"",
            f"**当前值**: {event.metric_value}",
            f"**阈值**: {event.threshold}",
            f"**操作符**: {event.operator}",
            f"",
            f"**状态**: {event.status.value if hasattr(event.status, 'value') else event.status}",
            f"**严重程度**: {severity_desc}",
            f"",
            f"---",
            f"",
            f"**详细信息**:",
            f"{event.message}",
            f"",
            f"**触发时间**: {event.triggered_at.strftime('%Y-%m-%d %H:%M:%S UTC') if event.triggered_at else 'N/A'}"
        ]
        
        if is_resolved and event.resolved_at:
            lines.append(f"**恢复时间**: {event.resolved_at.strftime('%Y-%m-%d %H:%M:%S UTC')}")
        
        content = "\n".join(lines)
        
        return {
            "msgtype": "markdown",
            "markdown": {
                "title": title,
                "text": content
            }
        }
    
    def _get_severity_desc(self, severity) -> str:
        if hasattr(severity, 'value'):
            severity = severity.value
        
        mapping = {
            'info': '信息',
            'warning': '警告',
            'critical': '严重'
        }
        return mapping.get(severity, str(severity))


class NotificationService:
    def __init__(self, config: Dict[str, Any]):
        self.config = config
        self.email_notifier = EmailNotifier(config.get('email', {}))
        self.dingtalk_notifier = DingTalkNotifier(config.get('dingtalk', {}))
        
        self.notifiers = {
            'email': self.email_notifier,
            'dingtalk': self.dingtalk_notifier
        }
        
        logger.info("NotificationService initialized")
    
    def send_alert(self, event: AlertEvent, is_resolved: bool = False) -> Dict[str, bool]:
        channels = event.notify_channels
        if not channels:
            channels = ['email', 'dingtalk']
        
        results = {}
        
        for channel in channels:
            notifier = self.notifiers.get(channel)
            if notifier:
                try:
                    success = notifier.send(event, is_resolved)
                    results[channel] = success
                except Exception as e:
                    logger.error(f"Failed to send via {channel}: {e}")
                    results[channel] = False
            else:
                logger.warning(f"Unknown notification channel: {channel}")
                results[channel] = False
        
        return results
    
    def test_channel(self, channel: str) -> bool:
        test_event = AlertEvent(
            alert_id="test_alert",
            rule_id="test_rule",
            server_id="test_server",
            metric_type="cpu_usage",
            metric_value=99.9,
            threshold=80.0,
            operator="greater_than",
            status="triggered",
            severity="warning",
            notify_channels=[channel],
            message="这是一条测试告警消息，请忽略。"
        )
        
        notifier = self.notifiers.get(channel)
        if notifier:
            return notifier.send(test_event, is_resolved=False)
        return False
