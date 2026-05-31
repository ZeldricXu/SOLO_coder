from datetime import datetime
from typing import Any, Dict, List, Optional, Callable
from dataclasses import dataclass, field
from enum import Enum
import re
import asyncio
import json
from pathlib import Path
import uuid

from app.core.logger import logger
from app.core.events import event_bus, EventType, build_event
from app.core.config import settings


class NotificationChannel(str, Enum):
    EMAIL = "email"
    SMS = "sms"
    IN_APP = "in_app"
    WEBHOOK = "webhook"
    SLACK = "slack"
    WECHAT = "wechat"


class NotificationStatus(str, Enum):
    PENDING = "pending"
    SENT = "sent"
    DELIVERED = "delivered"
    FAILED = "failed"
    RETRYING = "retrying"


@dataclass
class NotificationTemplate:
    template_id: str
    name: str
    channel: NotificationChannel
    subject_template: str = ""
    body_template: str = ""
    variables: List[str] = field(default_factory=list)
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)


@dataclass
class NotificationRequest:
    request_id: str
    channel: NotificationChannel
    template_id: Optional[str] = None
    subject: str = ""
    body: str = ""
    recipients: List[str] = field(default_factory=list)
    context: Dict[str, Any] = field(default_factory=dict)
    priority: int = 0
    created_at: datetime = field(default_factory=datetime.utcnow)
    status: NotificationStatus = NotificationStatus.PENDING


@dataclass
class NotificationResult:
    request_id: str
    channel: NotificationChannel
    recipient: str
    status: NotificationStatus
    sent_at: Optional[datetime] = None
    error_message: Optional[str] = None


class TemplateRenderer:
    def __init__(self):
        self._templates: Dict[str, NotificationTemplate] = {}
        self._template_pattern = re.compile(r'\{\{(\w+)\}\}')
        self._init_default_templates()

    def _init_default_templates(self):
        default_templates = [
            ("task_complete", "任务完成通知", NotificationChannel.EMAIL,
             "任务 {{task_name}} 已完成",
             "您的任务 {{task_name}} (ID: {{task_id}}) 已成功完成。\n完成时间: {{completion_time}}\n结果: {{result}}",
             ["task_name", "task_id", "completion_time", "result"]),
            ("task_failed", "任务失败通知", NotificationChannel.EMAIL,
             "任务 {{task_name}} 执行失败",
             "您的任务 {{task_name}} (ID: {{task_id}}) 执行失败。\n错误信息: {{error_message}}\n请查看详细日志了解更多信息。",
             ["task_name", "task_id", "error_message"]),
            ("alert_high", "高优先级告警", NotificationChannel.SMS,
             "",
             "告警: {{alert_type}} - {{message}}",
             ["alert_type", "message"]),
            ("config_changed", "配置变更通知", NotificationChannel.IN_APP,
             "配置已更新",
             "配置 {{namespace}} 已更新至版本 {{version}}。\n变更原因: {{reason}}",
             ["namespace", "version", "reason"]),
            ("audit_alert", "审计告警", NotificationChannel.WEBHOOK,
             "",
             "{{timestamp}}: {{actor}} 执行了 {{action}} 操作",
             ["timestamp", "actor", "action"]),
        ]

        for tid, name, channel, subject, body, vars_list in default_templates:
            self._templates[tid] = NotificationTemplate(
                template_id=tid,
                name=name,
                channel=channel,
                subject_template=subject,
                body_template=body,
                variables=vars_list
            )

    def add_template(self, template: NotificationTemplate):
        template.updated_at = datetime.utcnow()
        self._templates[template.template_id] = template
        logger.info(f"Added notification template: {template.template_id}")

    def remove_template(self, template_id: str) -> bool:
        if template_id in self._templates:
            del self._templates[template_id]
            return True
        return False

    def get_template(self, template_id: str) -> Optional[NotificationTemplate]:
        return self._templates.get(template_id)

    def list_templates(self) -> List[NotificationTemplate]:
        return list(self._templates.values())

    def _render_string(self, template: str, context: Dict[str, Any]) -> str:
        def replace_var(match):
            var_name = match.group(1)
            value = context.get(var_name, f"{{{{{var_name}}}}}")
            return str(value)
        return self._template_pattern.sub(replace_var, template)

    def render(self, template_id: str, context: Dict[str, Any]) -> Dict[str, str]:
        template = self._templates.get(template_id)
        if not template:
            raise ValueError(f"Template {template_id} not found")

        return {
            "subject": self._render_string(template.subject_template, context),
            "body": self._render_string(template.body_template, context)
        }

    def validate_template(self, template_id: str, context: Dict[str, Any]) -> Dict[str, Any]:
        template = self._templates.get(template_id)
        if not template:
            return {"valid": False, "error": "Template not found"}

        missing = [v for v in template.variables if v not in context]
        return {
            "valid": len(missing) == 0,
            "missing_variables": missing,
            "required_variables": template.variables
        }


class MultiChannelNotifier:
    def __init__(self):
        self._channels: Dict[NotificationChannel, Callable] = {}
        self._results: List[NotificationResult] = []
        self._retry_queue: List[NotificationRequest] = []
        self._max_retries = 3
        self._init_channels()

    def _init_channels(self):
        async def mock_send_email(recipient: str, subject: str, body: str, context: Dict[str, Any]) -> bool:
            logger.info(f"[EMAIL] Sending to {recipient}: {subject}")
            await asyncio.sleep(0.1)
            return True

        async def mock_send_sms(recipient: str, subject: str, body: str, context: Dict[str, Any]) -> bool:
            logger.info(f"[SMS] Sending to {recipient}: {body[:50]}...")
            await asyncio.sleep(0.05)
            return True

        async def mock_send_in_app(recipient: str, subject: str, body: str, context: Dict[str, Any]) -> bool:
            logger.info(f"[IN_APP] Sending to {recipient}: {subject}")
            await asyncio.sleep(0.02)
            return True

        async def mock_send_webhook(recipient: str, subject: str, body: str, context: Dict[str, Any]) -> bool:
            logger.info(f"[WEBHOOK] POST to {recipient}")
            await asyncio.sleep(0.1)
            return True

        async def mock_send_slack(recipient: str, subject: str, body: str, context: Dict[str, Any]) -> bool:
            logger.info(f"[SLACK] Sending to #{recipient}: {subject}")
            await asyncio.sleep(0.05)
            return True

        async def mock_send_wechat(recipient: str, subject: str, body: str, context: Dict[str, Any]) -> bool:
            logger.info(f"[WECHAT] Sending to {recipient}")
            await asyncio.sleep(0.05)
            return True

        self._channels[NotificationChannel.EMAIL] = mock_send_email
        self._channels[NotificationChannel.SMS] = mock_send_sms
        self._channels[NotificationChannel.IN_APP] = mock_send_in_app
        self._channels[NotificationChannel.WEBHOOK] = mock_send_webhook
        self._channels[NotificationChannel.SLACK] = mock_send_slack
        self._channels[NotificationChannel.WECHAT] = mock_send_wechat

    def register_channel(self, channel: NotificationChannel, sender: Callable):
        self._channels[channel] = sender
        logger.info(f"Registered notification channel: {channel}")

    def unregister_channel(self, channel: NotificationChannel) -> bool:
        if channel in self._channels:
            del self._channels[channel]
            return True
        return False

    def create_request(self, channel: NotificationChannel, recipients: List[str],
                        template_id: Optional[str] = None,
                        subject: str = "", body: str = "",
                        context: Optional[Dict[str, Any]] = None,
                        priority: int = 0) -> NotificationRequest:
        return NotificationRequest(
            request_id=f"notif_{uuid.uuid4().hex[:8]}",
            channel=channel,
            template_id=template_id,
            subject=subject,
            body=body,
            recipients=recipients,
            context=context or {},
            priority=priority
        )

    async def send(self, request: NotificationRequest) -> List[NotificationResult]:
        results = []
        sender = self._channels.get(request.channel)

        if not sender:
            for recipient in request.recipients:
                result = NotificationResult(
                    request_id=request.request_id,
                    channel=request.channel,
                    recipient=recipient,
                    status=NotificationStatus.FAILED,
                    error_message=f"Channel {request.channel} not registered"
                )
                results.append(result)
                self._results.append(result)
            return results

        for recipient in request.recipients:
            try:
                success = await sender(
                    recipient,
                    request.subject,
                    request.body,
                    request.context
                )
                result = NotificationResult(
                    request_id=request.request_id,
                    channel=request.channel,
                    recipient=recipient,
                    status=NotificationStatus.SENT if success else NotificationStatus.FAILED,
                    sent_at=datetime.utcnow() if success else None
                )
                if not success:
                    result.error_message = "Send failed"
            except Exception as e:
                result = NotificationResult(
                    request_id=request.request_id,
                    channel=request.channel,
                    recipient=recipient,
                    status=NotificationStatus.FAILED,
                    error_message=str(e)
                )

            results.append(result)
            self._results.append(result)

            if result.status == NotificationStatus.SENT:
                event_bus.emit(build_event(EventType.NOTIFICATION_SENT, {
                    "request_id": request.request_id,
                    "channel": request.channel,
                    "recipient": recipient
                }))

        return results

    async def send_to_multiple_channels(self, recipients: List[str],
                                         channels: List[NotificationChannel],
                                         template_id: Optional[str] = None,
                                         subject: str = "", body: str = "",
                                         context: Optional[Dict[str, Any]] = None) -> List[NotificationResult]:
        all_results = []
        for channel in channels:
            request = self.create_request(channel, recipients, template_id, subject, body, context)
            results = await self.send(request)
            all_results.extend(results)
        return all_results

    def get_results(self, request_id: Optional[str] = None,
                    limit: int = 100) -> List[NotificationResult]:
        results = self._results
        if request_id:
            results = [r for r in results if r.request_id == request_id]
        return results[-limit:]

    def get_statistics(self) -> Dict[str, Any]:
        stats = {
            "total": len(self._results),
            "by_channel": {},
            "by_status": {}
        }
        for result in self._results:
            ch = result.channel
            st = result.status
            stats["by_channel"][ch] = stats["by_channel"].get(ch, 0) + 1
            stats["by_status"][st] = stats["by_status"].get(st, 0) + 1
        return stats


class NotificationModule:
    def __init__(self):
        self._renderer = TemplateRenderer()
        self._notifier = MultiChannelNotifier()
        logger.info("NotificationModule initialized")

    @property
    def renderer(self) -> TemplateRenderer:
        return self._renderer

    @property
    def notifier(self) -> MultiChannelNotifier:
        return self._notifier

    async def notify(self, channel: NotificationChannel, recipients: List[str],
                     template_id: str, context: Dict[str, Any]) -> List[NotificationResult]:
        rendered = self._renderer.render(template_id, context)
        request = self._notifier.create_request(
            channel=channel,
            recipients=recipients,
            template_id=template_id,
            subject=rendered["subject"],
            body=rendered["body"],
            context=context
        )
        return await self._notifier.send(request)

    async def notify_with_custom_content(self, channel: NotificationChannel,
                                          recipients: List[str],
                                          subject: str, body: str,
                                          context: Optional[Dict[str, Any]] = None) -> List[NotificationResult]:
        request = self._notifier.create_request(
            channel=channel,
            recipients=recipients,
            subject=subject,
            body=body,
            context=context or {}
        )
        return await self._notifier.send(request)

    def create_template(self, template_id: str, name: str, channel: NotificationChannel,
                        subject_template: str, body_template: str,
                        variables: Optional[List[str]] = None) -> NotificationTemplate:
        template = NotificationTemplate(
            template_id=template_id,
            name=name,
            channel=channel,
            subject_template=subject_template,
            body_template=body_template,
            variables=variables or []
        )
        self._renderer.add_template(template)
        return template


notification_module = NotificationModule()
