from typing import Any, Dict, Optional
from fastapi import HTTPException, status


class DevPortalException(Exception):
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


class ValidationError(DevPortalException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=422, details=details)


class NotFoundError(DevPortalException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=404, details=details)


class ConflictError(DevPortalException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=409, details=details)


class UnauthorizedError(DevPortalException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=401, details=details)


class ForbiddenError(DevPortalException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=403, details=details)


class TimeoutError(DevPortalException):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message, code=504, details=details)


def to_http_exception(exc: DevPortalException) -> HTTPException:
    return HTTPException(
        status_code=exc.code,
        detail={"message": exc.message, "details": exc.details},
    )
