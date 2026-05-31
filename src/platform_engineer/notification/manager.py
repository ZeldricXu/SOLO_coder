import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Dict, List, Optional
from uuid import uuid4

from ..core.events import DomainEvent, EventBus, get_global_event_bus
from ..core.exceptions import NotificationError
from .channels import NotificationChannel, NotificationResult
from .retry import ExponentialBackoffPolicy, RetryExecutor, RetryPolicy
from .tracking import DeliveryRecord, DeliveryStatus, DeliveryTracker


class NotificationStatus(Enum):
    DRAFT = "draft"
    QUEUED = "queued"
    SENDING = "sending"
    DELIVERED = "delivered"
    PARTIALLY_DELIVERED = "partially_delivered"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass
class Notification:
    notification_id: str
    status: NotificationStatus
    channels: List[str]
    recipients: List[str]
    subject: str
    content: str
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    priority: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)
    expires_at: Optional[datetime] = None
    error: Optional[str] = None
    delivery_results: List[Dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "notification_id": self.notification_id,
            "status": self.status.value,
            "channels": self.channels,
            "recipients": self.recipients,
            "subject": self.subject,
            "content": self.content,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
            "priority": self.priority,
            "metadata": self.metadata,
            "expires_at": self.expires_at.isoformat() if self.expires_at else None,
            "error": self.error,
            "delivery_results": self.delivery_results,
        }


class NotificationManager:
    def __init__(
        self,
        retry_policy: Optional[RetryPolicy] = None,
        event_bus: Optional[EventBus] = None,
        logger=None,
    ):
        self._channels: Dict[str, NotificationChannel] = {}
        self._notifications: Dict[str, Notification] = {}
        self._retry_policy = retry_policy or ExponentialBackoffPolicy()
        self._event_bus = event_bus or get_global_event_bus()
        self._tracker = DeliveryTracker()
        self._logger = logger
        self._callbacks: List[Callable[[Notification], Any]] = []

    def register_channel(self, channel: NotificationChannel) -> None:
        self._channels[channel.channel_id] = channel

    def unregister_channel(self, channel_id: str) -> bool:
        if channel_id in self._channels:
            del self._channels[channel_id]
            return True
        return False

    def get_channel(self, channel_id: str) -> Optional[NotificationChannel]:
        return self._channels.get(channel_id)

    def create_notification(
        self,
        channels: List[str],
        recipients: List[str],
        subject: str,
        content: str,
        priority: int = 0,
        expires_seconds: Optional[int] = None,
        **metadata,
    ) -> Notification:
        notification_id = f"notif_{uuid4().hex[:12]}"
        expires_at = None
        if expires_seconds:
            expires_at = datetime.now(timezone.utc) + __import__("datetime").timedelta(seconds=expires_seconds)
        notification = Notification(
            notification_id=notification_id,
            status=NotificationStatus.DRAFT,
            channels=channels,
            recipients=recipients,
            subject=subject,
            content=content,
            priority=priority,
            metadata=metadata,
            expires_at=expires_at,
        )
        self._notifications[notification_id] = notification
        return notification

    def on_notification_change(self, callback: Callable[[Notification], Any]) -> None:
        self._callbacks.append(callback)

    async def _notify_callbacks(self, notification: Notification) -> None:
        for callback in self._callbacks:
            try:
                result = callback(notification)
                if asyncio.iscoroutine(result):
                    await result
            except Exception as e:
                if self._logger:
                    self._logger.error(f"Notification callback error: {e}")

    async def _update_status(self, notification: Notification, status: NotificationStatus, error: Optional[str] = None) -> None:
        notification.status = status
        notification.updated_at = datetime.now(timezone.utc)
        if error:
            notification.error = error
        await self._notify_callbacks(notification)
        event = DomainEvent(
            event_type=f"notification.{status.value}",
            payload=notification.to_dict(),
            source="notification_manager",
        )
        await self._event_bus.publish(event)

    async def send(self, notification: Notification) -> Notification:
        valid_channels = [c for c in notification.channels if c in self._channels]
        if not valid_channels:
            await self._update_status(notification, NotificationStatus.FAILED, "No valid channels")
            return notification

        await self._update_status(notification, NotificationStatus.SENDING)
        all_results: List[Dict[str, Any]] = []
        total_success = 0
        total_attempts = 0

        for channel_id in valid_channels:
            channel = self._channels[channel_id]
            for recipient in notification.recipients:
                record = self._tracker.create_record(
                    notification.notification_id,
                    channel_id,
                    recipient,
                    expires_seconds=3600,
                )
                record.add_attempt(DeliveryStatus.IN_PROGRESS)
                total_attempts += 1
                try:
                    executor = RetryExecutor(self._retry_policy, self._logger)
                    result = await executor.execute(
                        channel.send,
                        recipient,
                        notification.subject,
                        notification.content,
                        **notification.metadata,
                    )
                    if result.success:
                        total_success += 1
                        record.complete_attempt(DeliveryStatus.DELIVERED)
                        record.message_id = result.message_id
                    else:
                        record.complete_attempt(DeliveryStatus.FAILED, error=result.error)
                    all_results.append({
                        "channel": channel_id,
                        "recipient": recipient,
                        "success": result.success,
                        "message_id": result.message_id,
                        "error": result.error,
                    })
                except Exception as e:
                    record.complete_attempt(DeliveryStatus.FAILED, error=str(e))
                    all_results.append({
                        "channel": channel_id,
                        "recipient": recipient,
                        "success": False,
                        "error": str(e),
                    })

        notification.delivery_results = all_results

        if total_success == total_attempts:
            await self._update_status(notification, NotificationStatus.DELIVERED)
        elif total_success > 0:
            await self._update_status(notification, NotificationStatus.PARTIALLY_DELIVERED)
        else:
            await self._update_status(notification, NotificationStatus.FAILED, "All deliveries failed")

        return notification

    async def send_now(
        self,
        channels: List[str],
        recipients: List[str],
        subject: str,
        content: str,
        **kwargs,
    ) -> Notification:
        notification = self.create_notification(channels, recipients, subject, content, **kwargs)
        return await self.send(notification)

    def get_notification(self, notification_id: str) -> Optional[Notification]:
        return self._notifications.get(notification_id)

    def list_notifications(
        self,
        status: Optional[NotificationStatus] = None,
        limit: int = 100,
    ) -> List[Notification]:
        notifications = list(self._notifications.values())
        if status:
            notifications = [n for n in notifications if n.status == status]
        notifications.sort(key=lambda n: n.created_at, reverse=True)
        return notifications[:limit]

    def get_tracker(self) -> DeliveryTracker:
        return self._tracker

    def get_stats(self) -> Dict[str, Any]:
        total = len(self._notifications)
        by_status: Dict[str, int] = {}
        for status in NotificationStatus:
            by_status[status.value] = 0
        for notification in self._notifications.values():
            by_status[notification.status.value] += 1
        return {
            "total_notifications": total,
            "by_status": by_status,
            "channels": {cid: ch.get_stats() for cid, ch in self._channels.items()},
            "delivery_tracker": self._tracker.get_stats(),
        }


_global_notification_manager: Optional[NotificationManager] = None


def get_notification_manager() -> NotificationManager:
    global _global_notification_manager
    if _global_notification_manager is None:
        _global_notification_manager = NotificationManager()
    return _global_notification_manager


def set_notification_manager(manager: NotificationManager) -> None:
    global _global_notification_manager
    _global_notification_manager = manager
