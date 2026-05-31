"""
代码质量门禁契约
静态分析、并发隔离级别
"""

from __future__ import annotations

from abc import abstractmethod
from enum import Enum
from typing import Any, Dict, List, Protocol, runtime_checkable


class IsolationLevel(str, Enum):
    NONE = "none"
    FILE = "file"
    MODULE = "module"
    PROJECT = "project"


@runtime_checkable
class CodeAnalyzerProtocol(Protocol):
    @abstractmethod
    def analyze(self, file_path: str, rules: List[str]) -> List[Dict[str, Any]]: ...

    @abstractmethod
    def supports_language(self, language: str) -> bool: ...

    @abstractmethod
    def get_available_rules(self) -> List[Dict[str, Any]]: ...
