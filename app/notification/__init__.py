"""
Notification Module.
Implements notification prioritization, throttling, and suppression strategies.
"""

import asyncio
import time
from abc import ABC, abstractmethod
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Set

from app.logging import get_logger
from app.models import NotificationPriority, AlertSeverity


class NotificationChannel(str, Enum):
    EMAIL = "email"
    SMS = "sms"
    WEBHOOK = "webhook"
    SLACK = "slack"
    TELEGRAM = "telegram"
    PAGERDUTY = "pagerduty"


class NotificationStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    SENT = "sent"
    FAILED = "failed"
    SUPPRESSED = "suppressed"
    THROTTLED = "throttled"


@dataclass
class Notification:
    notification_id: str
    title: str
    content: str
    priority: NotificationPriority
    channels: List[NotificationChannel]
    recipients: List[str]
    timestamp: datetime = field(default_factory=datetime.utcnow)
    status: NotificationStatus = NotificationStatus.PENDING
    tags: Dict[str, str] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    error: Optional[str] = None
    sent_at: Optional[datetime] = None
    deduplication_key: Optional[str] = None


class NotificationSender(ABC):
    @abstractmethod
    async def send(self, notification: Notification) -> bool:
        pass


class ConsoleSender(NotificationSender):
    def __init__(self):
        self._logger = get_logger("console_sender")
        self._sent: List[Notification] = []
    
    async def send(self, notification: Notification) -> bool:
        self._logger.info(
            "Console notification",
            title=notification.title,
            priority=notification.priority.value,
            channels=[c.value for c in notification.channels]
        )
        self._sent.append(notification)
        return True
    
    def get_sent(self) -> List[Notification]:
        return list(self._sent)
    
    def clear(self):
        self._sent.clear()


class WebhookSender(NotificationSender):
    def __init__(self, webhook_url: str):
        self._webhook_url = webhook_url
        self._logger = get_logger("webhook_sender")
    
    async def send(self, notification: Notification) -> bool:
        payload = {
            "notification_id": notification.notification_id,
            "title": notification.title,
            "content": notification.content,
            "priority": notification.priority.value,
            "recipients": notification.recipients,
            "timestamp": notification.timestamp.isoformat(),
            "tags": notification.tags
        }
        self._logger.debug(
            "Webhook notification",
            url=self._webhook_url,
            payload_size=len(str(payload))
        )
        return True


class EmailSender(NotificationSender):
    def __init__(self, smtp_host: str = "localhost", smtp_port: int = 25):
        self._smtp_host = smtp_host
        self._smtp_port = smtp_port
        self._logger = get_logger("email_sender")
    
    async def send(self, notification: Notification) -> bool:
        self._logger.info(
            "Email notification",
            to=notification.recipients,
            title=notification.title
        )
        return True


class ThrottlingRule:
    def __init__(
        self,
        max_count: int,
        window_seconds: int,
        priority: Optional[NotificationPriority] = None,
        tags: Optional[Dict[str, str]] = None
    ):
        self.max_count = max_count
        self.window_seconds = window_seconds
        self.priority = priority
        self.tags = tags or {}
        self._timestamps: List[float] = []
    
    def should_throttle(self, notification: Notification) -> bool:
        if self.priority and notification.priority != self.priority:
            return False
        
        for k, v in self.tags.items():
            if notification.tags.get(k) != v:
                return False
        
        now = time.time()
        cutoff = now - self.window_seconds
        self._timestamps = [t for t in self._timestamps if t > cutoff]
        
        if len(self._timestamps) >= self.max_count:
            return True
        
        self._timestamps.append(now)
        return False


class SuppressionRule:
    def __init__(
        self,
        duration_seconds: int,
        match_tags: Optional[Dict[str, str]] = None,
        match_priority: Optional[NotificationPriority] = None
    ):
        self.duration_seconds = duration_seconds
        self.match_tags = match_tags or {}
        self.match_priority = match_priority
        self._suppressed_keys: Dict[str, float] = {}
    
    def _get_key(self, notification: Notification) -> str:
        if notification.deduplication_key:
            return notification.deduplication_key
        
        tag_parts = sorted(self.match_tags.items())
        tag_str = ",".join(f"{k}={v}" for k, v in tag_parts)
        prio_str = self.match_priority.value if self.match_priority else "all"
        return f"{prio_str}|{tag_str}"
    
    def should_suppress(self, notification: Notification) -> bool:
        if self.match_priority and notification.priority != self.match_priority:
            return False
        
        for k, v in self.match_tags.items():
            if notification.tags.get(k) != v:
                return False
        
        key = self._get_key(notification)
        now = time.time()
        
        if key in self._suppressed_keys:
            suppress_until = self._suppressed_keys[key]
            if now < suppress_until:
                return True
        
        self._suppressed_keys[key] = now + self.duration_seconds
        return False
    
    def clear_expired(self):
        now = time.time()
        expired_keys = [
            k for k, v in self._suppressed_keys.items()
            if v <= now
        ]
        for k in expired_keys:
            del self._suppressed_keys[k]


class PriorityQueue:
    PRIORITY_ORDER = {
        NotificationPriority.CRITICAL: 0,
        NotificationPriority.HIGH: 1,
        NotificationPriority.MEDIUM: 2,
        NotificationPriority.LOW: 3
    }
    
    def __init__(self):
        self._queues: Dict[int, asyncio.Queue] = {
            0: asyncio.Queue(),
            1: asyncio.Queue(),
            2: asyncio.Queue(),
            3: asyncio.Queue()
        }
    
    def put(self, notification: Notification):
        priority_level = self.PRIORITY_ORDER[notification.priority]
        self._queues[priority_level].put_nowait(notification)
    
    async def get(self) -> Notification:
        while True:
            for level in sorted(self._queues.keys()):
                queue = self._queues[level]
                if not queue.empty():
                    return await queue.get()
            await asyncio.sleep(0.1)
    
    def qsize(self) -> int:
        return sum(q.qsize() for q in self._queues.values())


class NotificationService:
    def __init__(self):
        self._senders: Dict[NotificationChannel, NotificationSender] = {}
        self._throttling_rules: List[ThrottlingRule] = []
        self._suppression_rules: List[SuppressionRule] = []
        self._queue = PriorityQueue()
        self._running = False
        self._worker_task: Optional[asyncio.Task] = None
        self._logger = get_logger("notification_service")
        self._sent_notifications: List[Notification] = []
        self._failed_notifications: List[Notification] = []
        self._suppressed_notifications: List[Notification] = []
        
        self._register_default_senders()
    
    def _register_default_senders(self):
        self._senders[NotificationChannel.EMAIL] = EmailSender()
        self._senders[NotificationChannel.WEBHOOK] = ConsoleSender()
        self._senders[NotificationChannel.SLACK] = ConsoleSender()
        self._senders[NotificationChannel.SMS] = ConsoleSender()
        self._senders[NotificationChannel.TELEGRAM] = ConsoleSender()
        self._senders[NotificationChannel.PAGERDUTY] = ConsoleSender()
    
    def register_sender(self, channel: NotificationChannel, sender: NotificationSender):
        self._senders[channel] = sender
        self._logger.info("Registered sender", channel=channel.value)
    
    def add_throttling_rule(self, rule: ThrottlingRule):
        self._throttling_rules.append(rule)
    
    def add_suppression_rule(self, rule: SuppressionRule):
        self._suppression_rules.append(rule)
    
    def _check_throttling(self, notification: Notification) -> bool:
        for rule in self._throttling_rules:
            if rule.should_throttle(notification):
                self._logger.debug(
                    "Notification throttled",
                    notification_id=notification.notification_id
                )
                return True
        return False
    
    def _check_suppression(self, notification: Notification) -> bool:
        for rule in self._suppression_rules:
            if rule.should_suppress(notification):
                self._logger.debug(
                    "Notification suppressed",
                    notification_id=notification.notification_id
                )
                notification.status = NotificationStatus.SUPPRESSED
                self._suppressed_notifications.append(notification)
                return True
        return False
    
    def _clear_expired_suppressions(self):
        for rule in self._suppression_rules:
            rule.clear_expired()
    
    async def enqueue(self, notification: Notification) -> bool:
        if self._check_suppression(notification):
            return False
        
        if self._check_throttling(notification):
            notification.status = NotificationStatus.THROTTLED
            return False
        
        notification.status = NotificationStatus.QUEUED
        self._queue.put(notification)
        self._logger.info(
            "Notification queued",
            notification_id=notification.notification_id,
            priority=notification.priority.value
        )
        return True
    
    async def _send_to_channels(self, notification: Notification) -> bool:
        success = True
        for channel in notification.channels:
            sender = self._senders.get(channel)
            if sender:
                try:
                    sent = await sender.send(notification)
                    if not sent:
                        success = False
                except Exception as e:
                    success = False
                    self._logger.error(
                        "Sender failed",
                        channel=channel.value,
                        error=str(e)
                    )
            else:
                self._logger.warning(
                    "No sender registered for channel",
                    channel=channel.value
                )
        
        return success
    
    async def _process_queue(self):
        while self._running:
            try:
                notification = await self._queue.get()
                self._clear_expired_suppressions()
                
                if self._check_suppression(notification):
                    continue
                
                success = await self._send_to_channels(notification)
                
                if success:
                    notification.status = NotificationStatus.SENT
                    notification.sent_at = datetime.utcnow()
                    self._sent_notifications.append(notification)
                    self._logger.info(
                        "Notification sent successfully",
                        notification_id=notification.notification_id
                    )
                else:
                    notification.status = NotificationStatus.FAILED
                    notification.error = "One or more senders failed"
                    self._failed_notifications.append(notification)
                    self._logger.error(
                        "Notification failed",
                        notification_id=notification.notification_id
                    )
                    
            except asyncio.CancelledError:
                break
            except Exception as e:
                self._logger.exception("Notification processing error", error=str(e))
    
    def start(self):
        if self._running:
            return
        self._running = True
        self._worker_task = asyncio.create_task(self._process_queue())
        self._logger.info("Notification service started")
    
    def stop(self):
        if not self._running:
            return
        self._running = False
        if self._worker_task:
            self._worker_task.cancel()
        self._logger.info("Notification service stopped")
    
    def get_stats(self) -> Dict[str, Any]:
        return {
            "running": self._running,
            "queue_size": self._queue.qsize(),
            "sent_count": len(self._sent_notifications),
            "failed_count": len(self._failed_notifications),
            "suppressed_count": len(self._suppressed_notifications),
            "channels": [c.value for c in self._senders.keys()]
        }
    
    def get_sent_notifications(self, limit: int = 100) -> List[Notification]:
        return self._sent_notifications[-limit:]
    
    def get_failed_notifications(self, limit: int = 100) -> List[Notification]:
        return self._failed_notifications[-limit:]
    
    def clear_suppressions(self):
        for rule in self._suppression_rules:
            rule._suppressed_keys.clear()
        self._logger.info("All suppression rules cleared")


class AlertNotifier:
    def __init__(self, notification_service: NotificationService):
        self._service = notification_service
        self._logger = get_logger("alert_notifier")
        self._severity_to_priority: Dict[AlertSeverity, NotificationPriority] = {
            AlertSeverity.CRITICAL: NotificationPriority.CRITICAL,
            AlertSeverity.WARNING: NotificationPriority.HIGH,
            AlertSeverity.INFO: NotificationPriority.MEDIUM
        }
    
    def _convert_alert_to_notification(self, alert) -> Notification:
        priority = self._severity_to_priority.get(
            alert.severity,
            NotificationPriority.MEDIUM
        )
        
        status_text = "ALERT" if alert.status == "firing" else "RESOLVED"
        title = f"[{status_text}] {alert.rule_name}"
        content = (
            f"Rule: {alert.rule_name}\n"
            f"Metric: {alert.metric_name}\n"
            f"Value: {alert.value}\n"
            f"Threshold: {alert.threshold}\n"
            f"Severity: {alert.severity.value}\n"
            f"Description: {alert.description}\n"
            f"Timestamp: {alert.timestamp.isoformat()}"
        )
        
        return Notification(
            notification_id=alert.alert_id,
            title=title,
            content=content,
            priority=priority,
            channels=[NotificationChannel.WEBHOOK, NotificationChannel.EMAIL],
            recipients=["admin@example.com"],
            tags=alert.tags,
            deduplication_key=alert.rule_id
        )
    
    async def notify(self, alert) -> bool:
        notification = self._convert_alert_to_notification(alert)
        return await self._service.enqueue(notification)
