"""API Gateway module orchestrator."""
from __future__ import annotations

import asyncio
from typing import Any, Callable, Dict, List, Optional
from uuid import UUID

from ...domain.errors.gateway import (
    AuthenticationError,
    AuthorizationError,
    RateLimitExceededError,
    RequestValidationError,
)
from ...infrastructure.cache.redis_cache import RedisCache
from ...infrastructure.config.settings import Settings
from ...infrastructure.logging.structured_logger import LogManager
from .request_logger import RequestLogger
from .tracing import SpanKind, SpanStatus, Tracer, TracingManager


class RateLimiter:
    def __init__(self, max_requests: int = 1000, window_seconds: int = 60, cache: Optional[RedisCache] = None) -> None:
        self._max_requests = max_requests
        self._window_seconds = window_seconds
        self._cache = cache
        self._local_counts: Dict[str, Dict] = {}
        self._logger = LogManager().get_logger(__name__)

    async def check_rate_limit(self, client_id: str) -> bool:
        key = f"rate_limit:{client_id}"
        now = asyncio.get_event_loop().time()

        if self._cache is not None:
            count = await self._cache.get(key)
            if count is None:
                await self._cache.set(key, 1, ttl=self._window_seconds)
                return True
            if count >= self._max_requests:
                return False
            await self._cache.set(key, count + 1, ttl=self._window_seconds)
            return True
        else:
            if client_id not in self._local_counts or now - self._local_counts[client_id]["window_start"] > self._window_seconds:
                self._local_counts[client_id] = {"count": 1, "window_start": now}
                return True
            if self._local_counts[client_id]["count"] >= self._max_requests:
                return False
            self._local_counts[client_id]["count"] += 1
            return True


class AuthManager:
    def __init__(self) -> None:
        self._api_keys: Dict[str, Dict] = {}
        self._logger = LogManager().get_logger(__name__)

    def add_api_key(self, api_key: str, permissions: List[str], tenant_id: str = "default") -> None:
        self._api_keys[api_key] = {
            "permissions": permissions,
            "tenant_id": tenant_id,
            "active": True,
        }
        self._logger.info(f"Added API key for tenant: {tenant_id}")

    async def authenticate(self, headers: Dict[str, str]) -> Dict:
        api_key = headers.get("x-api-key") or headers.get("Authorization", "").replace("Bearer ", "")

        if not api_key:
            raise AuthenticationError(
                message="No API key provided",
                suggestion="Please provide a valid API key in the 'x-api-key' header or 'Authorization: Bearer <key>' header.",
            )

        if api_key not in self._api_keys:
            raise AuthenticationError(
                message="Invalid API key",
                suggestion="Please check that your API key is correct and has not been revoked.",
            )

        key_info = self._api_keys[api_key]
        if not key_info.get("active", True):
            raise AuthenticationError(
                message="API key has been revoked",
                suggestion="Please contact your administrator to obtain a new API key.",
            )

        return key_info

    async def authorize(self, auth_info: Dict, action: str, resource: Optional[str] = None) -> None:
        permissions = auth_info.get("permissions", [])

        if action not in permissions and "*" not in permissions:
            raise AuthorizationError(
                action=action,
                resource=resource,
                suggestion=f"Your API key does not have the '{action}' permission. Please contact your administrator.",
            )


class GatewayModule:
    def __init__(
        self,
        settings: Settings,
        cache: Optional[RedisCache] = None,
    ) -> None:
        self._settings = settings
        self._request_logger = RequestLogger()
        self._tracing_manager = TracingManager(
            service_name=settings.tracing.service_name,
            enabled=settings.tracing.enabled,
        )
        self._tracer = Tracer(self._tracing_manager)
        self._rate_limiter = RateLimiter(cache=cache)
        self._auth_manager = AuthManager()
        self._routes: Dict[str, Callable] = {}
        self._middlewares: List[Callable] = []
        self._logger = LogManager().get_logger(__name__)
        self._logger.info("API Gateway module initialized")

    @property
    def request_logger(self) -> RequestLogger:
        return self._request_logger

    @property
    def tracing_manager(self) -> TracingManager:
        return self._tracing_manager

    @property
    def tracer(self) -> Tracer:
        return self._tracer

    @property
    def auth_manager(self) -> AuthManager:
        return self._auth_manager

    def register_route(self, path: str, handler: Callable, methods: List[str] = None) -> None:
        methods = methods or ["GET", "POST", "PUT", "DELETE", "PATCH"]
        for method in methods:
            key = f"{method}:{path}"
            self._routes[key] = handler
        self._logger.info(f"Registered route: {path} ({', '.join(methods)})")

    def register_middleware(self, middleware: Callable) -> None:
        self._middlewares.append(middleware)
        self._logger.info("Registered middleware")

    async def process_request(
        self,
        method: str,
        path: str,
        headers: Dict[str, str],
        body: bytes = b"",
        query_params: Optional[Dict[str, str]] = None,
    ) -> Dict[str, Any]:
        request_id = await self._request_logger.start_request(
            method=method,
            path=path,
            client_ip=headers.get("x-forwarded-for", ""),
            user_agent=headers.get("user-agent", ""),
            correlation_id=headers.get("x-correlation-id"),
        )

        trace_context = self._tracing_manager.extract_trace_context(headers)
        span = self._tracing_manager.start_span(
            name=f"{method} {path}",
            kind=SpanKind.SERVER,
            trace_id=trace_context["trace_id"],
            parent_span_id=trace_context["span_id"],
            attributes={
                "http.method": method,
                "http.path": path,
                "http.client_ip": headers.get("x-forwarded-for", ""),
                "http.user_agent": headers.get("user-agent", ""),
                "request_id": str(request_id),
            },
        )

        response_headers: Dict[str, str] = {}
        self._tracing_manager.inject_trace_context(span, response_headers)

        try:
            client_id = headers.get("x-api-key", "anonymous")
            if not await self._rate_limiter.check_rate_limit(client_id):
                raise RateLimitExceededError(
                    limit=self._rate_limiter._max_requests,
                    window_seconds=self._rate_limiter._window_seconds,
                    client_id=client_id,
                )

            if not path.startswith("/health") and not path.startswith("/public"):
                auth_info = await self._auth_manager.authenticate(headers)
                await self._auth_manager.authorize(auth_info, method, path)
                response_headers["x-tenant-id"] = auth_info.get("tenant_id", "default")

            for middleware in self._middlewares:
                result = await middleware(method, path, headers, body, query_params)
                if result is not None:
                    return result

            route_key = f"{method}:{path}"
            handler = self._routes.get(route_key)

            if handler is None:
                for registered_path, registered_handler in self._routes.items():
                    if registered_path.endswith(":*") and path.startswith(registered_path[:-2]):
                        handler = registered_handler
                        break

            if handler is None:
                response = {
                    "status_code": 404,
                    "headers": response_headers,
                    "body": {"error": "Not Found", "message": f"No handler found for {method} {path}"},
                }
            else:
                span.add_event("request.body.processed", {"size": len(body)})
                response_body = await handler(headers, body, query_params)
                response = {
                    "status_code": 200,
                    "headers": response_headers,
                    "body": response_body,
                }

            self._tracing_manager.end_span(span, SpanStatus.OK)
            await self._request_logger.end_request(
                request_id=request_id,
                status_code=response["status_code"],
                content_length=len(str(response["body"]).encode()),
            )

            return response

        except Exception as e:
            self._tracing_manager.end_span(
                span,
                SpanStatus.ERROR,
                description=str(e),
            )

            error_response = {
                "status_code": getattr(e, "http_status", 500),
                "headers": response_headers,
                "body": {
                    "error": getattr(e, "code", "INTERNAL_ERROR"),
                    "message": str(e),
                    "suggestion": getattr(e, "suggestion", None),
                    "details": getattr(e, "details", {}),
                },
            }

            await self._request_logger.end_request(
                request_id=request_id,
                status_code=error_response["status_code"],
                error_message=str(e),
            )

            return error_response

    async def start(self) -> None:
        self._logger.info("Starting API Gateway module")

    async def stop(self) -> None:
        self._logger.info("Stopping API Gateway module")

    async def get_status(self) -> dict:
        return {
            "module": "gateway",
            "status": "running",
            "routes_count": len(self._routes),
            "middlewares_count": len(self._middlewares),
            "tracing_enabled": self._settings.tracing.enabled,
        }
