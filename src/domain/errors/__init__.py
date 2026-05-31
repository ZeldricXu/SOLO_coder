"""
领域异常 - 按领域拆分
"""

from .base import BaseError
from .tracing import LoggingError
from .gateway import GatewayError, ConsistencyError
from .quality import QualityCheckError, ConcurrencyError
from .storage import StorageError
from .notification import NotificationError
from .template import TemplateError, ScaffoldError
from .common import ConfigurationError

__all__ = [
    "BaseError",
    "LoggingError",
    "GatewayError",
    "ConsistencyError",
    "QualityCheckError",
    "ConcurrencyError",
    "StorageError",
    "NotificationError",
    "TemplateError",
    "ScaffoldError",
    "ConfigurationError",
]
