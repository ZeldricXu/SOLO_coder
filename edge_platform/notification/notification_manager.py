import asyncio
import logging
import json
import re
import smtplib
import requests
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from typing import Dict, List, Optional, Any, Callable
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import uuid
import threading
from abc import ABC, abstractmethod

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import NotificationException

logger = logging.getLogger(__name__)


class ChannelType(str, Enum):
    EMAIL = "email"
    WEBHOOK = "webhook"
    SMS = "sms"
    PUSH = "push"
    IN_APP = "in_app"


class NotificationStatus(str, Enum):
    PENDING = "pending"
    SENDING = "sending"
    SENT = "sent"
    FAILED = "failed"
    RETRYING = "retrying"


@dataclass
class NotificationTemplate:
    template_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    name: str = ""
    channel_type: ChannelType = ChannelType.EMAIL
    subject_template: str = ""
    content_template: str = ""
    variables: List[str] = field(default_factory=list)
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)
    is_default: bool = False


@dataclass
class Notification:
    notification_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    channel_type: ChannelType = ChannelType.EMAIL
    recipient: str = ""
    subject: str = ""
    content: str = ""
    status: NotificationStatus = NotificationStatus.PENDING
    template_id: Optional[str] = None
    variables: Dict[str, Any] = field(default_factory=dict)
    retry_count: int = 0
    max_retries: int = 3
    error_message: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.now)
    sent_at: Optional[datetime] = None


class NotificationChannel(ABC):
    @abstractmethod
    async def send(self, notification: Notification) -> bool:
        pass


class EmailChannel(NotificationChannel):
    def __init__(
        self,
        smtp_host: str = "localhost",
        smtp_port: int = 25,
        smtp_user: str = "",
        smtp_password: str = "",
        use_tls: bool = False,
        from_address: str = "noreply@example.com"
    ):
        self._smtp_host = smtp_host
        self._smtp_port = smtp_port
        self._smtp_user = smtp_user
        self._smtp_password = smtp_password
        self._use_tls = use_tls
        self._from_address = from_address

    async def send(self, notification: Notification) -> bool:
        try:
            msg = MIMEMultipart()
            msg["From"] = self._from_address
            msg["To"] = notification.recipient
            msg["Subject"] = notification.subject

            msg.attach(MIMEText(notification.content, "html", "utf-8"))

            def send_email():
                with smtplib.SMTP(self._smtp_host, self._smtp_port) as server:
                    if self._use_tls:
                        server.starttls()
                    if self._smtp_user and self._smtp_password:
                        server.login(self._smtp_user, self._smtp_password)
                    server.send_message(msg)

            await asyncio.to_thread(send_email)
            return True
        except Exception as e:
            logger.error(f"Email send failed: {e}")
            raise NotificationException(f"Email send failed: {e}")


class WebhookChannel(NotificationChannel):
    def __init__(self, timeout_seconds: int = 10):
        self._timeout = timeout_seconds

    async def send(self, notification: Notification) -> bool:
        try:
            payload = {
                "notification_id": notification.notification_id,
                "subject": notification.subject,
                "content": notification.content,
                "variables": notification.variables,
                "timestamp": datetime.now().isoformat()
            }

            response = await asyncio.to_thread(
                requests.post,
                notification.recipient,
                json=payload,
                timeout=self._timeout
            )
            response.raise_for_status()
            return True
        except Exception as e:
            logger.error(f"Webhook send failed: {e}")
            raise NotificationException(f"Webhook send failed: {e}")


class SMSChannel(NotificationChannel):
    def __init__(self, api_url: str = "", api_key: str = ""):
        self._api_url = api_url
        self._api_key = api_key

    async def send(self, notification: Notification) -> bool:
        try:
            payload = {
                "to": notification.recipient,
                "message": notification.content,
                "api_key": self._api_key
            }

            if self._api_url:
                response = await asyncio.to_thread(
                    requests.post,
                    self._api_url,
                    json=payload,
                    timeout=10
                )
                response.raise_for_status()
            else:
                logger.info(f"SMS simulated: To={notification.recipient}, Message={notification.content}")

            return True
        except Exception as e:
            logger.error(f"SMS send failed: {e}")
            raise NotificationException(f"SMS send failed: {e}")


class InAppChannel(NotificationChannel):
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus

    async def send(self, notification: Notification) -> bool:
        try:
            self._event_bus.publish(Event(
                event_type="notification.in_app",
                source="notification",
                payload={
                    "user_id": notification.recipient,
                    "subject": notification.subject,
                    "content": notification.content,
                    "notification_id": notification.notification_id
                }
            ))
            return True
        except Exception as e:
            logger.error(f"In-app notification failed: {e}")
            raise NotificationException(f"In-app notification failed: {e}")


class TemplateEngine:
    _variable_pattern = re.compile(r'\{\{\s*(\w+)\s*\}\}')

    @classmethod
    def render(cls, template: str, variables: Dict[str, Any]) -> str:
        def replace_variable(match):
            var_name = match.group(1)
            value = variables.get(var_name, f"{{{{{var_name}}}}}")
            return str(value)

        return cls._variable_pattern.sub(replace_variable, template)

    @classmethod
    def extract_variables(cls, template: str) -> List[str]:
        return list(set(cls._variable_pattern.findall(template)))


class NotificationManager:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._channels: Dict[ChannelType, NotificationChannel] = {}
        self._templates: Dict[str, NotificationTemplate] = {}
        self._notifications: Dict[str, Notification] = {}
        self._notification_queue: asyncio.Queue[Notification] = asyncio.Queue()
        self._is_running = False
        self._worker_task: Optional[asyncio.Task] = None
        self._lock = threading.RLock()
        self._initialize_channels()

    def _initialize_channels(self) -> None:
        self._channels[ChannelType.EMAIL] = EmailChannel()
        self._channels[ChannelType.WEBHOOK] = WebhookChannel()
        self._channels[ChannelType.SMS] = SMSChannel()
        self._channels[ChannelType.IN_APP] = InAppChannel(self._event_bus)

    def register_channel(self, channel_type: ChannelType, channel: NotificationChannel) -> None:
        self._channels[channel_type] = channel
        logger.info(f"Registered channel: {channel_type}")

    def create_template(
        self,
        name: str,
        channel_type: ChannelType,
        subject_template: str,
        content_template: str,
        is_default: bool = False
    ) -> NotificationTemplate:
        variables = TemplateEngine.extract_variables(subject_template + content_template)

        template = NotificationTemplate(
            name=name,
            channel_type=channel_type,
            subject_template=subject_template,
            content_template=content_template,
            variables=variables,
            is_default=is_default
        )

        with self._lock:
            self._templates[template.template_id] = template

        self._event_bus.publish(Event(
            event_type="notification.template.created",
            source="notification",
            payload={"template_id": template.template_id, "name": name}
        ))

        return template

    def get_template(self, template_id: str) -> NotificationTemplate:
        template = self._templates.get(template_id)
        if not template:
            raise NotificationException(f"Template {template_id} not found")
        return template

    def update_template(
        self,
        template_id: str,
        name: Optional[str] = None,
        subject_template: Optional[str] = None,
        content_template: Optional[str] = None,
        is_default: Optional[bool] = None
    ) -> NotificationTemplate:
        template = self.get_template(template_id)

        if name is not None:
            template.name = name
        if subject_template is not None:
            template.subject_template = subject_template
        if content_template is not None:
            template.content_template = content_template
        if is_default is not None:
            template.is_default = is_default

        all_template_content = template.subject_template + template.content_template
        template.variables = TemplateEngine.extract_variables(all_template_content)
        template.updated_at = datetime.now()

        self._event_bus.publish(Event(
            event_type="notification.template.updated",
            source="notification",
            payload={"template_id": template_id}
        ))

        return template

    def delete_template(self, template_id: str) -> None:
        if template_id not in self._templates:
            raise NotificationException(f"Template {template_id} not found")

        with self._lock:
            del self._templates[template_id]

        self._event_bus.publish(Event(
            event_type="notification.template.deleted",
            source="notification",
            payload={"template_id": template_id}
        ))

    def list_templates(
        self,
        channel_type: Optional[ChannelType] = None,
        limit: int = 100
    ) -> List[NotificationTemplate]:
        with self._lock:
            templates = list(self._templates.values())

        if channel_type:
            templates = [t for t in templates if t.channel_type == channel_type]

        templates.sort(key=lambda t: t.updated_at, reverse=True)
        return templates[:limit]

    async def send_notification(
        self,
        channel_type: ChannelType,
        recipient: str,
        subject: str,
        content: str,
        template_id: Optional[str] = None,
        variables: Optional[Dict[str, Any]] = None
    ) -> Notification:
        notification = Notification(
            channel_type=channel_type,
            recipient=recipient,
            subject=subject,
            content=content,
            template_id=template_id,
            variables=variables or {}
        )

        with self._lock:
            self._notifications[notification.notification_id] = notification

        await self._notification_queue.put(notification)

        self._event_bus.publish(Event(
            event_type="notification.queued",
            source="notification",
            payload={
                "notification_id": notification.notification_id,
                "channel_type": channel_type.value
            }
        ))

        return notification

    async def send_from_template(
        self,
        template_id: str,
        recipient: str,
        variables: Dict[str, Any]
    ) -> Notification:
        template = self.get_template(template_id)

        subject = TemplateEngine.render(template.subject_template, variables)
        content = TemplateEngine.render(template.content_template, variables)

        return await self.send_notification(
            channel_type=template.channel_type,
            recipient=recipient,
            subject=subject,
            content=content,
            template_id=template_id,
            variables=variables
        )

    async def _process_notification(self, notification: Notification) -> None:
        channel = self._channels.get(notification.channel_type)
        if not channel:
            notification.status = NotificationStatus.FAILED
            notification.error_message = f"No channel registered for {notification.channel_type}"
            return

        notification.status = NotificationStatus.SENDING

        while notification.retry_count < notification.max_retries:
            try:
                success = await channel.send(notification)
                if success:
                    notification.status = NotificationStatus.SENT
                    notification.sent_at = datetime.now()

                    self._event_bus.publish(Event(
                        event_type="notification.sent",
                        source="notification",
                        payload={
                            "notification_id": notification.notification_id,
                            "channel_type": notification.channel_type.value
                        }
                    ))
                    return
            except Exception as e:
                notification.retry_count += 1
                notification.error_message = str(e)

                if notification.retry_count < notification.max_retries:
                    notification.status = NotificationStatus.RETRYING
                    await asyncio.sleep(2 ** notification.retry_count)

        notification.status = NotificationStatus.FAILED
        self._event_bus.publish(Event(
            event_type="notification.failed",
            source="notification",
            payload={
                "notification_id": notification.notification_id,
                "error": notification.error_message
            }
        ))

    async def _worker(self) -> None:
        while self._is_running:
            try:
                notification = await self._notification_queue.get()
                asyncio.create_task(self._process_notification(notification))
                self._notification_queue.task_done()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in notification worker: {e}")

    async def start(self) -> None:
        if self._is_running:
            return
        self._is_running = True
        self._worker_task = asyncio.create_task(self._worker())
        logger.info("Notification manager started")

    async def stop(self) -> None:
        self._is_running = False
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass
        logger.info("Notification manager stopped")

    def get_notification(self, notification_id: str) -> Notification:
        notification = self._notifications.get(notification_id)
        if not notification:
            raise NotificationException(f"Notification {notification_id} not found")
        return notification

    def list_notifications(
        self,
        status: Optional[NotificationStatus] = None,
        channel_type: Optional[ChannelType] = None,
        limit: int = 100
    ) -> List[Notification]:
        with self._lock:
            notifications = list(self._notifications.values())

        if status:
            notifications = [n for n in notifications if n.status == status]
        if channel_type:
            notifications = [n for n in notifications if n.channel_type == channel_type]

        notifications.sort(key=lambda n: n.created_at, reverse=True)
        return notifications[:limit]

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total = len(self._notifications)
            by_status = {}
            by_channel = {}

            for status in NotificationStatus:
                by_status[status.value] = sum(
                    1 for n in self._notifications.values()
                    if n.status == status
                )

            for channel in ChannelType:
                by_channel[channel.value] = sum(
                    1 for n in self._notifications.values()
                    if n.channel_type == channel
                )

        return {
            "total_notifications": total,
            "by_status": by_status,
            "by_channel": by_channel,
            "templates_count": len(self._templates),
            "queue_size": self._notification_queue.qsize()
        }
