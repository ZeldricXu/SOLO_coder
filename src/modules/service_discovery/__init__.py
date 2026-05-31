"""
软件目录与发现模块
服务/库的元数据注册、检索与依赖关系展示
"""

from .discovery_module import (
    ServiceRegistry,
    ServiceCatalog,
    DependencyAnalyzer,
)

__all__ = [
    "ServiceRegistry",
    "ServiceCatalog",
    "DependencyAnalyzer",
]
