from typing import Any, Dict, Optional
from fastapi import HTTPException, status


class PlatformException(Exception):
    def __init__(
        self,
        message: str,
        code: int = 500,
        details: Optional[Dict[str, Any]] = None,
    ):
        self.message = message
        self.code = code
        self.details = details or {}
        super().__init__(message)


class ValidationError(PlatformException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=400, details=details)


class NotFoundError(PlatformException):
    def __init__(self, resource_type: str, resource_id: str):
        super().__init__(
            f"{resource_type} not found: {resource_id}",
            code=404,
            details={"resource_type": resource_type, "resource_id": resource_id},
        )


class ConflictError(PlatformException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=409, details=details)


class UnauthorizedError(PlatformException):
    def __init__(self, message: str = "Unauthorized"):
        super().__init__(message, code=401)


class ForbiddenError(PlatformException):
    def __init__(self, message: str = "Forbidden"):
        super().__init__(message, code=403)


class TimeoutError(PlatformException):
    def __init__(self, message: str = "Operation timed out"):
        super().__init__(message, code=504)


class BusinessError(PlatformException):
    def __init__(
        self,
        message: str,
        code: int = 400,
        details: Optional[Dict[str, Any]] = None,
    ):
        super().__init__(message, code=code, details=details)


def to_http_exception(exc: PlatformException) -> HTTPException:
    return HTTPException(
        status_code=exc.code,
        detail={
            "message": exc.message,
            "code": exc.code,
            "details": exc.details,
        },
    )
