"""
通知基础设施实现
实现 NotificationProtocol 协议，支持多通道通知
"""

from .notification_impl import (
    ConsoleNotification,
    EmailNotification,
    SlackNotification,
    NotificationManager,
    NotificationSuppressionStrategy,
    RateLimitSuppression,
    DeduplicationSuppression,
    TimeWindowSuppression,
)

__all__ = [
    "ConsoleNotification",
    "EmailNotification",
    "SlackNotification",
    "NotificationManager",
    "NotificationSuppressionStrategy",
    "RateLimitSuppression",
    "DeduplicationSuppression",
    "TimeWindowSuppression",
]
