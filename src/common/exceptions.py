from __future__ import annotations

from typing import Any, Dict, Optional


class InfrastructureError(Exception):
    code: int = 500
    message: str = "内部处理错误"
    details: Optional[Dict[str, Any]] = None

    def __init__(
        self,
        message: Optional[str] = None,
        code: Optional[int] = None,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        self.message = message or self.message
        self.code = code or self.code
        self.details = details
        super().__init__(self.message)


class ValidationError(InfrastructureError):
    code = 422
    message = "参数校验失败"


class NotFoundError(InfrastructureError):
    code = 404
    message = "资源不存在"


class ConflictError(InfrastructureError):
    code = 409
    message = "资源冲突"


class UnauthorizedError(InfrastructureError):
    code = 401
    message = "未授权访问"


class ForbiddenError(InfrastructureError):
    code = 403
    message = "无权限访问"


class RateLimitError(InfrastructureError):
    code = 429
    message = "请求频率超限"


class TimeoutError(InfrastructureError):
    code = 504
    message = "上游服务响应超时"


class ConfigurationError(InfrastructureError):
    code = 500
    message = "配置错误"


class CacheError(InfrastructureError):
    code = 500
    message = "缓存操作失败"


class StorageError(InfrastructureError):
    code = 500
    message = "存储操作失败"
