"""
领域模型 - 按领域分包
"""

from .tracing import LogLevel, LogEntry, TraceSpan
from .gateway import ConsistencyCheckResult
from .quality import QualityIssue, QualityReport, QualityRule, ConcurrencyIssue
from .common import NotificationPriority, ServiceMetadata, DocumentMetadata, ScaffoldConfig

__all__ = [
    "LogLevel",
    "LogEntry",
    "TraceSpan",
    "ConsistencyCheckResult",
    "QualityIssue",
    "QualityReport",
    "QualityRule",
    "ConcurrencyIssue",
    "NotificationPriority",
    "ServiceMetadata",
    "DocumentMetadata",
    "ScaffoldConfig",
]
