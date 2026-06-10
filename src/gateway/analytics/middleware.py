from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from gateway.analytics.collector import get_analytics_collector, RequestRecord
from gateway.logger import get_logger

logger = get_logger("analytics-middleware")


class AnalyticsMiddleware(BaseHTTPMiddleware):
    def __init__(self, app):
        super().__init__(app)
        self.collector = get_analytics_collector()

    async def dispatch(self, request: Request, call_next):
        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        start_time = getattr(request.state, "start_time", __import__("time").time())
        request_id = getattr(request.state, "request_id", "")
        user = getattr(request.state, "user", {}) or {}
        route_match = getattr(request.state, "route_match", None)

        response = None
        error_type = ""

        try:
            response = await call_next(request)
            status_code = response.status_code

            if status_code >= 400:
                if status_code >= 500:
                    error_type = "server_error"
                else:
                    error_type = "client_error"

        except Exception as e:
            status_code = 500
            error_type = "exception"
            logger.error("Request exception in analytics middleware", error=str(e))
            raise

        finally:
            end_time = __import__("time").time()
            latency_ms = int((end_time - start_time) * 1000)
            upstream_latency_ms = getattr(request.state, "upstream_latency", 0)

            user_id = user.get("user_id", "") if isinstance(user, dict) else ""
            tenant_id = user.get("tenant_id", "") if isinstance(user, dict) else ""
            api_key = user.get("api_key_id", "") if isinstance(user, dict) else ""

            route_name = route_match.route.name if route_match else "unknown"
            api_path = self._normalize_path(path)

            rate_limited = getattr(request.state, "rate_limited", False)
            circuit_broken = getattr(request.state, "circuit_broken", False)

            client_ip = request.client.host if request.client else "unknown"
            user_agent = request.headers.get("user-agent", "")

            tags = {}
            if user and isinstance(user, dict):
                for k, v in user.items():
                    if k in ["scopes", "roles"] and isinstance(v, list):
                        tags[f"user_{k}"] = ",".join(v)
                    elif isinstance(v, (str, int, bool)):
                        tags[f"user_{k}"] = str(v)

            record = RequestRecord(
                timestamp=start_time,
                request_id=request_id,
                user_id=user_id,
                tenant_id=tenant_id,
                api_key=api_key,
                api_path=api_path,
                api_method=request.method,
                route_name=route_name,
                status_code=status_code,
                latency_ms=latency_ms,
                upstream_latency_ms=upstream_latency_ms,
                client_ip=client_ip,
                user_agent=user_agent,
                error_type=error_type,
                rate_limited=rate_limited,
                circuit_broken=circuit_broken,
                tags=tags,
            )

            __import__("asyncio").create_task(self.collector.collect(record))

        return response

    def _should_skip(self, path: str) -> bool:
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

    def _normalize_path(self, path: str) -> str:
        parts = path.split("/")
        normalized = []
        for part in parts:
            if part and (part.isdigit() or (len(part) == 36 and "-" in part)):
                normalized.append("{id}")
            else:
                normalized.append(part)
        return "/".join(normalized)
