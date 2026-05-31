from typing import Any, Dict, Optional
from fastapi import status
import uuid
import traceback


class PlatformException(Exception):
    def __init__(
        self,
        message: str,
        code: int = status.HTTP_500_INTERNAL_SERVER_ERROR,
        details: Optional[Dict[str, Any]] = None,
        error_code: Optional[str] = None,
    ):
        self.message = message
        self.code = code
        self.details = details or {}
        self.error_id = str(uuid.uuid4())
        self.error_code = error_code or self._generate_error_code()
        self.timestamp = self._get_timestamp()

        if "error_id" not in self.details:
            self.details["error_id"] = self.error_id
        if "error_code" not in self.details:
            self.details["error_code"] = self.error_code
        if "timestamp" not in self.details:
            self.details["timestamp"] = self.timestamp

        super().__init__(message)

    def _generate_error_code(self) -> str:
        class_name = self.__class__.__name__
        return f"ERR_{class_name.upper().replace('ERROR', '')}"

    def _get_timestamp(self) -> str:
        from datetime import datetime, timezone
        return datetime.now(timezone.utc).isoformat()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "error": {
                "message": self.message,
                "code": self.code,
                "error_id": self.error_id,
                "error_code": self.error_code,
                "timestamp": self.timestamp,
                "details": self.details,
            }
        }

    def __str__(self) -> str:
        return f"[{self.error_code}] {self.message} (error_id={self.error_id})"


class ValidationError(PlatformException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            details,
            error_code="ERR_VALIDATION_FAILED",
        )


class NotFoundError(PlatformException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_404_NOT_FOUND,
            details,
            error_code="ERR_RESOURCE_NOT_FOUND",
        )


class AuthenticationError(PlatformException):
    def __init__(self, message: str = "Authentication failed", details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_401_UNAUTHORIZED,
            details,
            error_code="ERR_AUTHENTICATION_FAILED",
        )


class AuthorizationError(PlatformException):
    def __init__(self, message: str = "Permission denied", details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_403_FORBIDDEN,
            details,
            error_code="ERR_AUTHORIZATION_FAILED",
        )


class ConflictError(PlatformException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_409_CONFLICT,
            details,
            error_code="ERR_RESOURCE_CONFLICT",
        )


class RateLimitError(PlatformException):
    def __init__(self, message: str = "Rate limit exceeded", details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_429_TOO_MANY_REQUESTS,
            details,
            error_code="ERR_RATE_LIMIT_EXCEEDED",
        )


class ResourceExhaustedError(PlatformException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_503_SERVICE_UNAVAILABLE,
            details,
            error_code="ERR_RESOURCE_EXHAUSTED",
        )


class TransactionFailedError(PlatformException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(
            message,
            status.HTTP_500_INTERNAL_SERVER_ERROR,
            details,
            error_code="ERR_TRANSACTION_FAILED",
        )

    @classmethod
    def from_exception(
        cls,
        exc: Exception,
        operation: str,
        context: Optional[Dict[str, Any]] = None,
    ) -> "TransactionFailedError":
        details = {
            "operation": operation,
            "original_error": str(exc),
            "original_error_type": type(exc).__name__,
            "traceback": traceback.format_exc(),
        }
        if context:
            details.update(context)
        return cls(f"Transaction failed during {operation}: {str(exc)}", details=details)
