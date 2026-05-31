import time
from abc import ABC, abstractmethod
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional

from .router import RequestContext, RoutingDecision


class Middleware(ABC):
    @abstractmethod
    def process_request(self, ctx: RequestContext) -> Optional[RoutingDecision]:
        pass

    def process_response(self, ctx: RequestContext, response: Any) -> None:
        pass


class MiddlewareChain:
    def __init__(self, middlewares: Optional[List[Middleware]] = None):
        self._middlewares: List[Middleware] = middlewares or []

    def add(self, middleware: Middleware) -> None:
        self._middlewares.append(middleware)

    def process_request(self, ctx: RequestContext) -> Optional[RoutingDecision]:
        for middleware in self._middlewares:
            decision = middleware.process_request(ctx)
            if decision is not None and decision != RoutingDecision.MATCHED:
                return decision
        return None

    def process_response(self, ctx: RequestContext, response: Any) -> None:
        for middleware in reversed(self._middlewares):
            middleware.process_response(ctx, response)


class RateLimitMiddleware(Middleware):
    def __init__(
        self,
        requests_per_minute: int = 100,
        window_seconds: int = 60,
        key_extractor: Optional[Callable[[RequestContext], str]] = None,
    ):
        self._requests_per_minute = requests_per_minute
        self._window_seconds = window_seconds
        self._key_extractor = key_extractor or self._default_key_extractor
        self._buckets: Dict[str, List[float]] = defaultdict(list)

    def _default_key_extractor(self, ctx: RequestContext) -> str:
        return ctx.client_ip or ctx.headers.get("X-Forwarded-For", "unknown")

    def process_request(self, ctx: RequestContext) -> Optional[RoutingDecision]:
        key = self._key_extractor(ctx)
        now = time.time()
        self._cleanup_old(now)
        if key not in self._buckets:
            self._buckets[key] = []
        window_start = now - self._window_seconds
        valid_requests = [t for t in self._buckets[key] if t > window_start]
        if len(valid_requests) >= self._requests_per_minute:
            return RoutingDecision.RATE_LIMITED
        self._buckets[key].append(now)
        ctx.attributes["rate_limit_remaining"] = self._requests_per_minute - len(valid_requests) - 1
        return None

    def _cleanup_old(self, now: float) -> None:
        window_start = now - self._window_seconds
        for key in list(self._buckets.keys()):
            self._buckets[key] = [t for t in self._buckets[key] if t > window_start]
            if not self._buckets[key]:
                del self._buckets[key]


class AuthMiddleware(Middleware):
    def __init__(
        self,
        token_validator: Optional[Callable[[str], Any]] = None,
        header_name: str = "Authorization",
        prefix: str = "Bearer",
    ):
        self._token_validator = token_validator
        self._header_name = header_name
        self._prefix = prefix
        self._api_keys: Dict[str, Dict[str, Any]] = {}

    def register_api_key(self, key: str, permissions: List[str] = None, metadata: Dict[str, Any] = None) -> None:
        self._api_keys[key] = {
            "permissions": permissions or [],
            "metadata": metadata or {},
            "created_at": datetime.now(timezone.utc),
        }

    def process_request(self, ctx: RequestContext) -> Optional[RoutingDecision]:
        auth_header = ctx.headers.get(self._header_name)
        if not auth_header:
            return RoutingDecision.UNAUTHORIZED
        token = auth_header
        if self._prefix and auth_header.startswith(f"{self._prefix} "):
            token = auth_header[len(self._prefix) + 1:]
        if self._token_validator:
            try:
                result = self._token_validator(token)
                if not result:
                    return RoutingDecision.UNAUTHORIZED
                ctx.attributes["auth_result"] = result
                return None
            except Exception:
                return RoutingDecision.UNAUTHORIZED
        if token in self._api_keys:
            ctx.attributes["api_key_metadata"] = self._api_keys[token]
            return None
        return RoutingDecision.UNAUTHORIZED


class RequestLoggerMiddleware(Middleware):
    def __init__(self, logger=None):
        self._logger = logger

    def process_request(self, ctx: RequestContext) -> Optional[RoutingDecision]:
        if self._logger:
            self._logger.info(
                f"[{ctx.request_id}] {ctx.method} {ctx.path} from {ctx.client_ip}"
            )
        return None

    def process_response(self, ctx: RequestContext, response: Any) -> None:
        duration = (datetime.now(timezone.utc) - ctx.start_time).total_seconds() * 1000
        if self._logger:
            self._logger.info(
                f"[{ctx.request_id}] Completed in {duration:.2f}ms"
            )


class CircuitBreakerMiddleware(Middleware):
    def __init__(
        self,
        failure_threshold: int = 5,
        reset_timeout_seconds: int = 30,
        half_open_max_calls: int = 3,
    ):
        self._failure_threshold = failure_threshold
        self._reset_timeout = reset_timeout_seconds
        self._half_open_max = half_open_max_calls
        self._state: Dict[str, str] = {}
        self._failures: Dict[str, int] = {}
        self._last_failure: Dict[str, datetime] = {}
        self._half_open_calls: Dict[str, int] = {}

    def process_request(self, ctx: RequestContext) -> Optional[RoutingDecision]:
        route_name = ctx.attributes.get("route_name", "default")
        now = datetime.now(timezone.utc)
        if route_name in self._state:
            if self._state[route_name] == "open":
                if route_name in self._last_failure:
                    elapsed = (now - self._last_failure[route_name]).total_seconds()
                    if elapsed >= self._reset_timeout:
                        self._state[route_name] = "half-open"
                        self._half_open_calls[route_name] = 0
                    else:
                        return RoutingDecision.RATE_LIMITED
                else:
                    return RoutingDecision.RATE_LIMITED
            if self._state[route_name] == "half-open":
                if self._half_open_calls.get(route_name, 0) >= self._half_open_max:
                    return RoutingDecision.RATE_LIMITED
                self._half_open_calls[route_name] = self._half_open_calls.get(route_name, 0) + 1
        return None

    def record_success(self, route_name: str) -> None:
        if route_name in self._state and self._state[route_name] == "half-open":
            self._state[route_name] = "closed"
            self._failures[route_name] = 0
            self._half_open_calls.pop(route_name, None)

    def record_failure(self, route_name: str) -> None:
        now = datetime.now(timezone.utc)
        self._failures[route_name] = self._failures.get(route_name, 0) + 1
        self._last_failure[route_name] = now
        if self._failures[route_name] >= self._failure_threshold:
            self._state[route_name] = "open"
            self._half_open_calls.pop(route_name, None)
