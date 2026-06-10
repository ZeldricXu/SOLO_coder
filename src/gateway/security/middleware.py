from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from gateway.security.filter import get_security_filter, SecurityScanResult
from gateway.logger import get_logger

logger = get_logger("security-middleware")


class SecurityFilterMiddleware(BaseHTTPMiddleware):
    def __init__(self, app):
        super().__init__(app)
        self.filter = get_security_filter()

    async def dispatch(self, request: Request, call_next):
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

        if scan_result.blocked:
            return self.filter.get_blocked_response(scan_result)

        if scan_result.is_suspicious:
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
