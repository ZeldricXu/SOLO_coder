"""
链路追踪与日志契约
网关模块的核心依赖 - 请求/响应/日志/追踪上下文
"""

from __future__ import annotations

from abc import abstractmethod
from typing import Any, Dict, Optional, Protocol, runtime_checkable


@runtime_checkable
class Request(Protocol):
    request_id: str
    method: str
    path: str
    headers: Dict[str, str]
    body: Optional[bytes]
    query_params: Dict[str, str]


@runtime_checkable
class Response(Protocol):
    status_code: int
    headers: Dict[str, str]
    body: Optional[bytes]


@runtime_checkable
class TraceContext(Protocol):
    trace_id: str
    span_id: str
    parent_span_id: Optional[str]
    service_name: str
    tags: Dict[str, Any]


@runtime_checkable
class LoggerProtocol(Protocol):
    @abstractmethod
    def debug(self, message: str, **kwargs: Any) -> None: ...

    @abstractmethod
    def info(self, message: str, **kwargs: Any) -> None: ...

    @abstractmethod
    def warning(self, message: str, **kwargs: Any) -> None: ...

    @abstractmethod
    def error(self, message: str, **kwargs: Any) -> None: ...

    @abstractmethod
    def critical(self, message: str, **kwargs: Any) -> None: ...

    @abstractmethod
    def with_trace(self, trace_ctx: TraceContext) -> "LoggerProtocol": ...

    @abstractmethod
    def with_context(self, **kwargs: Any) -> "LoggerProtocol": ...
