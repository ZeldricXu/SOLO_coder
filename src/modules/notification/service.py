from typing import Dict, List, Optional, Any
from datetime import datetime
from collections import defaultdict
import asyncio
import re
from jinja2 import Template, Environment, meta
from .types import (
    NotificationTemplate,
    TemplateType,
    NotificationChannel,
    Notification,
    NotificationRequest,
    NotificationStatus,
    NotificationPriority,
    ChannelConfig,
)
from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    NotFoundError,
    ValidationError,
    PlatformError,
    generate_id,
)
import logging

logger = logging.getLogger(__name__)


class NotificationService:
    def __init__(self):
        self._templates: Dict[str, NotificationTemplate] = {}
        self._notifications: Dict[str, Notification] = {}
        self._queue: List[tuple] = []
        self._channel_configs: Dict[NotificationChannel, ChannelConfig] = {}
        self._rate_limits: Dict[NotificationChannel, list] = defaultdict(list)
        self._jinja_env = Environment(autoescape=True)
        self._sending_tasks: Dict[str, asyncio.Task] = {}
        self._metrics = get_metrics_collector()
        self._lock = asyncio.Lock()
        self._init_default_channels()

    def _init_default_channels(self) -> None:
        for channel in NotificationChannel:
            self._channel_configs[channel] = ChannelConfig(
                channel=channel,
                enabled=True,
                rate_limit_per_minute=100,
            )

    async def create_template(
        self,
        template: NotificationTemplate,
        trace_id: Optional[str] = None,
    ) -> NotificationTemplate:
        with init_context(trace_id, operation="create_template"):
            try:
                template.template_id = template.template_id or generate_id("tmpl")

                parsed = self._jinja_env.parse(template.content)
                variables = meta.find_undeclared_variables(parsed)
                template.variables = sorted(list(variables))

                self._templates[template.template_id] = template

                emit_event(
                    "notification.template.created",
                    {"template_id": template.template_id, "name": template.name},
                    source="notification",
                )

                self._metrics.increment("notification_templates_created")
                return template

            except Exception as e:
                logger.error(f"Failed to create template: {e}")
                raise PlatformError(f"通知模板创建失败: {str(e)}")

    async def get_template(
        self,
        template_id: str,
        trace_id: Optional[str] = None,
    ) -> NotificationTemplate:
        with init_context(trace_id, operation="get_template"):
            template = self._templates.get(template_id)
            if not template:
                raise NotFoundError(f"Template not found: {template_id}")
            return template

    async def list_templates(
        self,
        channel: Optional[NotificationChannel] = None,
        trace_id: Optional[str] = None,
    ) -> List[NotificationTemplate]:
        with init_context(trace_id, operation="list_templates"):
            templates = list(self._templates.values())
            if channel:
                templates = [t for t in templates if channel in t.channels]
            return sorted(templates, key=lambda t: t.created_at, reverse=True)

    async def render_template(
        self,
        template_id: str,
        variables: Dict[str, Any],
        trace_id: Optional[str] = None,
    ) -> Dict[str, str]:
        with init_context(trace_id, operation="render_template"):
            template = await self.get_template(template_id)

            merged_vars = dict(template.default_variables)
            merged_vars.update(variables)

            try:
                jinja_template = self._jinja_env.from_string(template.content)
                rendered_content = jinja_template.render(**merged_vars)

                rendered_subject = template.subject
                if "{" in template.subject:
                    subject_template = self._jinja_env.from_string(template.subject)
                    rendered_subject = subject_template.render(**merged_vars)

                return {
                    "subject": rendered_subject,
                    "content": rendered_content,
                }
            except Exception as e:
                logger.error(f"Template rendering failed: {e}")
                raise ValidationError(f"模板渲染失败: {str(e)}")

    async def send_notification(
        self,
        request: NotificationRequest,
        trace_id: Optional[str] = None,
    ) -> Notification:
        with init_context(trace_id, operation="send_notification"):
            try:
                channel_config = self._channel_configs.get(request.channel)
                if not channel_config or not channel_config.enabled:
                    raise ValidationError(f"Channel {request.channel.value} is not enabled")

                subject = request.subject or ""
                content = request.content or ""

                if request.template_id:
                    rendered = await self.render_template(
                        request.template_id, request.variables
                    )
                    subject = rendered["subject"]
                    content = rendered["content"]

                if not request.recipients:
                    raise ValidationError("At least one recipient is required")

                notification_id = generate_id("notif")
                notification = Notification(
                    notification_id=notification_id,
                    template_id=request.template_id,
                    channel=request.channel,
                    recipients=request.recipients,
                    subject=subject,
                    rendered_content=content,
                    priority=request.priority,
                    scheduled_at=request.scheduled_at,
                )

                self._notifications[notification_id] = notification

                if request.scheduled_at and request.scheduled_at > datetime.utcnow():
                    notification.status = NotificationStatus.QUEUED
                    asyncio.create_task(self._schedule_notification(notification, request.scheduled_at))
                else:
                    await self._queue_notification(notification)

                emit_event(
                    "notification.created",
                    {"notification_id": notification_id, "channel": request.channel.value},
                    source="notification",
                )

                self._metrics.increment("notification_created")
                return notification

            except ValidationError:
                raise
            except Exception as e:
                logger.error(f"Failed to send notification: {e}")
                raise PlatformError(f"通知发送失败: {str(e)}")

    async def _queue_notification(self, notification: Notification) -> None:
        async with self._lock:
            priority = -notification.priority.value
            scheduled_ts = notification.scheduled_at.timestamp() if notification.scheduled_at else 0
            self._queue.append((priority, scheduled_ts, notification.notification_id, notification))
            self._queue.sort(key=lambda x: (x[0], x[1]))
            notification.status = NotificationStatus.QUEUED

    async def _schedule_notification(self, notification: Notification, scheduled_at: datetime) -> None:
        delay = (scheduled_at - datetime.utcnow()).total_seconds()
        if delay > 0:
            await asyncio.sleep(delay)
        await self._queue_notification(notification)

    async def process_queue(self, max_concurrent: int = 10) -> None:
        while True:
            if len(self._sending_tasks) >= max_concurrent:
                await asyncio.sleep(0.1)
                continue

            async with self._lock:
                if not self._queue:
                    break

                now = datetime.utcnow().timestamp()
                ready = []
                remaining = []

                for item in self._queue:
                    priority, scheduled_ts, notif_id, notification = item
                    if scheduled_ts <= now:
                        if self._check_rate_limit(notification.channel):
                            ready.append(item)
                        else:
                            remaining.append(item)
                    else:
                        remaining.append(item)
                        break

                self._queue = remaining

            for priority, scheduled_ts, notif_id, notification in ready:
                if len(self._sending_tasks) >= max_concurrent:
                    async with self._lock:
                        self._queue.append((priority, scheduled_ts, notif_id, notification))
                        self._queue.sort(key=lambda x: (x[0], x[1]))
                    continue

                self._sending_tasks[notif_id] = asyncio.create_task(
                    self._send_notification(notification)
                )

            await asyncio.sleep(0.01)

    def _check_rate_limit(self, channel: NotificationChannel) -> bool:
        config = self._channel_configs.get(channel)
        if not config:
            return True

        now = datetime.utcnow()
        timestamps = self._rate_limits[channel]
        timestamps = [t for t in timestamps if (now - t).total_seconds() < 60]
        self._rate_limits[channel] = timestamps

        if len(timestamps) >= config.rate_limit_per_minute:
            return False

        timestamps.append(now)
        return True

    async def _send_notification(self, notification: Notification) -> None:
        notification.status = NotificationStatus.SENT
        notification.sent_at = datetime.utcnow()
        self._notifications[notification.notification_id] = notification

        try:
            result = await self._send_via_channel(notification)

            notification.status = NotificationStatus.DELIVERED
            notification.delivered_at = datetime.utcnow()
            self._notifications[notification.notification_id] = notification

            emit_event(
                "notification.delivered",
                {"notification_id": notification.notification_id},
                source="notification",
            )

            self._metrics.increment("notification_delivered")

        except Exception as e:
            notification.retry_count += 1
            if notification.retry_count < notification.max_retries:
                notification.status = NotificationStatus.QUEUED
                async with self._lock:
                    priority = -notification.priority.value
                    retry_ts = datetime.utcnow().timestamp() + (notification.retry_count * 5)
                    self._queue.append((priority, retry_ts, notification.notification_id, notification))
                    self._queue.sort(key=lambda x: (x[0], x[1]))
                logger.warning(
                    f"Notification {notification.notification_id} failed, "
                    f"retry {notification.retry_count}/{notification.max_retries}: {e}"
                )
            else:
                notification.status = NotificationStatus.FAILED
                notification.error_message = str(e)
                self._notifications[notification.notification_id] = notification
                self._metrics.increment("notification_failed")
                emit_event(
                    "notification.failed",
                    {"notification_id": notification.notification_id, "error": str(e)},
                    source="notification",
                )
        finally:
            if notification.notification_id in self._sending_tasks:
                del self._sending_tasks[notification.notification_id]

    async def _send_via_channel(self, notification: Notification) -> bool:
        logger.info(
            f"Sending notification via {notification.channel.value} to {len(notification.recipients)} recipients"
        )

        if notification.channel == NotificationChannel.EMAIL:
            await asyncio.sleep(0.05)
        elif notification.channel == NotificationChannel.SMS:
            await asyncio.sleep(0.03)
        elif notification.channel == NotificationChannel.WEBHOOK:
            await asyncio.sleep(0.02)
        else:
            await asyncio.sleep(0.01)

        return True

    async def get_notification(
        self,
        notification_id: str,
        trace_id: Optional[str] = None,
    ) -> Notification:
        with init_context(trace_id, operation="get_notification"):
            notification = self._notifications.get(notification_id)
            if not notification:
                raise NotFoundError(f"Notification not found: {notification_id}")
            return notification

    async def mark_as_read(
        self,
        notification_id: str,
        trace_id: Optional[str] = None,
    ) -> bool:
        with init_context(trace_id, operation="mark_as_read"):
            notification = await self.get_notification(notification_id)
            notification.status = NotificationStatus.READ
            self._notifications[notification_id] = notification
            return True

    async def configure_channel(
        self,
        config: ChannelConfig,
        trace_id: Optional[str] = None,
    ) -> ChannelConfig:
        with init_context(trace_id, operation="configure_channel"):
            self._channel_configs[config.channel] = config
            emit_event(
                "notification.channel.configured",
                {"channel": config.channel.value},
                source="notification",
            )
            return config

    async def send_batch(
        self,
        requests: List[NotificationRequest],
        trace_id: Optional[str] = None,
    ) -> List[Notification]:
        with init_context(trace_id, operation="send_batch"):
            notifications = []
            for request in requests:
                notif = await self.send_notification(request)
                notifications.append(notif)
            return notifications

    async def get_channel_stats(self, channel: NotificationChannel, trace_id: Optional[str] = None) -> Dict[str, int]:
        ctx = init_context(trace_id)
        try:
            notifications = list(self._notifications.values())
            channel_notifs = [n for n in notifications if n.channel == channel]
            return {
                "total": len(channel_notifs),
                "sent": sum(1 for n in channel_notifs if n.status == NotificationStatus.SENT),
                "delivered": sum(1 for n in channel_notifs if n.status == NotificationStatus.DELIVERED),
                "failed": sum(1 for n in channel_notifs if n.status == NotificationStatus.FAILED),
                "read": sum(1 for n in channel_notifs if n.status == NotificationStatus.READ),
                "pending": sum(1 for n in channel_notifs if n.status in [NotificationStatus.PENDING, NotificationStatus.QUEUED]),
            }
        finally:
            record_metrics(ctx)
            ctx.cleanup()
