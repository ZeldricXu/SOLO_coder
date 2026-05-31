"""
通知实现
遵循 NotificationProtocol 协议，支持优先级和抑制策略
"""

from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set

from src.core import NotificationError, NotificationPriority, NotificationProtocol


class NotificationSuppressionStrategy(ABC):
    """通知抑制策略抽象基类"""

    @abstractmethod
    def should_suppress(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str,
    ) -> bool: ...

    def record_sent(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str,
    ) -> None: ...


class RateLimitSuppression(NotificationSuppressionStrategy):
    """速率限制抑制 - 限制每个接收者在时间窗口内的通知数量"""

    def __init__(self, max_messages: int = 10, window_seconds: int = 60) -> None:
        self.max_messages = max_messages
        self.window_seconds = window_seconds
        self._timestamps: Dict[str, List[float]] = defaultdict(list)

    def should_suppress(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str,
    ) -> bool:
        now = time.time()
        timestamps = self._timestamps[recipient]
        timestamps = [t for t in timestamps if now - t < self.window_seconds]
        self._timestamps[recipient] = timestamps
        return len(timestamps) >= self.max_messages

    def record_sent(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str,
    ) -> None:
        self._timestamps[recipient].append(time.time())


class DeduplicationSuppression(NotificationSuppressionStrategy):
    """去重抑制 - 防止重复发送相同内容"""

    def __init__(self, ttl_seconds: int = 300) -> None:
        self.ttl_seconds = ttl_seconds
        self._seen: Dict[str, float] = {}

    def _get_key(self, recipient: str, title: str, content: str) -> str:
        return f"{recipient}:{title}:{hash(content)}"

    def should_suppress(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str,
    ) -> bool:
        key = self._get_key(recipient, title, content)
        now = time.time()
        if key in self._seen and now - self._seen[key] < self.ttl_seconds:
            return True
        return False

    def record_sent(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str,
    ) -> None:
        key = self._get_key(recipient, title, content)
        self._seen[key] = time.time()


class TimeWindowSuppression(NotificationSuppressionStrategy):
    """时间窗口抑制 - 在特定时间段内抑制通知"""

    def __init__(
        self,
        quiet_hours: Optional[List[tuple[int, int]]] = None,
        allowed_priorities: Optional[Set[str]] = None,
    ) -> None:
        self.quiet_hours = quiet_hours or [(22, 6)]
        self.allowed_priorities = allowed_priorities or {"urgent"}

    def should_suppress(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str,
    ) -> bool:
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
    """控制台通知 - 主要用于测试"""

    def __init__(self) -> None:
        self.sent_messages: List[Dict[str, Any]] = []

    async def send(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str = "normal",
        **kwargs: Any,
    ) -> bool:
        message = {
            "recipient": recipient,
            "title": title,
            "content": content,
            "priority": priority,
            "timestamp": time.time(),
            **kwargs,
        }
        self.sent_messages.append(message)
        print(f"[NOTIFICATION {priority.upper()}] To: {recipient} | Title: {title}")
        print(f"  Content: {content[:100]}..." if len(content) > 100 else f"  Content: {content}")
        return True

    def supports(self, channel: str) -> bool:
        return channel.lower() == "console"


class EmailNotification(NotificationProtocol):
    """邮件通知"""

    def __init__(self, smtp_host: str = "", smtp_port: int = 25) -> None:
        self.smtp_host = smtp_host
        self.smtp_port = smtp_port

    async def send(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str = "normal",
        **kwargs: Any,
    ) -> bool:
        try:
            if not self.smtp_host:
                print(f"[EMAIL SIMULATION] To: {recipient} | Subject: {title}")
                return True
            import smtplib
            from email.mime.text import MIMEText

            msg = MIMEText(content, "plain", "utf-8")
            msg["Subject"] = title
            msg["To"] = recipient
            msg["X-Priority"] = str(
                1 if priority == "urgent" else 3 if priority == "high" else 5
            )

            with smtplib.SMTP(self.smtp_host, self.smtp_port) as server:
                server.send_message(msg)
            return True
        except Exception as e:
            raise NotificationError(
                f"Failed to send email: {e}", channel="email"
            ) from e

    def supports(self, channel: str) -> bool:
        return channel.lower() == "email"


class SlackNotification(NotificationProtocol):
    """Slack通知"""

    def __init__(self, webhook_url: str = "") -> None:
        self.webhook_url = webhook_url

    async def send(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str = "normal",
        **kwargs: Any,
    ) -> bool:
        try:
            color_map = {
                "urgent": "#ff0000",
                "high": "#ff9900",
                "normal": "#36a64f",
                "low": "#cccccc",
            }
            color = color_map.get(priority, "#36a64f")

            payload = {
                "channel": recipient,
                "attachments": [
                    {
                        "title": title,
                        "text": content,
                        "color": color,
                        "fields": [{"title": "Priority", "value": priority.upper(), "short": True}],
                    }
                ],
            }

            if not self.webhook_url:
                print(f"[SLACK SIMULATION] Channel: {recipient} | Title: {title}")
                return True

            import aiohttp
            async with aiohttp.ClientSession() as session:
                async with session.post(self.webhook_url, json=payload) as resp:
                    return resp.status == 200
        except Exception as e:
            raise NotificationError(
                f"Failed to send Slack notification: {e}", channel="slack"
            ) from e

    def supports(self, channel: str) -> bool:
        return channel.lower() == "slack"


class NotificationManager:
    """
    通知管理器
    支持多通道、优先级、抑制策略的综合管理
    """

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

    def _get_priority_order(self, priority: str) -> int:
        order = {"low": 0, "normal": 1, "high": 2, "urgent": 3}
        return order.get(priority, 1)

    def _get_channels_for_priority(self, priority: str) -> List[str]:
        p_order = self._get_priority_order(priority)
        if p_order >= 3:
            return ["email", "slack", "console"]
        elif p_order >= 2:
            return ["slack", "email"]
        elif p_order >= 1:
            return ["email"]
        else:
            return ["console"]

    async def send(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str = "normal",
        channels: Optional[List[str]] = None,
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
                    results[ch_name] = await channel.send(
                        recipient, title, content, priority
                    )
                except NotificationError:
                    results[ch_name] = False

        if any(results.values()):
            for strategy in self._suppression_strategies:
                strategy.record_sent(recipient, title, content, priority)

        return results
