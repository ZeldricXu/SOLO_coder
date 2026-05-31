"""
模板引擎基础设施实现
实现 TemplateEngineProtocol 和 FileSystemProtocol 协议
"""

from .template_impl import (
    Jinja2TemplateEngine,
    FileSystemAdapter,
    InMemoryFileSystem,
)

__all__ = [
    "Jinja2TemplateEngine",
    "FileSystemAdapter",
    "InMemoryFileSystem",
]
