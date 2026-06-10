from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from gateway.rate_limit.limiter import get_rate_limiter
from gateway.rate_limit.resolvers import get_rate_limit_key_resolver
from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("rate-limit-middleware")


class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app):
        super().__init__(app)
        self.limiter = get_rate_limiter()
        self.settings = get_settings()
        self.rl_settings = self.settings.rate_limit
        if self.rl_settings.multi_dimension_enabled:
            self.key_resolver = get_rate_limit_key_resolver()

    async def dispatch(self, request: Request, call_next):
        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        route_match = getattr(request.state, "route_match", None)
        if not route_match:
            return await call_next(request)

        route = route_match.route
        if not route.rate_limit_enabled:
            return await call_next(request)

        if self.rl_settings.multi_dimension_enabled:
            result = await self._check_multi_dimension(request, route)
        else:
            user_id = self._get_user_id(request)
            api_path = self._normalize_path(path)
            result = await self.limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=route.rate_limit_per_user,
                custom_api_limit=route.rate_limit_per_api,
            )

        response_headers = {
            "X-RateLimit-Limit": str(result.limit),
            "X-RateLimit-Remaining": str(max(0, result.remaining)),
            "X-RateLimit-Used": str(result.total_requests),
        }

        if result.used_burst:
            response_headers["X-RateLimit-Burst"] = "true"

        if hasattr(result, "rate_limit_key") and result.rate_limit_key:
            response_headers["X-RateLimit-Key"] = result.rate_limit_key

        if not result.allowed:
            response_headers["Retry-After"] = str(result.retry_after)

            logger.warning(
                "Rate limit exceeded",
                rate_limit_key=getattr(result, "rate_limit_key", None),
                api_path=path,
                limit=result.limit,
                retry_after=result.retry_after,
                request_id=getattr(request.state, "request_id", ""),
            )

            request.state.rate_limited = True

            return JSONResponse(
                status_code=429,
                content={
                    "error": {
                        "code": 429,
                        "message": "Too Many Requests",
                        "detail": f"Rate limit exceeded. Please retry after {result.retry_after} seconds.",
                        "rate_limit": {
                            "limit": result.limit,
                            "remaining": result.remaining,
                            "retry_after": result.retry_after,
                        },
                    }
                },
                headers=response_headers,
            )

        request.state.rate_limit_result = result

        response = await call_next(request)

        for key, value in response_headers.items():
            response.headers[key] = value

        return response

    async def _check_multi_dimension(self, request: Request, route):
        context = {
            "api_path": self._normalize_path(request.url.path),
            "route": route,
        }
        keys = await self.key_resolver.resolve_keys(request, context)
        return await self.limiter.check_rate_limit_multi_dimension(
            request=request,
            api_path=context["api_path"],
            custom_user_limit=route.rate_limit_per_user,
            custom_api_limit=route.rate_limit_per_api,
        )

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

    def _get_user_id(self, request: Request) -> Optional[str]:
        user = getattr(request.state, "user", None)
        if user and isinstance(user, dict):
            return user.get("user_id") or user.get("api_key_id")

        api_key = request.headers.get("X-API-Key") or request.headers.get("api-key")
        if api_key:
            return f"apikey:{hash(api_key) % 1000000}"

        client_ip = request.client.host if request.client else "unknown"
        return f"ip:{client_ip}"

    def _normalize_path(self, path: str) -> str:
        parts = path.split("/")
        normalized = []
        for part in parts:
            if part and (part.isdigit() or len(part) == 36 and "-" in part):
                normalized.append("{id}")
            else:
                normalized.append(part)
        return "/".join(normalized)
