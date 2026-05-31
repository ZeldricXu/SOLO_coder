"""Custom error classes for the file storage system."""
from .base import BaseError, ErrorCode
from .storage import (
    StorageError,
    FileNotFoundError,
    FileAlreadyExistsError,
    StorageCapacityExceededError,
    ChecksumMismatchError,
    InvalidStorageTierError,
)
from .common import (
    ValidationError,
    ConfigurationError,
    TimeoutError,
    ConcurrencyError,
    ResourceBusyError,
)
from .gateway import (
    GatewayError,
    AuthenticationError,
    AuthorizationError,
    RateLimitExceededError,
    RequestValidationError,
)

__all__ = [
    "BaseError",
    "ErrorCode",
    "StorageError",
    "FileNotFoundError",
    "FileAlreadyExistsError",
    "StorageCapacityExceededError",
    "ChecksumMismatchError",
    "InvalidStorageTierError",
    "ValidationError",
    "ConfigurationError",
    "TimeoutError",
    "ConcurrencyError",
    "ResourceBusyError",
    "GatewayError",
    "AuthenticationError",
    "AuthorizationError",
    "RateLimitExceededError",
    "RequestValidationError",
]
