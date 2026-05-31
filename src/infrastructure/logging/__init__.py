"""
日志基础设施实现
实现 LoggerProtocol 协议，提供结构化日志输出
"""

from .structured_logger import (
    StructuredLogger,
    ConsoleHandler,
    FileHandler,
    JsonFormatter,
    TextFormatter,
)

__all__ = [
    "StructuredLogger",
    "ConsoleHandler",
    "FileHandler",
    "JsonFormatter",
    "TextFormatter",
]
