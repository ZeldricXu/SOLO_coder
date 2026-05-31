"""
基础设施层 - 包含所有协议的具体实现
高层模块通过抽象协议（core/protocols.py）依赖这些实现
"""

from .logging import StructuredLogger, ConsoleHandler, FileHandler, JsonFormatter
from .storage import MemoryStorage, LocalFileStorage, S3Storage
from .notification import (
    ConsoleNotification,
    EmailNotification,
    SlackNotification,
    NotificationManager,
)
from .template import Jinja2TemplateEngine, FileSystemAdapter

__all__ = [
    "StructuredLogger",
    "ConsoleHandler",
    "FileHandler",
    "JsonFormatter",
    "MemoryStorage",
    "LocalFileStorage",
    "S3Storage",
    "ConsoleNotification",
    "EmailNotification",
    "SlackNotification",
    "NotificationManager",
    "Jinja2TemplateEngine",
    "FileSystemAdapter",
]
