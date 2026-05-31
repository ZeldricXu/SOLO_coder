"""
Structured logging module.
Provides structured JSON logging with dynamic configuration and hot updates.

Re-exports for backward compatibility.
"""

from app.logging.config import (
    LogFormatType,
    LogHandlerType,
    LogLevel,
    HandlerConfig,
    SceneConfig,
    LoggingConfig,
    create_default_config
)
from app.logging.formatters import StructuredFormatter
from app.logging.logger import ContextLogger
from app.logging.storage import LogStorage
from app.logging.registry import (
    ConfigChangedCallback,
    LoggingStrategyRegistry
)
from app.logging.manager import LoggingManager


def get_logger(name: str, trace_id=None):
    manager = LoggingManager()
    return manager.get_logger(name, trace_id)


__all__ = [
    "LogFormatType",
    "LogHandlerType",
    "LogLevel",
    "HandlerConfig",
    "SceneConfig",
    "LoggingConfig",
    "create_default_config",
    "StructuredFormatter",
    "ContextLogger",
    "LogStorage",
    "ConfigChangedCallback",
    "LoggingStrategyRegistry",
    "LoggingManager",
    "get_logger"
]
