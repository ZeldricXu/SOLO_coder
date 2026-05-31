"""
模板引擎与文件系统契约
脚手架模块的解耦关键 - 只依赖这两个协议
"""

from __future__ import annotations

from abc import abstractmethod
from typing import Any, Dict, List, Optional, Protocol, runtime_checkable


@runtime_checkable
class TemplateEngineProtocol(Protocol):
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
