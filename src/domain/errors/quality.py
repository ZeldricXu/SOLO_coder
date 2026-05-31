from .base import BaseError


class QualityCheckError(BaseError):
    def __init__(self, message: str, details=None):
        super().__init__(message, code="quality_check_error", details=details)


class ConcurrencyError(QualityCheckError):
    def __init__(self, message: str, file: str = "", details=None):
        _details = details or {}
        if file:
            _details["file"] = file
        super().__init__(message, details=_details)
