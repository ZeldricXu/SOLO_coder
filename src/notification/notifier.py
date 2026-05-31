import asyncio
import json
import time
from abc import ABC, abstractmethod
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Protocol, runtime_checkable

try:
    import httpx
    HAS_HTTPX = True
except ImportError:
    HAS_HTTPX = False

from src.config import get_settings
from src.logging_ import get_logger
from src.models import Notification, NotificationChannel, NotificationStatus
from src.utils.errors import NotificationError
from src.utils.helpers import retry_async, sanitize_dict

logger = get_logger(__name__)


@runtime_checkable
class NotificationProvider(Protocol):
    async def send(self, notification: Notification) -> bool:
        ...


class BaseNotificationProvider(ABC):
    def __init__(self, timeout: int = 30):
        self.timeout = timeout
        self.client = httpx.AsyncClient(timeout=timeout) if HAS_HTTPX else None

    @abstractmethod
    async def send(self, notification: Notification) -> bool:
        pass

    async def close(self) -> None:
        if self.client:
            await self.client.aclose()

    def _log_send(self, channel: str, recipient: str, extra: Optional[Dict[str, Any]] = None) -> None:
        log_extra = extra or {}
        log_extra.update({"channel": channel, "recipient": recipient})
        logger.info("Sending %s notification to %s", channel, recipient, extra=log_extra)

    def _sanitize_payload(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return sanitize_dict(payload)


class EmailProvider(BaseNotificationProvider):
    def __init__(self, smtp_host: str = "localhost", smtp_port: int = 25, **kwargs):
        super().__init__(**kwargs)
        self.smtp_host = smtp_host
        self.smtp_port = smtp_port

    async def send(self, notification: Notification) -> bool:
        self._log_send("email", notification.recipient)

        payload = {
            "to": notification.recipient,
            "subject": notification.subject,
            "body": notification.content,
            "metadata": notification.metadata,
        }

        logger.debug("Email payload: %s", self._sanitize_payload(payload))
        return True


class SMSProvider(BaseNotificationProvider):
    def __init__(self, api_url: str = "https://api.sms-provider.com", api_key: str = "", **kwargs):
        super().__init__(**kwargs)
        self.api_url = api_url
        self.api_key = api_key

    async def send(self, notification: Notification) -> bool:
        self._log_send("sms", notification.recipient)

        payload = {
            "phone": notification.recipient,
            "message": notification.content,
        }

        logger.debug("SMS payload: %s", self._sanitize_payload(payload))
        return True


class WebhookProvider(BaseNotificationProvider):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)

    async def send(self, notification: Notification) -> bool:
        webhook_url = notification.recipient
        self._log_send("webhook", webhook_url)

        payload = {
            "notification_id": notification.notification_id,
            "content": notification.content,
            "subject": notification.subject,
            "metadata": notification.metadata,
            "timestamp": datetime.utcnow().isoformat(),
        }

        if not HAS_HTTPX:
            logger.warning(
                "httpx not available, simulating webhook send to %s",
                webhook_url,
            )
            return True

        try:
            response = await self.client.post(
                webhook_url,
                json=payload,
                headers={"Content-Type": "application/json"},
            )
            response.raise_for_status()
            logger.info(
                "Webhook notification sent to %s, status: %d",
                webhook_url,
                response.status_code,
            )
            return True

        except httpx.HTTPError as e:
            logger.error(
                "Webhook notification failed: %s",
                str(e),
                extra={"webhook_url": webhook_url},
            )
            raise NotificationError(f"Webhook send failed: {str(e)}") from e


class SlackProvider(BaseNotificationProvider):
    def __init__(self, bot_token: str = "", **kwargs):
        super().__init__(**kwargs)
        self.bot_token = bot_token

    async def send(self, notification: Notification) -> bool:
        channel = notification.recipient
        self._log_send("slack", channel)

        payload = {
            "channel": channel,
            "text": notification.content,
            "username": "Task Orchestrator",
        }

        if notification.subject:
            payload["blocks"] = [
                {"type": "header", "text": {"type": "plain_text", "text": notification.subject}},
                {"type": "section", "text": {"type": "mrkdwn", "text": notification.content}},
            ]

        return True


class DingTalkProvider(BaseNotificationProvider):
    def __init__(self, access_token: str = "", **kwargs):
        super().__init__(**kwargs)
        self.access_token = access_token

    async def send(self, notification: Notification) -> bool:
        self._log_send("dingtalk", notification.recipient)
        return True


@dataclass
class DeliveryStatus:
    notification_id: str
    status: NotificationStatus
    delivered: bool = False
    last_attempt: Optional[datetime] = None
    attempts: int = 0
    error: Optional[str] = None
    response_data: Dict[str, Any] = field(default_factory=dict)


class NotificationManager:
    def __init__(self):
        self.settings = get_settings()
        self._providers: Dict[NotificationChannel, BaseNotificationProvider] = {
            NotificationChannel.EMAIL: EmailProvider(timeout=self.settings.NOTIFICATION_TIMEOUT),
            NotificationChannel.SMS: SMSProvider(timeout=self.settings.NOTIFICATION_TIMEOUT),
            NotificationChannel.WEBHOOK: WebhookProvider(timeout=self.settings.NOTIFICATION_TIMEOUT),
            NotificationChannel.SLACK: SlackProvider(timeout=self.settings.NOTIFICATION_TIMEOUT),
            NotificationChannel.DINGTALK: DingTalkProvider(timeout=self.settings.NOTIFICATION_TIMEOUT),
        }
        self._delivery_tracker: Dict[str, DeliveryStatus] = {}
        self._notification_store: Dict[str, Notification] = {}

    @staticmethod
    def _create_tracker(notification_id: str) -> DeliveryStatus:
        return DeliveryStatus(
            notification_id=notification_id,
            status=NotificationStatus.PENDING,
        )

    def _update_tracker_status(
        self,
        tracker: DeliveryStatus,
        status: NotificationStatus,
        error: Optional[str] = None,
    ) -> None:
        tracker.status = status
        tracker.delivered = status == NotificationStatus.DELIVERED
        if error:
            tracker.error = error

    def _update_notification_status(
        self,
        notification: Notification,
        status: NotificationStatus,
        error: Optional[str] = None,
    ) -> None:
        notification.status = status
        notification.sent_at = datetime.utcnow()
        if status == NotificationStatus.DELIVERED:
            notification.delivered_at = datetime.utcnow()
        if error:
            notification.error_message = error

    def _format_statistics(
        self,
        total: int,
        by_channel: Dict[str, int],
        by_status: Dict[str, int],
        total_attempts: int,
    ) -> Dict[str, Any]:
        return {
            "total_notifications": total,
            "by_channel": dict(by_channel),
            "by_status": dict(by_status),
            "total_delivery_attempts": total_attempts,
            "average_attempts_per_notification": (
                total_attempts / total if total > 0 else 0
            ),
        }

    def register_provider(
        self,
        channel: NotificationChannel,
        provider: BaseNotificationProvider,
    ) -> None:
        self._providers[channel] = provider
        logger.info("Registered provider for channel: %s", channel.value)

    def get_provider(self, channel: NotificationChannel) -> BaseNotificationProvider:
        if channel not in self._providers:
            raise NotificationError(f"No provider registered for channel: {channel.value}")
        return self._providers[channel]

    def _ensure_stored(self, notification: Notification) -> None:
        if notification.notification_id not in self._notification_store:
            self._notification_store[notification.notification_id] = notification
            self._delivery_tracker[notification.notification_id] = self._create_tracker(
                notification.notification_id
            )

    def create_notification(
        self,
        channel: NotificationChannel,
        recipient: str,
        content: str,
        subject: Optional[str] = None,
        max_retries: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Notification:
        notification = Notification(
            channel=channel,
            recipient=recipient,
            content=content,
            subject=subject,
            max_retries=max_retries or self.settings.NOTIFICATION_RETRY_MAX_ATTEMPTS,
            metadata=metadata or {},
        )
        self._ensure_stored(notification)
        return notification

    async def _send_with_retry(
        self,
        notification: Notification,
    ) -> DeliveryStatus:
        provider = self.get_provider(notification.channel)
        tracker = self._delivery_tracker[notification.notification_id]
        notification.status = NotificationStatus.RETRYING

        @retry_async(
            max_attempts=notification.max_retries,
            delay=self.settings.NOTIFICATION_RETRY_DELAY,
            backoff=2.0,
            exceptions=(NotificationError, httpx.HTTPError),
        )
        async def _attempt_send() -> bool:
            tracker.attempts += 1
            tracker.last_attempt = datetime.utcnow()
            notification.retry_count = tracker.attempts

            logger.info(
                "Attempt %d/%d to send notification %s via %s",
                tracker.attempts,
                notification.max_retries,
                notification.notification_id,
                notification.channel.value,
            )

            return await provider.send(notification)

        try:
            success = await _attempt_send()
            final_status = NotificationStatus.DELIVERED if success else NotificationStatus.FAILED
            self._update_tracker_status(tracker, final_status)
            self._update_notification_status(notification, final_status)

        except Exception as e:
            error_msg = str(e)
            self._update_tracker_status(tracker, NotificationStatus.FAILED, error_msg)
            self._update_notification_status(notification, NotificationStatus.FAILED, error_msg)
            logger.error(
                "Notification %s failed after %d attempts: %s",
                notification.notification_id,
                tracker.attempts,
                error_msg,
            )

        return tracker

    async def send(self, notification: Notification) -> DeliveryStatus:
        self._ensure_stored(notification)
        notification.status = NotificationStatus.SENT
        return await self._send_with_retry(notification)

    async def send_immediate(
        self,
        channel: NotificationChannel,
        recipient: str,
        content: str,
        subject: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> DeliveryStatus:
        notification = self.create_notification(
            channel=channel,
            recipient=recipient,
            content=content,
            subject=subject,
            metadata=metadata,
        )
        return await self.send(notification)

    async def batch_send(
        self,
        notifications: List[Notification],
        max_concurrent: int = 10,
    ) -> List[DeliveryStatus]:
        semaphore = asyncio.Semaphore(max_concurrent)

        async def _send_with_semaphore(notification: Notification) -> DeliveryStatus:
            async with semaphore:
                return await self.send(notification)

        tasks = [_send_with_semaphore(n) for n in notifications]
        return await asyncio.gather(*tasks)

    def get_delivery_status(self, notification_id: str) -> Optional[DeliveryStatus]:
        return self._delivery_tracker.get(notification_id)

    def get_notification(self, notification_id: str) -> Optional[Notification]:
        return self._notification_store.get(notification_id)

    async def retry_failed(
        self,
        since: Optional[datetime] = None,
        channels: Optional[List[NotificationChannel]] = None,
    ) -> List[DeliveryStatus]:
        failed_notifications = [
            n
            for n in self._notification_store.values()
            if n.status == NotificationStatus.FAILED
            and (since is None or n.sent_at is None or n.sent_at >= since)
            and (channels is None or n.channel in channels)
        ]

        results: List[DeliveryStatus] = []
        for notification in failed_notifications:
            tracker = self._delivery_tracker.get(notification.notification_id)
            if tracker and tracker.attempts < notification.max_retries * 2:
                result = await self.send(notification)
                results.append(result)

        return results

    async def track_delivery(
        self,
        notification_id: str,
        timeout: int = 300,
        check_interval: int = 5,
    ) -> Optional[DeliveryStatus]:
        start_time = time.time()
        terminal_statuses = (NotificationStatus.DELIVERED, NotificationStatus.FAILED)

        while time.time() - start_time < timeout:
            tracker = self.get_delivery_status(notification_id)
            if tracker and tracker.status in terminal_statuses:
                return tracker
            await asyncio.sleep(check_interval)
        return self.get_delivery_status(notification_id)

    def get_statistics(self) -> Dict[str, Any]:
        total = len(self._notification_store)
        by_channel: Dict[str, int] = defaultdict(int)
        by_status: Dict[str, int] = defaultdict(int)
        total_attempts = 0

        for notification in self._notification_store.values():
            by_channel[notification.channel.value] += 1
            by_status[notification.status.value] += 1

        for tracker in self._delivery_tracker.values():
            total_attempts += tracker.attempts

        return self._format_statistics(total, by_channel, by_status, total_attempts)

    async def close(self) -> None:
        for provider in self._providers.values():
            await provider.close()
        logger.info("Notification manager closed")
