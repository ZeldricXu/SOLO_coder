"""Base error classes and error codes."""
from __future__ import annotations

from enum import Enum
from typing import Any, Dict, Optional


class ErrorCode(str, Enum):
    VALIDATION_ERROR = "VALIDATION_ERROR"
    MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD"
    INVALID_PARAMETER = "INVALID_PARAMETER"
    FILE_NOT_FOUND = "FILE_NOT_FOUND"
    FILE_ALREADY_EXISTS = "FILE_ALREADY_EXISTS"
    CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    STORAGE_CAPACITY_EXCEEDED = "STORAGE_CAPACITY_EXCEEDED"
    INVALID_STORAGE_TIER = "INVALID_STORAGE_TIER"
    CONFIGURATION_ERROR = "CONFIGURATION_ERROR"
    TIMEOUT_ERROR = "TIMEOUT_ERROR"
    CONCURRENCY_ERROR = "CONCURRENCY_ERROR"
    RESOURCE_BUSY = "RESOURCE_BUSY"
    AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
    AUTHORIZATION_FAILED = "AUTHORIZATION_FAILED"
    RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    OPERATION_NOT_SUPPORTED = "OPERATION_NOT_SUPPORTED"
    SCHEMA_VERSION_MISMATCH = "SCHEMA_VERSION_MISMATCH"
    MIGRATION_FAILED = "MIGRATION_FAILED"
    QUALITY_CHECK_FAILED = "QUALITY_CHECK_FAILED"
    QUERY_PARSE_ERROR = "QUERY_PARSE_ERROR"
    TASK_DEPENDENCY_FAILED = "TASK_DEPENDENCY_FAILED"


class BaseError(Exception):
    def __init__(
        self,
        message: str,
        code: ErrorCode = ErrorCode.INTERNAL_ERROR,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.details = details or {}
        self.suggestion = suggestion
        self.message = message

    def to_dict(self) -> Dict[str, Any]:
        return {
            "error": {
                "code": self.code.value,
                "message": self.message,
                "details": self.details,
                "suggestion": self.suggestion,
            }
        }

    def __str__(self) -> str:
        parts = [f"[{self.code.value}] {self.message}"]
        if self.suggestion:
            parts.append(f"Suggestion: {self.suggestion}")
        return " | ".join(parts)
