"""
项目脚手架生成模块
依赖抽象协议：TemplateEngineProtocol, FileSystemProtocol
可独立测试，不依赖具体基础设施实现
"""

from .scaffold_module import (
    ProjectScaffold,
    TemplateRegistry,
    InteractivePrompter,
    TemplateInfo,
    ScaffoldResult,
)

__all__ = [
    "ProjectScaffold",
    "TemplateRegistry",
    "InteractivePrompter",
    "TemplateInfo",
    "ScaffoldResult",
]
