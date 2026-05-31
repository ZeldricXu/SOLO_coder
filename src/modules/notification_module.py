import asyncio
import json
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Callable, Union, Set
from enum import Enum
from dataclasses import dataclass, field
from collections import defaultdict, deque

from .logging_module import get_logger
from .config_module import get_app_config
from .event_store import EventStore, EventType, get_event_store
from .storage_module import StorageManager, get_storage_manager

logger = get_logger(__name__)


class NotificationPriority(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class NotificationChannel(str, Enum):
    EMAIL = "email"
    SLACK = "slack"
    WEBHOOK = "webhook"
    SMS = "sms"
    PUSH = "push"
    IN_APP = "in_app"
    CONSOLE = "console"


class NotificationStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    SENT = "sent"
    DELIVERED = "delivered"
    FAILED = "failed"
    SUPPRESSED = "suppressed"


class SuppressionStrategy(str, Enum):
    NONE = "none"
    RATE_LIMIT = "rate_limit"
    DEDUPLICATION = "deduplication"
    THROTTLING = "throttling"
    SILENCE = "silence"


@dataclass
class Notification:
    notification_id: str
    title: str
    message: str
    priority: NotificationPriority
    channels: List[NotificationChannel] = field(default_factory=list)
    recipients: List[str] = field(default_factory=list)
    status: NotificationStatus = NotificationStatus.PENDING
    created_at: datetime = field(default_factory=datetime.utcnow)
    sent_at: Optional[datetime] = None
    delivered_at: Optional[datetime] = None
    failed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    correlation_id: Optional[str] = None
    deduplication_key: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    ttl: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "notification_id": self.notification_id,
            "title": self.title,
            "message": self.message,
            "priority": self.priority.value,
            "channels": [c.value for c in self.channels],
            "recipients": self.recipients,
            "status": self.status.value,
            "created_at": self.created_at.isoformat(),
            "sent_at": self.sent_at.isoformat() if self.sent_at else None,
            "delivered_at": self.delivered_at.isoformat() if self.delivered_at else None,
            "failed_at": self.failed_at.isoformat() if self.failed_at else None,
            "error_message": self.error_message,
            "metadata": self.metadata,
            "correlation_id": self.correlation_id,
            "deduplication_key": self.deduplication_key,
            "tags": self.tags,
            "ttl": self.ttl,
        }


@dataclass
class SuppressionRule:
    rule_id: str
    strategy: SuppressionStrategy
    enabled: bool = True
    priority_threshold: Optional[NotificationPriority] = None
    tags: Optional[List[str]] = None
    channels: Optional[List[NotificationChannel]] = None
    rate_limit: Optional[int] = None
    rate_limit_window: int = 60
    duration: Optional[int] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    created_by: Optional[str] = None
    description: Optional[str] = None


class NotificationBackend(ABC):
    @abstractmethod
    async def send(self, notification: Notification) -> bool:
        pass

    @abstractmethod
    def supports_channel(self, channel: NotificationChannel) -> bool:
        pass


class ConsoleBackend(NotificationBackend):
    async def send(self, notification: Notification) -> bool:
        print(f"[NOTIFICATION {notification.priority.value.upper()}] {notification.title}")
        print(f"  {notification.message}")
        if notification.recipients:
            print(f"  Recipients: {', '.join(notification.recipients)}")
        logger.info("Notification sent via console", notification_id=notification.notification_id)
        return True

    def supports_channel(self, channel: NotificationChannel) -> bool:
        return channel == NotificationChannel.CONSOLE


class EmailBackend(NotificationBackend):
    def __init__(self, host: str, port: int, user: str, password: str):
        self.host = host
        self.port = port
        self.user = user
        self.password = password

    async def send(self, notification: Notification) -> bool:
        try:
            import aiosmtplib
            from email.message import EmailMessage

            msg = EmailMessage()
            msg["Subject"] = notification.title
            msg["From"] = self.user
            msg["To"] = ", ".join(notification.recipients)
            msg.set_content(notification.message)

            await aiosmtplib.send(
                msg,
                hostname=self.host,
                port=self.port,
                username=self.user,
                password=self.password,
                use_tls=True,
            )
            logger.info("Email notification sent", notification_id=notification.notification_id)
            return True
        except Exception as e:
            logger.error("Email send failed", notification_id=notification.notification_id, error=str(e))
            return False

    def supports_channel(self, channel: NotificationChannel) -> bool:
        return channel == NotificationChannel.EMAIL


class SlackBackend(NotificationBackend):
    def __init__(self, webhook_url: str):
        self.webhook_url = webhook_url

    async def send(self, notification: Notification) -> bool:
        try:
            import httpx

            color = {
                NotificationPriority.LOW: "#36a64f",
                NotificationPriority.MEDIUM: "#daa038",
                NotificationPriority.HIGH: "#dc3545",
                NotificationPriority.CRITICAL: "#ff0000",
            }.get(notification.priority, "#36a64f")

            payload = {
                "attachments": [
                    {
                        "color": color,
                        "title": notification.title,
                        "text": notification.message,
                        "ts": int(datetime.utcnow().timestamp()),
                    }
                ]
            }

            if notification.tags:
                payload["attachments"][0]["fields"] = [
                    {"title": "Tags", "value": ", ".join(notification.tags), "short": True}
                ]

            async with httpx.AsyncClient() as client:
                response = await client.post(self.webhook_url, json=payload)
                response.raise_for_status()

            logger.info("Slack notification sent", notification_id=notification.notification_id)
            return True
        except Exception as e:
            logger.error("Slack send failed", notification_id=notification.notification_id, error=str(e))
            return False

    def supports_channel(self, channel: NotificationChannel) -> bool:
        return channel == NotificationChannel.SLACK


class WebhookBackend(NotificationBackend):
    def __init__(self, webhook_url: str):
        self.webhook_url = webhook_url

    async def send(self, notification: Notification) -> bool:
        try:
            import httpx

            payload = notification.to_dict()

            async with httpx.AsyncClient() as client:
                response = await client.post(self.webhook_url, json=payload)
                response.raise_for_status()

            logger.info("Webhook notification sent", notification_id=notification.notification_id)
            return True
        except Exception as e:
            logger.error("Webhook send failed", notification_id=notification.notification_id, error=str(e))
            return False

    def supports_channel(self, channel: NotificationChannel) -> bool:
        return channel == NotificationChannel.WEBHOOK


class NotificationManager:
    _instance: Optional['NotificationManager'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, event_store: Optional[EventStore] = None, storage: Optional[StorageManager] = None):
        if self._initialized:
            return

        config = get_app_config()
        self._event_store = event_store or get_event_store()
        self._storage = storage or get_storage_manager()

        self._backends: List[NotificationBackend] = []
        self._suppression_rules: Dict[str, SuppressionRule] = {}
        self._notification_history: deque[Notification] = deque(maxlen=10000)
        self._recent_notifications: Dict[str, deque[datetime]] = defaultdict(lambda: deque(maxlen=1000))
        self._deduplication_cache: Dict[str, datetime] = {}
        self._deduplication_ttl: int = 300

        self._default_channels = [
            NotificationChannel(c) for c in config.notification.default_channels
        ]

        self._setup_default_backends(config)
        self._initialized = True

    def _setup_default_backends(self, config):
        self._backends.append(ConsoleBackend())

        if config.notification.email_host:
            try:
                self._backends.append(EmailBackend(
                    host=config.notification.email_host,
                    port=config.notification.email_port,
                    user=config.notification.email_user or "",
                    password=config.notification.email_password or "",
                ))
            except Exception as e:
                logger.warning("Failed to initialize email backend", error=str(e))

        if config.notification.slack_webhook:
            try:
                self._backends.append(SlackBackend(config.notification.slack_webhook))
            except Exception as e:
                logger.warning("Failed to initialize Slack backend", error=str(e))

        if config.notification.webhook_url:
            try:
                self._backends.append(WebhookBackend(config.notification.webhook_url))
            except Exception as e:
                logger.warning("Failed to initialize webhook backend", error=str(e))

    def add_backend(self, backend: NotificationBackend) -> None:
        self._backends.append(backend)

    def create_notification(
        self,
        title: str,
        message: str,
        priority: NotificationPriority = NotificationPriority.MEDIUM,
        channels: Optional[List[NotificationChannel]] = None,
        recipients: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        correlation_id: Optional[str] = None,
        deduplication_key: Optional[str] = None,
        tags: Optional[List[str]] = None,
        ttl: Optional[int] = None,
    ) -> Notification:
        return Notification(
            notification_id=str(uuid.uuid4()),
            title=title,
            message=message,
            priority=priority,
            channels=channels or self._default_channels,
            recipients=recipients or [],
            metadata=metadata or {},
            correlation_id=correlation_id,
            deduplication_key=deduplication_key,
            tags=tags or [],
            ttl=ttl,
        )

    def _should_suppress(self, notification: Notification) -> Optional[SuppressionRule]:
        for rule in self._suppression_rules.values():
            if not rule.enabled:
                continue

            if rule.priority_threshold:
                priority_order = [p for p in NotificationPriority]
                if priority_order.index(notification.priority) < priority_order.index(rule.priority_threshold):
                    continue

            if rule.tags and not any(tag in notification.tags for tag in rule.tags):
                continue

            if rule.channels and not any(c in notification.channels for c in rule.channels):
                continue

            if rule.start_time and datetime.utcnow() < rule.start_time:
                continue
            if rule.end_time and datetime.utcnow() > rule.end_time:
                continue

            if rule.strategy == SuppressionStrategy.SILENCE:
                return rule

            if rule.strategy == SuppressionStrategy.RATE_LIMIT and rule.rate_limit:
                key = f"rate_{rule.rule_id}"
                recent = self._recent_notifications[key]
                window_start = datetime.utcnow() - timedelta(seconds=rule.rate_limit_window)
                recent_count = sum(1 for t in recent if t > window_start)
                if recent_count >= rule.rate_limit:
                    return rule

            if rule.strategy == SuppressionStrategy.DEDUPLICATION and notification.deduplication_key:
                dedup_key = notification.deduplication_key
                if dedup_key in self._deduplication_cache:
                    last_sent = self._deduplication_cache[dedup_key]
                    if datetime.utcnow() - last_sent < timedelta(seconds=self._deduplication_ttl):
                        return rule

        return None

    async def send(self, notification: Notification) -> Notification:
        if self._should_suppress(notification):
            notification.status = NotificationStatus.SUPPRESSED
            logger.info("Notification suppressed", notification_id=notification.notification_id)
            self._notification_history.append(notification)
            return notification

        if notification.deduplication_key:
            self._deduplication_cache[notification.deduplication_key] = datetime.utcnow()

        notification.status = NotificationStatus.QUEUED
        self._notification_history.append(notification)

        for channel in notification.channels:
            for backend in self._backends:
                if backend.supports_channel(channel):
                    try:
                        success = await backend.send(notification)
                        if success:
                            notification.status = NotificationStatus.SENT
                            notification.sent_at = datetime.utcnow()
                        else:
                            notification.status = NotificationStatus.FAILED
                            notification.failed_at = datetime.utcnow()
                            notification.error_message = f"Backend failed for channel {channel}"
                    except Exception as e:
                        notification.status = NotificationStatus.FAILED
                        notification.failed_at = datetime.utcnow()
                        notification.error_message = str(e)
                        logger.error("Notification send failed", notification_id=notification.notification_id, error=str(e))
                    break

        asyncio.create_task(self._event_store.append(
            aggregate_id=notification.notification_id,
            event_type=EventType.NOTIFICATION_SENT,
            payload={"notification": notification.to_dict()},
            metadata={"phase": "notification"},
        ))

        return notification

    async def send_immediately(
        self,
        title: str,
        message: str,
        priority: NotificationPriority = NotificationPriority.MEDIUM,
        channels: Optional[List[NotificationChannel]] = None,
        recipients: Optional[List[str]] = None,
        **kwargs,
    ) -> Notification:
        notification = self.create_notification(
            title=title,
            message=message,
            priority=priority,
            channels=channels,
            recipients=recipients,
            **kwargs,
        )
        return await self.send(notification)

    def add_suppression_rule(self, rule: SuppressionRule) -> SuppressionRule:
        self._suppression_rules[rule.rule_id] = rule
        logger.info("Suppression rule added", rule_id=rule.rule_id, strategy=rule.strategy)
        return rule

    def remove_suppression_rule(self, rule_id: str) -> bool:
        if rule_id in self._suppression_rules:
            del self._suppression_rules[rule_id]
            logger.info("Suppression rule removed", rule_id=rule_id)
            return True
        return False

    def get_suppression_rules(self) -> List[SuppressionRule]:
        return list(self._suppression_rules.values())

    def silence(
        self,
        duration: int,
        tags: Optional[List[str]] = None,
        channels: Optional[List[NotificationChannel]] = None,
        priority_threshold: Optional[NotificationPriority] = None,
        created_by: Optional[str] = None,
        description: Optional[str] = None,
    ) -> SuppressionRule:
        rule = SuppressionRule(
            rule_id=str(uuid.uuid4()),
            strategy=SuppressionStrategy.SILENCE,
            enabled=True,
            priority_threshold=priority_threshold,
            tags=tags,
            channels=channels,
            start_time=datetime.utcnow(),
            end_time=datetime.utcnow() + timedelta(seconds=duration),
            created_by=created_by,
            description=description or f"Silence for {duration} seconds",
        )
        return self.add_suppression_rule(rule)

    def get_recent_notifications(
        self,
        limit: int = 100,
        status: Optional[NotificationStatus] = None,
        priority: Optional[NotificationPriority] = None,
    ) -> List[Notification]:
        notifications = list(self._notification_history)
        if status:
            notifications = [n for n in notifications if n.status == status]
        if priority:
            notifications = [n for n in notifications if n.priority == priority]
        return notifications[-limit:]

    async def send_batch(self, notifications: List[Notification]) -> List[Notification]:
        results = []
        for notification in notifications:
            result = await self.send(notification)
            results.append(result)
        return results

    def get_stats(self) -> Dict[str, Any]:
        status_counts = defaultdict(int)
        priority_counts = defaultdict(int)

        for notification in self._notification_history:
            status_counts[notification.status.value] += 1
            priority_counts[notification.priority.value] += 1

        return {
            "total": len(self._notification_history),
            "by_status": dict(status_counts),
            "by_priority": dict(priority_counts),
            "suppression_rules": len(self._suppression_rules),
            "backends": len(self._backends),
        }

    def cleanup_old_entries(self) -> int:
        cutoff = datetime.utcnow() - timedelta(seconds=self._deduplication_ttl * 2)
        old_keys = [k for k, v in self._deduplication_cache.items() if v < cutoff]
        for key in old_keys:
            del self._deduplication_cache[key]
        return len(old_keys)


def get_notification_manager() -> NotificationManager:
    return NotificationManager()
