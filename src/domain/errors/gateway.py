"""API Gateway related error classes."""
from __future__ import annotations

from typing import Any, Dict, Optional

from .base import BaseError, ErrorCode


class GatewayError(BaseError):
    def __init__(
        self,
        message: str,
        code: ErrorCode = ErrorCode.INTERNAL_ERROR,
        details: Optional[Dict[str, Any]] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        super().__init__(message, code, details, suggestion)


class AuthenticationError(GatewayError):
    def __init__(
        self,
        message: str = "Authentication failed",
        auth_method: Optional[str] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        details = {}
        if auth_method:
            details["auth_method"] = auth_method
        super().__init__(
            message=message,
            code=ErrorCode.AUTHENTICATION_FAILED,
            details=details,
            suggestion=suggestion or "Please provide valid authentication credentials.",
        )


class AuthorizationError(GatewayError):
    def __init__(
        self,
        action: str,
        resource: Optional[str] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        details = {"action": action}
        if resource:
            details["resource"] = resource
        message = f"Permission denied for action '{action}'"
        if resource:
            message += f" on resource '{resource}'"
        super().__init__(
            message=message,
            code=ErrorCode.AUTHORIZATION_FAILED,
            details=details,
            suggestion=suggestion or "Check your permissions and try again.",
        )


class RateLimitExceededError(GatewayError):
    def __init__(
        self,
        limit: int,
        window_seconds: int,
        client_id: Optional[str] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        details = {
            "rate_limit": limit,
            "window_seconds": window_seconds,
        }
        if client_id:
            details["client_id"] = client_id
        super().__init__(
            message=f"Rate limit exceeded. Limit: {limit} requests per {window_seconds} seconds",
            code=ErrorCode.RATE_LIMIT_EXCEEDED,
            details=details,
            suggestion=suggestion or "Reduce your request frequency and try again later.",
        )


class RequestValidationError(GatewayError):
    def __init__(
        self,
        message: str,
        validation_errors: Optional[list] = None,
        suggestion: Optional[str] = None,
    ) -> None:
        details = {}
        if validation_errors:
            details["validation_errors"] = validation_errors
        super().__init__(
            message=message,
            code=ErrorCode.VALIDATION_ERROR,
            details=details,
            suggestion=suggestion or "Please check your request parameters and try again.",
        )
