from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from gateway.observability.metrics import (
    record_request_start,
    record_request_end,
)
from gateway.logger import get_logger

logger = get_logger("metrics-middleware")


class MetricsMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        method = request.method
        start_time = getattr(request.state, "start_time", __import__("time").time())

        route_match = getattr(request.state, "route_match", None)
        route_name = route_match.route.name if route_match and route_match.route else None

        record_request_start(method=method, route=route_name)

        response = None
        try:
            response = await call_next(request)
            status_code = response.status_code

            end_time = __import__("time").time()
            duration = end_time - start_time

            request_size = 0
            try:
                content_length = request.headers.get("content-length")
                if content_length:
                    request_size = int(content_length)
            except (ValueError, TypeError):
                pass

            response_size = 0
            try:
                content_length = response.headers.get("content-length")
                if content_length:
                    response_size = int(content_length)
            except (ValueError, TypeError):
                pass

            user = getattr(request.state, "user", {}) or {}
            user_id = user.get("user_id", "") if isinstance(user, dict) else ""
            tenant_id = user.get("tenant_id", "") if isinstance(user, dict) else ""

            record_request_end(
                method=method,
                route=route_name,
                status_code=status_code,
                duration_seconds=duration,
                user_id=user_id,
                tenant_id=tenant_id,
                request_size=request_size if request_size > 0 else None,
                response_size=response_size if response_size > 0 else None,
            )

            return response

        except Exception as e:
            end_time = __import__("time").time()
            duration = end_time - start_time

            user = getattr(request.state, "user", {}) or {}
            user_id = user.get("user_id", "") if isinstance(user, dict) else ""
            tenant_id = user.get("tenant_id", "") if isinstance(user, dict) else ""

            record_request_end(
                method=method,
                route=route_name,
                status_code=500,
                duration_seconds=duration,
                user_id=user_id,
                tenant_id=tenant_id,
            )
            raise

    def _should_skip(self, path: str) -> bool:
        skip_paths = [
            "/health",
            "/live",
            "/ready",
            "/metrics",
            "/docs",
            "/openapi.json",
            "/redoc",
            "/portal/",
            "/static/",
        ]
        return any(path.startswith(p) for p in skip_paths)
