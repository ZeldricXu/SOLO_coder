from __future__ import annotations

from typing import Any, Optional


class StreamSQLException(Exception):
    def __init__(self, message: str, details: Optional[dict[str, Any]] = None) -> None:
        super().__init__(message)
        self.message = message
        self.details = details or {}

    def __str__(self) -> str:
        if self.details:
            return f"{self.message}: {self.details}"
        return self.message


class ValidationError(StreamSQLException):
    def __init__(self, message: str, field: Optional[str] = None, value: Any = None) -> None:
        details = {}
        if field:
            details["field"] = field
        if value is not None:
            details["value"] = str(value)
        super().__init__(message, details)


class TimeoutError(StreamSQLException):
    def __init__(self, operation: str, timeout_seconds: float) -> None:
        super().__init__(
            f"Operation '{operation}' timed out after {timeout_seconds}s",
            {"operation": operation, "timeout_seconds": timeout_seconds},
        )


class ConfigurationError(StreamSQLException):
    def __init__(self, key: str, reason: str) -> None:
        super().__init__(
            f"Invalid configuration '{key}': {reason}",
            {"key": key, "reason": reason},
        )


class ResourceAcquisitionError(StreamSQLException):
    def __init__(self, resource_type: str, reason: str) -> None:
        super().__init__(
            f"Failed to acquire {resource_type}: {reason}",
            {"resource_type": resource_type, "reason": reason},
        )


class SchemaExtractionError(StreamSQLException):
    def __init__(self, source: str, reason: str) -> None:
        super().__init__(
            f"Failed to extract schema from {source}: {reason}",
            {"source": source, "reason": reason},
        )


class CDCCaptureError(StreamSQLException):
    def __init__(self, source: str, operation: str, reason: str) -> None:
        super().__init__(
            f"CDC capture failed for {source} during {operation}: {reason}",
            {"source": source, "operation": operation, "reason": reason},
        )


class SQLParseError(StreamSQLException):
    def __init__(self, sql: str, position: Optional[int] = None, message: str = "") -> None:
        details = {"sql": sql}
        if position is not None:
            details["position"] = position
        super().__init__(f"Failed to parse SQL: {message}", details)


class LineageExtractionError(StreamSQLException):
    def __init__(self, sql: str, reason: str) -> None:
        super().__init__(
            f"Failed to extract lineage from SQL: {reason}",
            {"sql": sql, "reason": reason},
        )


class QualityCheckError(StreamSQLException):
    def __init__(self, rule_id: str, table: str, reason: str) -> None:
        super().__init__(
            f"Quality check failed for rule {rule_id} on {table}: {reason}",
            {"rule_id": rule_id, "table": table, "reason": reason},
        )
