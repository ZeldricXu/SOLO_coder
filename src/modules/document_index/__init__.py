"""
内部文档索引模块
多源技术文档聚合、全文搜索与权限过滤
"""

from .document_module import (
    DocumentIndex,
    DocumentCrawler,
    SearchEngine,
    PermissionFilter,
)

__all__ = [
    "DocumentIndex",
    "DocumentCrawler",
    "SearchEngine",
    "PermissionFilter",
]
