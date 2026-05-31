from typing import Any, Dict, Optional


class BaseAppException(Exception):
    status_code: int = 500
    error_code: str = "INTERNAL_ERROR"
    message: str = "内部处理错误"
    details: Optional[Dict[str, Any]] = None

    def __init__(
        self,
        message: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
        error_code: Optional[str] = None,
    ):
        if message:
            self.message = message
        if details:
            self.details = details
        if error_code:
            self.error_code = error_code
        super().__init__(self.message)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self.status_code,
            "error_code": self.error_code,
            "message": self.message,
            "details": self.details or {},
        }


class ValidationError(BaseAppException):
    status_code = 422
    error_code = "VALIDATION_ERROR"
    message = "参数校验失败"


class NotFoundError(BaseAppException):
    status_code = 404
    error_code = "NOT_FOUND"
    message = "资源不存在"


class ConflictError(BaseAppException):
    status_code = 409
    error_code = "CONFLICT"
    message = "资源冲突"


class TimeoutError(BaseAppException):
    status_code = 504
    error_code = "TIMEOUT"
    message = "上游服务响应超时"


class InternalError(BaseAppException):
    status_code = 500
    error_code = "INTERNAL_ERROR"
    message = "内部处理错误"


class PermissionDeniedError(BaseAppException):
    status_code = 403
    error_code = "PERMISSION_DENIED"
    message = "权限不足"
