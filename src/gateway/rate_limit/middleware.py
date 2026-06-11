"""
速率限制中间件模块

在请求生命周期中的位置：
    第 6 个中间件（顺序：RequestID → Metrics → SecurityFilter → Auth → RouteMatching → **RateLimit** → CircuitBreaker → Transform → Analytics → Proxy）
    前一个中间件：RouteMatchingMiddleware（路由匹配）
    后一个中间件：CircuitBreakerMiddleware（熔断器）

功能职责：
    - 对 API 请求进行速率限制，防止滥用
    - 支持单维度限流（用户 + API 路径）和多维度限流
    - 限流结果通过响应头返回给客户端
    - 超过限流阈值时返回 429 错误

输入数据结构：
    request: Starlette Request 对象
        - request.url.path: 请求路径
        - request.state.user: 用户信息（来自 Auth 中间件）
        - request.state.route_match: 路由匹配结果（来自 RouteMatching 中间件）

输出数据结构：
    request.state 写入：
        - rate_limit_result: RateLimitResult 对象，限流检查结果
        - rate_limited: bool，是否被限流（仅在被拒绝时设置）

    响应头添加：
        - X-RateLimit-Limit: 总限制次数
        - X-RateLimit-Remaining: 剩余次数
        - X-RateLimit-Used: 已使用次数
        - X-RateLimit-Burst: 是否使用了突发配额（可选）
        - X-RateLimit-Key: 限流键（可选）
        - Retry-After: 重试等待秒数（被限流时）
"""

from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from gateway.rate_limit.limiter import get_rate_limiter
from gateway.rate_limit.resolvers import get_rate_limit_key_resolver
from gateway.config import get_settings
from gateway.observability import record_rate_limit_rejection, record_rate_limit_remaining
from gateway.logger import get_logger

logger = get_logger("rate-limit-middleware")


class RateLimitMiddleware(BaseHTTPMiddleware):
    """
    速率限制中间件

    功能：
        对 API 请求进行速率限制，保护后端服务不被过度请求淹没。
        支持按用户、按 API 路径的多维度限流策略。

    输入：
        - request: Starlette Request 对象
            - url.path: 请求路径
            - state.user: 用户信息（来自认证中间件）
            - state.route_match: 路由匹配结果（来自路由匹配中间件）

    输出（写入 request.state）：
        - rate_limit_result: 限流检查结果对象
        - rate_limited: bool，是否被限流（仅拒绝时设置）

    失败响应：
        - 429 Too Many Requests: 请求超过速率限制
          响应体包含错误码、消息、详细信息和限流数据
          响应头包含 Retry-After 和限流相关头
    """

    def __init__(self, app):
        """
        初始化速率限制中间件

        Args:
            app: Starlette 应用实例
        """
        super().__init__(app)
        self.limiter = get_rate_limiter()
        self.settings = get_settings()
        self.rl_settings = self.settings.rate_limit
        if self.rl_settings.multi_dimension_enabled:
            self.key_resolver = get_rate_limit_key_resolver()

    async def dispatch(self, request: Request, call_next):
        """
        中间件主入口，执行限流检查

        Args:
            request: Starlette 请求对象
            call_next: 调用下一个中间件的回调函数

        Returns:
            Starlette Response 对象，正常响应或 429 限流响应
        """
        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        route_match = getattr(request.state, "route_match", None)
        if not route_match:
            return await call_next(request)

        route = route_match.route
        if not route.rate_limit_enabled:
            return await call_next(request)

        if self.rl_settings.multi_dimension_enabled:
            result = await self._check_multi_dimension(request, route)
        else:
            user_id = self._get_user_id(request)
            api_path = self._normalize_path(path)
            result = await self.limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=route.rate_limit_per_user,
                custom_api_limit=route.rate_limit_per_api,
            )

        response_headers = {
            "X-RateLimit-Limit": str(result.limit),
            "X-RateLimit-Remaining": str(max(0, result.remaining)),
            "X-RateLimit-Used": str(result.total_requests),
        }

        if result.used_burst:
            response_headers["X-RateLimit-Burst"] = "true"

        if hasattr(result, "rate_limit_key") and result.rate_limit_key:
            response_headers["X-RateLimit-Key"] = result.rate_limit_key

        if not result.allowed:
            response_headers["Retry-After"] = str(result.retry_after)

            logger.warning(
                "Rate limit exceeded",
                rate_limit_key=getattr(result, "rate_limit_key", None),
                api_path=path,
                limit=result.limit,
                retry_after=result.retry_after,
                request_id=getattr(request.state, "request_id", ""),
            )

            request.state.rate_limited = True

            rl_key = getattr(result, "rate_limit_key", None) or "unknown"
            route_name = route.name if hasattr(route, "name") else None
            record_rate_limit_rejection(route=route_name, key=rl_key)

            return JSONResponse(
                status_code=429,
                content={
                    "error": {
                        "code": 429,
                        "message": "Too Many Requests",
                        "detail": f"Rate limit exceeded. Please retry after {result.retry_after} seconds.",
                        "rate_limit": {
                            "limit": result.limit,
                            "remaining": result.remaining,
                            "retry_after": result.retry_after,
                        },
                    }
                },
                headers=response_headers,
            )

        request.state.rate_limit_result = result

        rl_key = getattr(result, "rate_limit_key", None)
        route_name = route.name if hasattr(route, "name") else None
        if rl_key:
            record_rate_limit_remaining(route=route_name, key=rl_key, remaining=result.remaining)

        response = await call_next(request)

        for key, value in response_headers.items():
            response.headers[key] = value

        return response

    async def _check_multi_dimension(self, request: Request, route):
        """
        多维度限流检查

        使用 key_resolver 解析多个限流维度（如用户、IP、设备等），
        然后调用限流器进行多维度检查。

        Args:
            request: 请求对象
            route: 路由对象，包含自定义限流配置

        Returns:
            RateLimitResult 对象，包含限流检查结果
        """
        context = {
            "api_path": self._normalize_path(request.url.path),
            "route": route,
        }
        keys = await self.key_resolver.resolve_keys(request, context)
        return await self.limiter.check_rate_limit_multi_dimension(
            request=request,
            api_path=context["api_path"],
            custom_user_limit=route.rate_limit_per_user,
            custom_api_limit=route.rate_limit_per_api,
        )

    def _should_skip(self, path: str) -> bool:
        """
        判断是否为跳过限流的路径

        健康检查、指标、文档等内部路径不需要限流。

        Args:
            path: 请求路径

        Returns:
            True 表示跳过限流，False 表示需要限流
        """
        skip_paths = [
            "/health",
            "/metrics",
            "/docs",
            "/openapi.json",
            "/redoc",
            "/portal/",
            "/static/",
        ]
        return any(path.startswith(p) for p in skip_paths)

    def _get_user_id(self, request: Request) -> Optional[str]:
        """
        获取用于限流的用户标识

        优先级：
            1. 认证用户的 user_id 或 api_key_id
            2. 请求头中的 API Key（哈希处理）
            3. 客户端 IP 地址

        Args:
            request: 请求对象

        Returns:
            用户标识字符串，用于限流键生成
        """
        user = getattr(request.state, "user", None)
        if user and isinstance(user, dict):
            return user.get("user_id") or user.get("api_key_id")

        api_key = request.headers.get("X-API-Key") or request.headers.get("api-key")
        if api_key:
            return f"apikey:{hash(api_key) % 1000000}"

        client_ip = request.client.host if request.client else "unknown"
        return f"ip:{client_ip}"

    def _normalize_path(self, path: str) -> str:
        """
        归一化 API 路径，将路径参数替换为占位符

        将数字 ID 和 UUID 替换为 {id}，使相同模式的路径共享限流配额。
        例如：/api/users/123 → /api/users/{id}
              /api/orders/550e8400-e29b-41d4-a716-446655440000 → /api/orders/{id}

        Args:
            path: 原始请求路径

        Returns:
            归一化后的路径字符串
        """
        parts = path.split("/")
        normalized = []
        for part in parts:
            if part and (part.isdigit() or len(part) == 36 and "-" in part):
                normalized.append("{id}")
            else:
                normalized.append(part)
        return "/".join(normalized)
