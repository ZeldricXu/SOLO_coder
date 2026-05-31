import time
import json
from datetime import datetime, timezone
from typing import Callable, Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from .tracing import (
    Span,
    generate_trace_id,
    generate_span_id,
    get_current_trace_id,
    get_current_span_id,
    log_request,
    update_request_log,
)


class RequestTracingMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, service_name: str = "api-gateway"):
        super().__init__(app)
        self.service_name = service_name

    async def dispatch(self, request: Request, call_next: Callable):
        trace_id = request.headers.get("X-Trace-ID") or generate_trace_id()
        parent_span_id = request.headers.get("X-Parent-Span-ID")
        span_id = request.headers.get("X-Span-ID") or generate_span_id()

        request.state.trace_id = trace_id
        request.state.span_id = span_id

        method = request.method
        path = request.url.path
        client_ip = request.client.host if request.client else None
        user_agent = request.headers.get("user-agent")

        request_headers = dict(request.headers)
        request_body = None
        if method in ["POST", "PUT", "PATCH"]:
            try:
                body_bytes = await request.body()
                if body_bytes:
                    request_body = body_bytes.decode("utf-8", errors="replace")
            except Exception:
                pass

        start_time = time.time()

        log_id = None
        try:
            log_entry = await log_request(
                trace_id=trace_id,
                span_id=span_id,
                service_name=self.service_name,
                method=method,
                path=path,
                request_headers=request_headers,
                request_body=request_body,
                client_ip=client_ip,
                user_agent=user_agent,
                user_id=None,
            )
            log_id = log_entry.id
        except Exception:
            pass

        with Span(
            name=f"{method} {path}",
            service_name=self.service_name,
            trace_id=trace_id,
            span_id=span_id,
            parent_span_id=parent_span_id,
            kind="server",
            attributes={
                "http.method": method,
                "http.path": path,
                "http.client_ip": client_ip,
                "http.user_agent": user_agent,
            },
        ) as span:
            try:
                response = await call_next(request)

                duration_ms = (time.time() - start_time) * 1000
                status_code = response.status_code

                span.set_attribute("http.status_code", status_code)

                if log_id:
                    response_headers = dict(response.headers)
                    response_body = None

                    await update_request_log(
                        log_id=log_id,
                        status_code=status_code,
                        response_headers=response_headers,
                        response_body=response_body,
                        error_message=None,
                        duration_ms=duration_ms,
                    )

                response.headers["X-Trace-ID"] = trace_id
                response.headers["X-Span-ID"] = span_id

                return response

            except Exception as e:
                duration_ms = (time.time() - start_time) * 1000
                span.set_status("error", str(e))

                if log_id:
                    await update_request_log(
                        log_id=log_id,
                        status_code=500,
                        response_headers={},
                        response_body=None,
                        error_message=str(e),
                        duration_ms=duration_ms,
                    )
                raise


class CORSMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: Callable):
        if request.method == "OPTIONS":
            response = Response(status_code=204)
            response.headers["Access-Control-Allow-Origin"] = "*"
            response.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, PATCH, OPTIONS"
            response.headers["Access-Control-Allow-Headers"] = "*"
            return response

        response = await call_next(request)
        response.headers["Access-Control-Allow-Origin"] = "*"
        response.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, PATCH, OPTIONS"
        response.headers["Access-Control-Allow-Headers"] = "*"
        return response


class RateLimiterMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, requests_per_minute: int = 60):
        super().__init__(app)
        if not isinstance(requests_per_minute, int) or requests_per_minute <= 0:
            requests_per_minute = 60
        self.requests_per_minute = requests_per_minute
        self._request_counts: dict = {}

    async def dispatch(self, request: Request, call_next: Callable):
        client_ip = request.client.host if request.client else "unknown"
        path = request.url.path

        now = time.time()
        minute_key = f"{client_ip}:{int(now // 60)}"

        count = self._request_counts.get(minute_key, 0)
        if count >= self.requests_per_minute:
            return Response(
                content=json.dumps({
                    "code": 429,
                    "message": "Rate limit exceeded",
                    "data": None,
                }),
                status_code=429,
                media_type="application/json",
            )

        self._request_counts[minute_key] = count + 1

        for key in list(self._request_counts.keys()):
            if int(key.split(":")[-1]) < int(now // 60) - 1:
                del self._request_counts[key]

        return await call_next(request)
