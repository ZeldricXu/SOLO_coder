"""Common error classes."""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from .base import BaseError, ErrorCode


class ValidationError(BaseError):
    def __init__(
        self,
        message: str,
        field: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        error_details = details or {}
        if field:
            error_details["field"] = field
        super().__init__(
            message=message,
            code=ErrorCode.VALIDATION_ERROR,
            details=error_details,
            suggestion=suggestion,
        )


class MissingRequiredFieldError(ValidationError):
    def __init__(self, field_name: str, suggestion: Optional[str] = None) -> None:
        super().__init__(
            message=f"Required field is missing: {field_name}",
            field=field_name,
            details={"required_field": field_name},
            suggestion=suggestion or f"Please provide a value for the '{field_name}' field.",
        )


class InvalidParameterError(ValidationError):
    def __init__(
        self,
        param_name: str,
        param_value: Any,
        valid_options: Optional[List[str]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        details = {"parameter_name": param_name, "provided_value": param_value}
        if valid_options:
            details["valid_options"] = valid_options
        message = f"Invalid value for parameter '{param_name}': {param_value}"
        if valid_options:
            message += f". Valid options are: {', '.join(valid_options)}"
        super().__init__(
            message=message,
            field=param_name,
            details=details,
            suggestion=suggestion or f"Please provide a valid value for '{param_name}'.",
        )


class ConfigurationError(BaseError):
    def __init__(
        self,
        message: str,
        config_key: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        error_details = details or {}
        if config_key:
            error_details["config_key"] = config_key
        super().__init__(
            message=message,
            code=ErrorCode.CONFIGURATION_ERROR,
            details=error_details,
            suggestion=suggestion or "Check your configuration settings.",
        )


class TimeoutError(BaseError):
    def __init__(
        self,
        operation: str,
        timeout_seconds: int,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(
            message=f"Operation '{operation}' timed out after {timeout_seconds} seconds",
            code=ErrorCode.TIMEOUT_ERROR,
            details={
                "operation": operation,
                "timeout_seconds": timeout_seconds,
                **(details or {}),
            },
            suggestion=suggestion or "Try increasing the timeout or check system resources.",
        )


class ConcurrencyError(BaseError):
    def __init__(
        self,
        message: str,
        resource: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        error_details = details or {}
        if resource:
            error_details["resource"] = resource
        super().__init__(
            message=message,
            code=ErrorCode.CONCURRENCY_ERROR,
            details=error_details,
            suggestion=suggestion or "Retry the operation or implement proper locking.",
        )


class ResourceBusyError(BaseError):
    def __init__(
        self,
        resource: str,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(
            message=f"Resource is busy: {resource}",
            code=ErrorCode.RESOURCE_BUSY,
            details={"resource": resource, **(details or {})},
            suggestion=suggestion or "Try again later when the resource is available.",
        )
