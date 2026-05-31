from typing import Any, Dict, Optional


class PlatformError(Exception):
    status_code: int = 500
    error_code: str = "internal_error"

    def __init__(
        self,
        message: str,
        details: Optional[Dict[str, Any]] = None,
        resource_id: Optional[str] = None,
    ):
        super().__init__(message)
        self.message = message
        self.details = details or {}
        self.resource_id = resource_id

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self.status_code,
            "error_code": self.error_code,
            "message": self.message,
            "details": self.details,
            "resource_id": self.resource_id,
        }


class ValidationError(PlatformError):
    status_code = 422
    error_code = "validation_error"


class ConcurrencyConflictError(PlatformError):
    status_code = 409
    error_code = "concurrency_conflict"


class TimeoutError(PlatformError):
    status_code = 504
    error_code = "timeout"

    def __init__(
        self,
        message: str = "上游服务响应超时",
        details: Optional[Dict[str, Any]] = None,
        resource_id: Optional[str] = None,
    ):
        super().__init__(message, details, resource_id)


class InternalError(PlatformError):
    status_code = 500
    error_code = "internal_error"

    def __init__(
        self,
        message: str = "内部处理错误",
        details: Optional[Dict[str, Any]] = None,
        resource_id: Optional[str] = None,
    ):
        super().__init__(message, details, resource_id)


class ConfigNotFoundError(PlatformError):
    status_code = 404
    error_code = "config_not_found"


class NotificationError(PlatformError):
    status_code = 503
    error_code = "notification_failed"


class AnomalyDetectionError(PlatformError):
    status_code = 500
    error_code = "anomaly_detection_failed"


class TracingError(PlatformError):
    status_code = 500
    error_code = "tracing_error"


class MigrationError(PlatformError):
    status_code = 500
    error_code = "migration_failed"


class SLOError(PlatformError):
    status_code = 500
    error_code = "slo_error"


class TopologyError(PlatformError):
    status_code = 500
    error_code = "topology_error"


class GatewayError(PlatformError):
    status_code = 502
    error_code = "gateway_error"


class ProfilingError(PlatformError):
    status_code = 500
    error_code = "profiling_error"
