"""
核心抽象层 - 定义所有模块的公共接口和协议
应用依赖倒置原则：高层模块依赖于此抽象层，而非具体实现
"""

from .protocols import (
    LoggerProtocol,
    StorageProtocol,
    NotificationProtocol,
    TemplateEngineProtocol,
    FileSystemProtocol,
    CodeAnalyzerProtocol,
    Request,
    Response,
    TraceContext,
)
from .models import (
    LogLevel,
    LogEntry,
    NotificationPriority,
    ServiceMetadata,
    DocumentMetadata,
    QualityReport,
    QualityIssue,
    ScaffoldConfig,
    TraceSpan,
)
from .exceptions import (
    BaseError,
    ConfigurationError,
    StorageError,
    NotificationError,
    TemplateError,
    QualityCheckError,
    ScaffoldError,
    GatewayError,
)

__all__ = [
    "LoggerProtocol",
    "StorageProtocol",
    "NotificationProtocol",
    "TemplateEngineProtocol",
    "FileSystemProtocol",
    "CodeAnalyzerProtocol",
    "Request",
    "Response",
    "TraceContext",
    "LogLevel",
    "LogEntry",
    "NotificationPriority",
    "ServiceMetadata",
    "DocumentMetadata",
    "QualityReport",
    "QualityIssue",
    "ScaffoldConfig",
    "TraceSpan",
    "BaseError",
    "ConfigurationError",
    "StorageError",
    "NotificationError",
    "TemplateError",
    "QualityCheckError",
    "ScaffoldError",
    "GatewayError",
]
