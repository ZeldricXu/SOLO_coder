"""
领域契约 - 按领域拆分的接口协议
每个子模块只依赖自己领域的契约，不产生跨领域耦合
"""

from .tracing import LoggerProtocol, TraceContext, Request, Response
from .gateway import GatewayMiddleware, HandlerFunc, ConsistencyPolicy
from .quality import CodeAnalyzerProtocol, IsolationLevel
from .notification import NotificationProtocol
from .storage import StorageProtocol
from .template import TemplateEngineProtocol, FileSystemProtocol

__all__ = [
    "LoggerProtocol",
    "TraceContext",
    "Request",
    "Response",
    "GatewayMiddleware",
    "HandlerFunc",
    "ConsistencyPolicy",
    "CodeAnalyzerProtocol",
    "IsolationLevel",
    "NotificationProtocol",
    "StorageProtocol",
    "TemplateEngineProtocol",
    "FileSystemProtocol",
]
