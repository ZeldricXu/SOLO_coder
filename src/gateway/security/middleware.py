"""
安全过滤中间件模块

在请求生命周期中的位置：
    第 3 个中间件（顺序：RequestID → Metrics → **SecurityFilter** → Auth → RouteMatching → ...）
    前一个中间件：MetricsMiddleware（指标采集）
    后一个中间件：AuthMiddleware（认证授权）

功能职责：
    - 对入站请求进行安全扫描（XSS、SQL注入、命令注入、路径遍历等）
    - 支持两种运行模式：block（拦截模式）和 sanitize（清理模式）
    - 将扫描结果和清理后的数据注入 request.state

输入数据结构：
    request: Starlette Request 对象
        - request.headers: 请求头
        - request.url.path: 请求路径
        - request.url.query: 查询参数
        - request.body(): 请求体（POST/PUT/PATCH/DELETE 方法）

输出数据结构（写入 request.state）：
    - request.state.cached_body: bytes，缓存的原始请求体（仅 POST/PUT/PATCH/DELETE）
    - request.state.security_scan_result: SecurityScanResult，安全扫描结果对象
    - request.state.security_sanitized: bool，是否对请求进行了清理
    - request.state.sanitized_headers: Dict[str, str]，清理后的请求头（可选）
    - request.state.sanitized_query: str，清理后的查询字符串（可选）
    - request.state.sanitized_body: bytes，清理后的请求体（可选）
"""

from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from gateway.security.filter import get_security_filter, SecurityScanResult
from gateway.observability import record_security_blocked, record_security_cleaned
from gateway.logger import get_logger

logger = get_logger("security-middleware")


class SecurityFilterMiddleware(BaseHTTPMiddleware):
    """
    安全过滤中间件

    功能：
        - 对入站请求执行安全扫描，检测常见的 Web 攻击载荷
        - block 模式：检测到恶意请求直接返回 403 拦截响应
        - sanitize 模式：检测到可疑请求时对敏感内容进行清理（转义/移除），
          清理后的数据通过 request.state 传递给下游中间件和业务逻辑

    输入：
        - request: Starlette Request 对象
            - headers: 请求头
            - url.path: 请求路径
            - url.query: 查询参数
            - body: 请求体（POST/PUT/PATCH/DELETE）

    输出（写入 request.state）：
        - security_scan_result: SecurityScanResult 扫描结果
        - security_sanitized: bool 是否经过清理
        - sanitized_headers: dict 清理后的请求头（可选）
        - sanitized_query: str 清理后的查询串（可选）
        - sanitized_body: bytes 清理后的请求体（可选）
        - cached_body: bytes 原始请求体缓存（可选）

    失败响应：
        - 403 Forbidden: 请求被安全规则拦截，响应头包含 X-Security-Filter: blocked
    """

    def __init__(self, app):
        """
        初始化安全过滤中间件

        Args:
            app: Starlette 应用实例（下一个中间件或应用）
        """
        super().__init__(app)
        self.filter = get_security_filter()

    async def dispatch(self, request: Request, call_next):
        """
        中间件主入口：执行安全扫描并处理结果

        处理流程：
            1. 检查安全过滤器是否启用，未启用则直接放行
            2. 检查是否为跳过路径（健康检查、文档等），是则直接放行
            3. 读取请求体并缓存到 request.state
            4. 调用安全过滤器执行扫描
            5. 根据扫描结果处理：
               - blocked=True: 返回 403 拦截响应
               - is_suspicious=True: 注入清理后的数据到 request.state
               - 正常请求：注入扫描结果到 request.state

        Args:
            request: Starlette 请求对象
            call_next: 调用下一个中间件的回调函数

        Returns:
            Starlette Response 对象（正常响应或 403 拦截响应）
        """
        if not self.filter.sf_settings.enabled:
            return await call_next(request)

        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        body = None
        if request.method in ["POST", "PUT", "PATCH", "DELETE"]:
            try:
                body = await request.body()
                request.state.cached_body = body
            except Exception:
                pass

        scan_result = await self.filter.scan_request(request, body)

        route_match = getattr(request.state, "route_match", None)
        route_name = route_match.route.name if route_match and route_match.route else None

        if scan_result.blocked:
            for rule in scan_result.matched_rules:
                rule_id = getattr(rule, "id", "unknown")
                category = getattr(rule, "category", "unknown")
                severity = getattr(rule, "severity", "unknown")
                record_security_blocked(route=route_name, category=category, severity=severity, rule_id=rule_id)
            return self.filter.get_blocked_response(scan_result)

        if scan_result.is_suspicious:
            categories = set()
            for rule in scan_result.matched_rules:
                category = getattr(rule, "category", "unknown")
                categories.add(category)
            for category in categories:
                record_security_cleaned(route=route_name, category=category)

            request.state.security_scan_result = scan_result
            request.state.security_sanitized = True

            if scan_result.sanitized_headers:
                request.state.sanitized_headers = scan_result.sanitized_headers
            if scan_result.sanitized_query is not None:
                request.state.sanitized_query = scan_result.sanitized_query
            if scan_result.sanitized_body is not None:
                request.state.sanitized_body = scan_result.sanitized_body
        else:
            request.state.security_scan_result = scan_result
            request.state.security_sanitized = False

        return await call_next(request)

    def _should_skip(self, path: str) -> bool:
        """
        判断是否为跳过安全扫描的路径

        健康检查、指标、API 文档等内部/管理路径跳过安全扫描，
        避免误拦截和提升性能。

        Args:
            path: 请求路径

        Returns:
            True 表示跳过安全扫描，False 表示需要扫描
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
