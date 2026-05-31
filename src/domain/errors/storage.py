from .base import BaseError


class StorageError(BaseError):
    def __init__(self, message: str, bucket=None, key=None, details=None):
        _details = details or {}
        if bucket:
            _details["bucket"] = bucket
        if key:
            _details["key"] = key
        super().__init__(message, code="storage_error", details=_details)
