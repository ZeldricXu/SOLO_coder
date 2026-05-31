from typing import Optional, List, Any


class LLMGatewayException(Exception):
    def __init__(
        self,
        message: str,
        code: int = 500,
        errors: Optional[List[Any]] = None,
        details: Optional[dict] = None,
    ):
        super().__init__(message)
        self.message = message
        self.code = code
        self.errors = errors or []
        self.details = details or {}


class NotFoundException(LLMGatewayException):
    def __init__(self, message: str = "资源未找到", details: Optional[dict] = None):
        super().__init__(message, code=404, details=details)


class ValidationException(LLMGatewayException):
    def __init__(self, message: str = "参数校验失败", errors: Optional[List[Any]] = None):
        super().__init__(message, code=422, errors=errors)


class ConflictException(LLMGatewayException):
    def __init__(self, message: str = "资源冲突", details: Optional[dict] = None):
        super().__init__(message, code=409, details=details)


class UnauthorizedException(LLMGatewayException):
    def __init__(self, message: str = "未授权访问", details: Optional[dict] = None):
        super().__init__(message, code=401, details=details)


class ForbiddenException(LLMGatewayException):
    def __init__(self, message: str = "禁止访问", details: Optional[dict] = None):
        super().__init__(message, code=403, details=details)


class TimeoutException(LLMGatewayException):
    def __init__(self, message: str = "请求超时", details: Optional[dict] = None):
        super().__init__(message, code=504, details=details)


class RateLimitException(LLMGatewayException):
    def __init__(self, message: str = "请求频率超限", details: Optional[dict] = None):
        super().__init__(message, code=429, details=details)


class DependencyException(LLMGatewayException):
    def __init__(self, message: str = "依赖服务异常", details: Optional[dict] = None):
        super().__init__(message, code=502, details=details)
