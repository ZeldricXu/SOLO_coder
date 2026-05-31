"""
异常基类
"""

from __future__ import annotations

from typing import Any, Dict, Optional


class BaseError(Exception):
    def __init__(
        self,
        message: str,
        code: str = "internal_error",
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.code = code
        self.details = details or {}

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "details": self.details,
        }
