"""
熔断器中间件模块

在请求生命周期中的位置：
    第 7 个中间件（顺序：RequestID → Metrics → SecurityFilter → Auth → RouteMatching → RateLimit → **CircuitBreaker** → Transform → ...）
    前一个中间件：RateLimitMiddleware（限流）
    后一个中间件：TransformMiddleware（请求/响应转换）

功能职责：
    - 基于服务粒度的熔断器保护，防止级联故障扩散
    - 状态机管理：closed → open → half_open → closed
    - 请求前检查熔断器状态，决定是否放行
    - 请求后记录成功/失败/慢请求指标
    - 支持三种降级策略：静态响应、备用目标地址、直接 503 拒绝

输入数据结构：
    request: Starlette Request 对象
        - request.url.path: 请求路径，用于跳过内部路径
        - request.state.route_match: 路由匹配结果（由 RouteMatchingMiddleware 注入）
        - request.state.start_time: 请求开始时间（可选，由 MetricsMiddleware 注入）
        - request.state.request_id: 请求ID（可选，用于日志追踪）

输出数据结构（写入 request.state）：
    - request.state.circuit_state: CircuitState，当前熔断器状态
    - request.state.circuit_service_name: str，服务名称（路由名:目标主机）
    - request.state.circuit_broken: bool，请求是否被熔断器拦截（仅在被拦截时设置）
    - request.state.fallback_target: str，备用目标地址（仅在配置 fallback_target 时设置）

输出响应头：
    - X-Circuit-State: 当前熔断器状态 (closed/open/half_open)
    - X-Circuit-Latency: 请求耗时（毫秒，仅成功响应时）
    - Retry-After: 重试等待秒数（仅 503 响应时）
    - X-Circuit-Fallback: 降级类型标识 (static)（仅静态降级时）
"""

from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from gateway.circuit_breaker.breaker import get_circuit_breaker, CircuitState
from gateway.logger import get_logger

logger = get_logger("circuit-breaker-middleware")


class CircuitBreakerMiddleware(BaseHTTPMiddleware):
    """
    熔断器中间件

    功能：
        实现熔断器模式，通过状态机管理后端服务的可用性。
        当服务失败率或慢请求率超过阈值时，熔断器打开，快速失败，
        避免级联故障扩散到上游服务。

    状态机流转：
        closed （闭合）：正常状态，所有请求放行，持续统计成功率
          ↓ 失败率/慢请求率超过阈值
        open （打开）：熔断状态，所有请求被拦截，直接返回降级响应
          ↓ 等待 wait_duration 时间后
        half_open （半开）：试探状态，允许少量请求通过
          ↓ 全部成功 → closed（恢复）
          ↓ 任一失败 → open（再次熔断）

    输入：
        - request: Starlette Request 对象
            - state.route_match: 路由匹配结果，包含路由配置和目标地址

    输出（写入 request.state）：
        - circuit_state: CircuitState 枚举值
        - circuit_service_name: str 服务标识
        - circuit_broken: bool 是否被熔断拦截
        - fallback_target: str 备用目标（可选）

    失败响应（降级）：
        - 503 Service Unavailable：默认降级响应，含 Retry-After 头
        - 200 + 静态 JSON：配置 fallback_response 时返回
        - 转发到备用目标：配置 fallback_target 时透传给后续中间件
    """

    def __init__(self, app):
        """
        初始化熔断器中间件

        Args:
            app: ASGI 应用实例（Starlette/FastAPI app）
        """
        super().__init__(app)
        self.breaker = get_circuit_breaker()

    async def dispatch(self, request: Request, call_next):
        """
        中间件主入口，执行熔断器检查与结果记录

        处理流程：
            1. 跳过内部路径（/health、/metrics 等）
            2. 检查路由是否启用熔断器，未启用则直接放行
            3. 调用熔断器 check() 检查当前状态，判断是否允许请求通过
            4. 若被拦截，根据配置返回降级响应（静态响应/备用目标/503）
            5. 若放行，调用下游并记录请求成功/失败/慢请求
            6. 响应中注入 X-Circuit-State 和 X-Circuit-Latency 头

        Args:
            request: Starlette 请求对象
            call_next: 调用下一个中间件的回调函数

        Returns:
            Starlette Response 对象，可能是正常响应或降级响应
        """
        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        route_match = getattr(request.state, "route_match", None)
        if not route_match:
            return await call_next(request)

        route = route_match.route
        if not route.circuit_breaker_enabled:
            return await call_next(request)

        service_name = self._get_service_name(route_match)
        config = route.circuit_breaker_config or {}

        result = await self.breaker.check(service_name, config)

        if not result.allowed:
            logger.warning(
                "Circuit breaker blocked request",
                service_name=service_name,
                state=result.state.value,
                retry_after=result.retry_after,
                request_id=getattr(request.state, "request_id", ""),
            )

            request.state.circuit_broken = True

            if result.fallback_response:
                return JSONResponse(
                    status_code=200,
                    content=result.fallback_response,
                    headers={
                        "X-Circuit-State": result.state.value,
                        "X-Circuit-Fallback": "static",
                    },
                )
            elif result.fallback_target:
                request.state.fallback_target = result.fallback_target
                return await call_next(request)
            else:
                return JSONResponse(
                    status_code=503,
                    content={
                        "error": {
                            "code": 503,
                            "message": "Service Unavailable",
                            "detail": "Service is temporarily unavailable due to high failure rate.",
                        }
                    },
                    headers={
                        "Retry-After": str(result.retry_after),
                        "X-Circuit-State": result.state.value,
                    },
                )

        request.state.circuit_state = result.state
        request.state.circuit_service_name = service_name

        start_time = getattr(request.state, "start_time", __import__("time").time())

        try:
            response = await call_next(request)

            latency = __import__("time").time() - start_time
            is_slow = latency > self.breaker.cb_settings.slow_request_duration

            if 200 <= response.status_code < 500 and not is_slow:
                await self.breaker.record_success(service_name, latency)
            else:
                await self.breaker.record_failure(service_name, latency, is_slow=is_slow)

            response.headers["X-Circuit-State"] = result.state.value
            response.headers["X-Circuit-Latency"] = f"{latency * 1000:.2f}ms"

            return response

        except Exception as e:
            latency = __import__("time").time() - start_time
            await self.breaker.record_failure(service_name, latency)

            logger.error(
                "Request failed, recorded as circuit failure",
                service_name=service_name,
                error=str(e),
                request_id=getattr(request.state, "request_id", ""),
            )
            raise

    def _should_skip(self, path: str) -> bool:
        """
        判断是否为需要跳过熔断器的内部路径

        健康检查、指标、文档等内部路径不参与熔断器统计，
        避免这些路径影响业务服务的熔断状态判断。

        Args:
            path: 请求路径字符串

        Returns:
            True 表示跳过熔断器，直接放行请求
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

    def _get_service_name(self, route_match) -> str:
        """
        根据路由匹配结果构造服务唯一标识

        服务名格式：{路由名称}:{目标主机名}
        用于在 Redis 中作为熔断器统计数据的 key 前缀。

        Args:
            route_match: 路由匹配对象，包含 route 和 target 属性

        Returns:
            服务唯一标识字符串，例如 "user-api:api.example.com"
        """
        route = route_match.route
        target = route_match.target

        route_name = route.name
        target_host = target.url.replace("http://", "").replace("https://", "").split("/")[0]

        return f"{route_name}:{target_host}"
