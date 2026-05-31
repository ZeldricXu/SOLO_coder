class PlatformError(Exception):
    code: int = 500
    message: str = "内部错误"

    def __init__(self, message: str = None, code: int = None, details: dict = None):
        self.message = message or self.message
        self.code = code or self.code
        self.details = details or {}
        super().__init__(self.message)


class ValidationError(PlatformError):
    code = 422
    message = "参数校验失败"


class TimeoutError(PlatformError):
    code = 504
    message = "上游服务响应超时"


class NotFoundError(PlatformError):
    code = 404
    message = "资源不存在"


class ConflictError(PlatformError):
    code = 409
    message = "资源冲突"


class UnauthorizedError(PlatformError):
    code = 401
    message = "未授权访问"


class RateLimitError(PlatformError):
    code = 429
    message = "请求过于频繁"


class CircuitBreakerOpenError(PlatformError):
    code = 503
    message = "熔断器已打开，服务暂时不可用"
