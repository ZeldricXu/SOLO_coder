"""
API网关模块
实现请求日志与链路追踪
依赖抽象协议：LoggerProtocol
"""

from .gateway_module import (
    ApiGateway,
    RequestLogger,
    TraceManager,
    GatewayMiddleware,
    RateLimitMiddleware,
    AuthMiddleware,
    SimpleRequest,
    SimpleResponse,
    SimpleTraceContext,
)

__all__ = [
    "ApiGateway",
    "RequestLogger",
    "TraceManager",
    "GatewayMiddleware",
    "RateLimitMiddleware",
    "AuthMiddleware",
    "SimpleRequest",
    "SimpleResponse",
    "SimpleTraceContext",
]
