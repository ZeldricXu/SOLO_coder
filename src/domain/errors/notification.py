from .base import BaseError


class NotificationError(BaseError):
    def __init__(self, message: str, channel=None, details=None):
        _details = details or {}
        if channel:
            _details["channel"] = channel
        super().__init__(message, code="notification_error", details=_details)
