from .base import BaseError


class GatewayError(BaseError):
    def __init__(self, message: str, status_code: int = 500, details=None):
        _details = details or {}
        _details["status_code"] = status_code
        super().__init__(message, code="gateway_error", details=_details)


class ConsistencyError(GatewayError):
    def __init__(self, message: str, request_id: str = "", details=None):
        _details = details or {}
        if request_id:
            _details["request_id"] = request_id
        super().__init__(message, status_code=409, details=_details)
