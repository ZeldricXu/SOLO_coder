from .base import BaseError


class LoggingError(BaseError):
    def __init__(self, message: str, details=None):
        super().__init__(message, code="logging_error", details=details)
