from __future__ import annotations

import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, Optional, Protocol

from top.core.models import BaseModel


class RequestContext(BaseModel):
    request_id: str
    client_ip: Optional[str] = None
    user_agent: Optional[str] = None
    path: str
    method: str
    timestamp: float = field(default_factory=time.time)
    principal: Optional[Any] = None
    attributes: Dict[str, Any] = field(default_factory=dict)


class Middleware(ABC):
    @abstractmethod
    async def process(
        self,
        context: RequestContext,
        next_handler: Callable[[RequestContext], Awaitable[Any]],
    ) -> Any:
        pass


class CorrelationIDMiddleware(Middleware):
    def __init__(
        self,
        header_name: str = "X-Request-ID",
        response_header: str = "X-Request-ID",
    ):
        self._header_name = header_name
        self._response_header = response_header

    async def process(
        self,
        context: RequestContext,
        next_handler: Callable[[RequestContext], Awaitable[Any]],
    ) -> Any:
        if "request_id" in context.attributes:
            context.request_id = context.attributes["request_id"]
        else:
            context.request_id = str(uuid.uuid4())

        response = await next_handler(context)

        if isinstance(response, dict):
            headers = response.get("headers", {})
            headers[self._response_header] = context.request_id
            response["headers"] = headers

        return response


class AuthMiddleware(Middleware):
    def __init__(
        self,
        auth_provider,
        exempt_paths: Optional[list[str]] = None,
        include_paths: Optional[list[str]] = None,
    ):
        self._auth_provider = auth_provider
        self._exempt_paths = exempt_paths or []
        self._include_paths = include_paths

    def _path_matches(self, path: str, patterns: list[str]) -> bool:
        import fnmatch
        for pattern in patterns:
            if fnmatch.fnmatch(path, pattern):
                return True
        return False

    def _should_authenticate(self, path: str) -> bool:
        if self._path_matches(path, self._exempt_paths):
            return False
        if self._include_paths:
            return self._path_matches(path, self._include_paths)
        return True

    async def process(
        self,
        context: RequestContext,
        next_handler: Callable[[RequestContext], Awaitable[Any]],
    ) -> Any:
        if not self._should_authenticate(context.path):
            return await next_handler(context)

        headers = context.attributes.get("headers", {})
        credentials = self._auth_provider.extract_credentials(headers)

        if not credentials:
            return self._auth_failed_response("Missing credentials")

        result = self._auth_provider.authenticate(credentials)

        if not result.authenticated:
            return self._auth_failed_response(result.error, result.challenge)

        context.principal = result.principal
        context.attributes["auth_method"] = result.auth_method.value

        return await next_handler(context)

    def _auth_failed_response(
        self,
        error: str,
        challenge: Optional[str] = None,
    ) -> Dict[str, Any]:
        response = {
            "status_code": 401,
            "body": {
                "code": 401,
                "message": "Unauthorized",
                "details": error,
            },
            "headers": {},
        }
        if challenge:
            response["headers"]["WWW-Authenticate"] = challenge
        return response


class RateLimitMiddleware(Middleware):
    def __init__(
        self,
        rate_limiter,
        key_extractor: Optional[Callable[[RequestContext], str]] = None,
    ):
        self._rate_limiter = rate_limiter
        self._key_extractor = key_extractor or self._default_key_extractor

    @staticmethod
    def _default_key_extractor(context: RequestContext) -> str:
        principal = context.principal
        if principal and hasattr(principal, "user_id"):
            return f"user:{principal.user_id}"
        return context.client_ip or "anonymous"

    async def process(
        self,
        context: RequestContext,
        next_handler: Callable[[RequestContext], Awaitable[Any]],
    ) -> Any:
        key = self._key_extractor(context)
        result = self._rate_limiter.check(key, resource=context.path)

        context.attributes["rate_limit"] = {
            "remaining": result.remaining,
            "limit": result.limit,
        }

        if not result.allowed:
            return {
                "status_code": 429,
                "body": {
                    "code": 429,
                    "message": "Too Many Requests",
                    "details": result.reason or "Rate limit exceeded",
                    "retry_after": result.retry_after,
                },
                "headers": {
                    "X-RateLimit-Limit": str(result.limit),
                    "X-RateLimit-Remaining": str(result.remaining),
                    "Retry-After": str(int(result.retry_after)),
                },
            }

        response = await next_handler(context)

        if isinstance(response, dict):
            headers = response.get("headers", {})
            headers["X-RateLimit-Limit"] = str(result.limit)
            headers["X-RateLimit-Remaining"] = str(result.remaining)
            response["headers"] = headers

        return response


class CachingMiddleware(Middleware):
    def __init__(
        self,
        ttl: int = 60,
        max_cache_size: int = 1000,
        key_generator: Optional[Callable[[RequestContext], str]] = None,
    ):
        self._ttl = ttl
        self._max_cache_size = max_cache_size
        self._key_generator = key_generator or self._default_key_generator
        self._cache: Dict[str, Dict[str, Any]] = {}
        self._access_order: list[str] = []

    @staticmethod
    def _default_key_generator(context: RequestContext) -> str:
        return f"{context.method}:{context.path}"

    def _evict_if_needed(self) -> None:
        while len(self._cache) >= self._max_cache_size:
            if self._access_order:
                oldest_key = self._access_order.pop(0)
                self._cache.pop(oldest_key, None)
            else:
                break

    def _get_cached(self, key: str) -> Optional[Any]:
        if key not in self._cache:
            return None

        entry = self._cache[key]
        if time.time() - entry["timestamp"] > self._ttl:
            del self._cache[key]
            return None

        if key in self._access_order:
            self._access_order.remove(key)
        self._access_order.append(key)

        return entry["value"]

    def _cache_response(self, key: str, value: Any) -> None:
        self._evict_if_needed()

        self._cache[key] = {
            "value": value,
            "timestamp": time.time(),
        }

        if key in self._access_order:
            self._access_order.remove(key)
        self._access_order.append(key)

    async def process(
        self,
        context: RequestContext,
        next_handler: Callable[[RequestContext], Awaitable[Any]],
    ) -> Any:
        if context.method != "GET":
            return await next_handler(context)

        cache_key = self._key_generator(context)
        cached = self._get_cached(cache_key)

        if cached is not None:
            context.attributes["cache_hit"] = True
            if isinstance(cached, dict):
                headers = cached.get("headers", {})
                headers["X-Cache"] = "HIT"
                cached["headers"] = headers
            return cached

        context.attributes["cache_hit"] = False
        response = await next_handler(context)

        if (
            isinstance(response, dict)
            and response.get("status_code", 200) == 200
        ):
            self._cache_response(cache_key, response)
            headers = response.get("headers", {})
            headers["X-Cache"] = "MISS"
            response["headers"] = headers

        return response


class APIGatewayMiddleware:
    def __init__(self, middlewares: Optional[list[Middleware]] = None):
        self._middlewares = middlewares or []

    def add_middleware(self, middleware: Middleware) -> None:
        self._middlewares.append(middleware)

    async def process_request(
        self,
        context: RequestContext,
        final_handler: Callable[[RequestContext], Awaitable[Any]],
    ) -> Any:
        async def build_chain(
            index: int,
        ) -> Callable[[RequestContext], Awaitable[Any]]:
            if index >= len(self._middlewares):
                return final_handler

            async def handler(ctx: RequestContext) -> Any:
                next_handler = await build_chain(index + 1)
                return await self._middlewares[index].process(ctx, next_handler)

            return handler

        chain = await build_chain(0)
        return await chain(context)


def create_api_gateway(
    auth_provider=None,
    rate_limiter=None,
    exempt_auth_paths: Optional[list[str]] = None,
    use_caching: bool = False,
    cache_ttl: int = 60,
) -> APIGatewayMiddleware:
    gateway = APIGatewayMiddleware()

    gateway.add_middleware(CorrelationIDMiddleware())

    if auth_provider:
        gateway.add_middleware(
            AuthMiddleware(
                auth_provider=auth_provider,
                exempt_paths=exempt_auth_paths or [],
            )
        )

    if rate_limiter:
        gateway.add_middleware(RateLimitMiddleware(rate_limiter=rate_limiter))

    if use_caching:
        gateway.add_middleware(CachingMiddleware(ttl=cache_ttl))

    return gateway
