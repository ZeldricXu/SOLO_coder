"""
通知基础设施实现
"""

from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set

from src.domain.contracts.notification import NotificationProtocol
from src.domain.errors.notification import NotificationError


class NotificationSuppressionStrategy(ABC):
    @abstractmethod
    def should_suppress(self, recipient: str, title: str, content: str, priority: str) -> bool: ...

    def record_sent(self, recipient: str, title: str, content: str, priority: str) -> None: ...


class RateLimitSuppression(NotificationSuppressionStrategy):
    def __init__(self, max_messages: int = 10, window_seconds: int = 60) -> None:
        self.max_messages = max_messages
        self.window_seconds = window_seconds
        self._timestamps: Dict[str, List[float]] = defaultdict(list)

    def should_suppress(self, recipient: str, title: str, content: str, priority: str) -> bool:
        now = time.time()
        ts = self._timestamps[recipient]
        ts = [t for t in ts if now - t < self.window_seconds]
        self._timestamps[recipient] = ts
        return len(ts) >= self.max_messages

    def record_sent(self, recipient: str, title: str, content: str, priority: str) -> None:
        self._timestamps[recipient].append(time.time())


class DeduplicationSuppression(NotificationSuppressionStrategy):
    def __init__(self, ttl_seconds: int = 300) -> None:
        self.ttl_seconds = ttl_seconds
        self._seen: Dict[str, float] = {}

    def _get_key(self, recipient: str, title: str, content: str) -> str:
        return f"{recipient}:{title}:{hash(content)}"

    def should_suppress(self, recipient: str, title: str, content: str, priority: str) -> bool:
        key = self._get_key(recipient, title, content)
        now = time.time()
        return key in self._seen and now - self._seen[key] < self.ttl_seconds

    def record_sent(self, recipient: str, title: str, content: str, priority: str) -> None:
        self._seen[self._get_key(recipient, title, content)] = time.time()


class TimeWindowSuppression(NotificationSuppressionStrategy):
    def __init__(self, quiet_hours: Optional[List[tuple]] = None, allowed_priorities: Optional[Set[str]] = None) -> None:
        self.quiet_hours = quiet_hours or [(22, 6)]
        self.allowed_priorities = allowed_priorities or {"urgent"}

    def should_suppress(self, recipient: str, title: str, content: str, priority: str) -> bool:
        if priority in self.allowed_priorities:
            return False
        now_hour = time.localtime().tm_hour
        for start, end in self.quiet_hours:
            if start < end:
                if start <= now_hour < end:
                    return True
            else:
                if now_hour >= start or now_hour < end:
                    return True
        return False


class ConsoleNotification(NotificationProtocol):
    def __init__(self) -> None:
        self.sent_messages: List[Dict[str, Any]] = []

    async def send(self, recipient: str, title: str, content: str, priority: str = "normal", **kwargs: Any) -> bool:
        self.sent_messages.append({"recipient": recipient, "title": title, "content": content, "priority": priority, "timestamp": time.time(), **kwargs})
        print(f"[NOTIFICATION {priority.upper()}] To: {recipient} | Title: {title}")
        return True

    def supports(self, channel: str) -> bool:
        return channel.lower() == "console"


class EmailNotification(NotificationProtocol):
    def __init__(self, smtp_host: str = "", smtp_port: int = 25) -> None:
        self.smtp_host = smtp_host
        self.smtp_port = smtp_port

    async def send(self, recipient: str, title: str, content: str, priority: str = "normal", **kwargs: Any) -> bool:
        if not self.smtp_host:
            print(f"[EMAIL SIMULATION] To: {recipient} | Subject: {title}")
            return True
        try:
            import smtplib
            from email.mime.text import MIMEText
            msg = MIMEText(content, "plain", "utf-8")
            msg["Subject"] = title
            msg["To"] = recipient
            with smtplib.SMTP(self.smtp_host, self.smtp_port) as server:
                server.send_message(msg)
            return True
        except Exception as e:
            raise NotificationError(f"Failed to send email: {e}", channel="email") from e

    def supports(self, channel: str) -> bool:
        return channel.lower() == "email"


class NotificationManager:
    def __init__(
        self,
        channels: Optional[List[NotificationProtocol]] = None,
        suppression_strategies: Optional[List[NotificationSuppressionStrategy]] = None,
    ) -> None:
        self._channels: Dict[str, NotificationProtocol] = {}
        self._suppression_strategies = suppression_strategies or []
        for channel in channels or []:
            self.add_channel(channel)

    def add_channel(self, channel: NotificationProtocol) -> None:
        for ch_name in ["console", "email", "slack"]:
            if channel.supports(ch_name):
                self._channels[ch_name] = channel

    def add_suppression_strategy(self, strategy: NotificationSuppressionStrategy) -> None:
        self._suppression_strategies.append(strategy)

    async def send(
        self, recipient: str, title: str, content: str, priority: str = "normal", channels: Optional[List[str]] = None
    ) -> Dict[str, bool]:
        for strategy in self._suppression_strategies:
            if strategy.should_suppress(recipient, title, content, priority):
                return {"suppressed": True}

        target_channels = channels or self._get_channels_for_priority(priority)
        results: Dict[str, bool] = {}
        for ch_name in target_channels:
            channel = self._channels.get(ch_name)
            if channel:
                try:
                    results[ch_name] = await channel.send(recipient, title, content, priority)
                except NotificationError:
                    results[ch_name] = False

        if any(results.values()):
            for strategy in self._suppression_strategies:
                strategy.record_sent(recipient, title, content, priority)
        return results

    def _get_priority_order(self, priority: str) -> int:
        return {"low": 0, "normal": 1, "high": 2, "urgent": 3}.get(priority, 1)

    def _get_channels_for_priority(self, priority: str) -> List[str]:
        p = self._get_priority_order(priority)
        if p >= 3:
            return ["email", "slack", "console"]
        if p >= 2:
            return ["slack", "email"]
        if p >= 1:
            return ["email"]
        return ["console"]
