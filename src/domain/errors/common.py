from .base import BaseError


class ConfigurationError(BaseError):
    def __init__(self, message: str, details=None):
        super().__init__(message, code="configuration_error", details=details)
