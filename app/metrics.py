"""Prometheus 指标采集模块，暴露 /internal/metrics 端点供监控系统采集。

提供的指标：
- http_requests_total: HTTP 请求总数（按 method/endpoint/status_code 分类）
- http_request_duration_seconds: HTTP 请求延迟直方图
- http_requests_in_progress: 当前正在处理的请求数
- health_checks_total: 健康检查执行总数（按 service/status 分类）
- alerts_triggered_total: 告警触发总数（按 level/rule_name 分类）
- alerts_active: 当前活跃告警数（按 level 分类）
- slow_sql_total: 慢SQL总数（按 fingerprint 分类）

使用方式：
    from app.metrics import PrometheusMiddleware, get_metrics_response
    app.add_middleware(PrometheusMiddleware)
    # 在路由中返回 get_metrics_response() 即可
"""
import time
from typing import Callable
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from prometheus_client import Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST


REQUEST_COUNT = Counter(
    "http_requests_total",
    "Total number of HTTP requests",
    ["method", "endpoint", "status_code"]
)

REQUEST_LATENCY = Histogram(
    "http_request_duration_seconds",
    "HTTP request latency in seconds",
    ["method", "endpoint"]
)

REQUEST_IN_PROGRESS = Gauge(
    "http_requests_in_progress",
    "Number of HTTP requests in progress"
)

HEALTH_CHECK_COUNT = Counter(
    "health_checks_total",
    "Total number of health checks",
    ["service", "status"]
)

ALERT_TRIGGERED_COUNT = Counter(
    "alerts_triggered_total",
    "Total number of alerts triggered",
    ["level", "rule_name"]
)

ACTIVE_ALERTS = Gauge(
    "alerts_active",
    "Number of active alerts",
    ["level"]
)

SLOW_SQL_COUNT = Counter(
    "slow_sql_total",
    "Total number of slow SQL queries",
    ["fingerprint"]
)


class PrometheusMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        REQUEST_IN_PROGRESS.inc()

        start_time = time.time()
        method = request.method
        endpoint = self._get_endpoint(request)

        try:
            response = await call_next(request)
            status_code = str(response.status_code)

            REQUEST_COUNT.labels(
                method=method,
                endpoint=endpoint,
                status_code=status_code
            ).inc()

            latency = time.time() - start_time
            REQUEST_LATENCY.labels(
                method=method,
                endpoint=endpoint
            ).observe(latency)

            return response
        finally:
            REQUEST_IN_PROGRESS.dec()

    def _get_endpoint(self, request: Request) -> str:
        path = request.url.path

        if path.startswith("/static/"):
            return "/static/*"
        if path.startswith("/api/"):
            parts = path.split("/")
            if len(parts) >= 4:
                return f"/api/{parts[2]}/{'/'.join(parts[3:]) if len(parts) > 4 else ''}"
            return path
        return path


def get_metrics_response() -> Response:
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST
    )
