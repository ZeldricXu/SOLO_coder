"""
协议定义 - 使用 typing.Protocol 定义接口
所有低层实现必须实现这些协议，高层模块只依赖这些协议
"""

from __future__ import annotations

from abc import abstractmethod
from typing import (
    Any,
    AsyncIterator,
    Dict,
    Iterator,
    List,
    Optional,
    Protocol,
    runtime_checkable,
)
from uuid import UUID


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
    """日志协议 - 高层模块只依赖此接口"""

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


@runtime_checkable
class StorageProtocol(Protocol):
    """存储协议 - 抽象对象存储操作"""

    @abstractmethod
    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str: ...

    @abstractmethod
    async def download(self, bucket: str, key: str) -> bytes: ...

    @abstractmethod
    async def delete(self, bucket: str, key: str) -> None: ...

    @abstractmethod
    async def exists(self, bucket: str, key: str) -> bool: ...

    @abstractmethod
    async def list(
        self, bucket: str, prefix: Optional[str] = None
    ) -> List[Dict[str, Any]]: ...

    @abstractmethod
    async def get_metadata(self, bucket: str, key: str) -> Dict[str, Any]: ...


@runtime_checkable
class NotificationProtocol(Protocol):
    """通知协议 - 抽象通知发送操作"""

    @abstractmethod
    async def send(
        self,
        recipient: str,
        title: str,
        content: str,
        priority: str = "normal",
        **kwargs: Any,
    ) -> bool: ...

    @abstractmethod
    def supports(self, channel: str) -> bool: ...


@runtime_checkable
class TemplateEngineProtocol(Protocol):
    """模板引擎协议 - 抽象模板渲染
    此协议使项目脚手架模块可独立于具体模板引擎进行测试
    """

    @abstractmethod
    def render_string(self, template: str, context: Dict[str, Any]) -> str: ...

    @abstractmethod
    def render_file(
        self, template_path: str, output_path: str, context: Dict[str, Any]
    ) -> None: ...

    @abstractmethod
    def list_templates(self, template_dir: str) -> List[str]: ...

    @abstractmethod
    def validate_template(self, template: str) -> tuple[bool, Optional[str]]: ...


@runtime_checkable
class FileSystemProtocol(Protocol):
    """文件系统协议 - 抽象文件操作
    使项目脚手架模块可进行单元测试，不依赖真实文件系统
    """

    @abstractmethod
    def write_file(self, path: str, content: str) -> None: ...

    @abstractmethod
    def read_file(self, path: str) -> str: ...

    @abstractmethod
    def exists(self, path: str) -> bool: ...

    @abstractmethod
    def create_dir(self, path: str) -> None: ...

    @abstractmethod
    def list_dir(self, path: str) -> List[str]: ...


@runtime_checkable
class CodeAnalyzerProtocol(Protocol):
    """代码分析协议 - 抽象静态代码分析"""

    @abstractmethod
    def analyze(self, file_path: str, rules: List[str]) -> List[Dict[str, Any]]: ...

    @abstractmethod
    def supports_language(self, language: str) -> bool: ...

    @abstractmethod
    def get_available_rules(self) -> List[Dict[str, Any]]: ...
