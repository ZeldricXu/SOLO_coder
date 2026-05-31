import time
from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response, JSONResponse
from starlette.middleware.cors import CORSMiddleware

from app.logging import LogContext, get_logger
from app.config import settings
from app.api_gateway.auth import RateLimiter
from app.exceptions import RateLimitError

logger = get_logger(__name__)


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = request.headers.get("X-Request-ID")
        request_id = LogContext.set_request_id(request_id)

        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        return response


class RequestLoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        start_time = time.time()

        client_ip = request.client.host if request.client else "unknown"
        method = request.method
        path = request.url.path
        query_params = str(request.query_params) if request.query_params else None

        logger.info(
            "Request started",
            method=method,
            path=path,
            client_ip=client_ip,
            query_params=query_params,
            user_agent=request.headers.get("user-agent"),
        )

        try:
            response = await call_next(request)
            process_time = (time.time() - start_time) * 1000

            logger.info(
                "Request completed",
                method=method,
                path=path,
                status_code=response.status_code,
                process_time_ms=round(process_time, 2),
                client_ip=client_ip,
            )

            response.headers["X-Process-Time"] = str(round(process_time, 2))
            return response

        except Exception as e:
            process_time = (time.time() - start_time) * 1000
            logger.error(
                "Request failed",
                method=method,
                path=path,
                error=str(e),
                process_time_ms=round(process_time, 2),
                client_ip=client_ip,
                exc_info=True,
            )
            raise


class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app):
        super().__init__(app)
        self.rate_limiter = RateLimiter()
        self._rate_limit_tier_config = {
            "basic": {"max_requests": 100, "window_seconds": 60},
            "premium": {"max_requests": 1000, "window_seconds": 60},
            "enterprise": {"max_requests": 10000, "window_seconds": 60},
        }

    async def dispatch(self, request: Request, call_next):
        client_ip = request.client.host if request.client else "unknown"
        auth_header = request.headers.get("Authorization")

        identifier = client_ip
        tier = "basic"

        if auth_header and auth_header.startswith("Bearer "):
            token = auth_header[7:]
            try:
                from app.utils import decode_access_token

                payload = decode_access_token(token)
                identifier = payload.get("sub", client_ip)
                tier = payload.get("tier", "basic")
            except Exception:
                pass

        tier_config = self._rate_limit_tier_config.get(tier, self._rate_limit_tier_config["basic"])

        allowed = await self.rate_limiter.check_rate_limit(
            identifier,
            max_requests=tier_config["max_requests"],
            window_seconds=tier_config["window_seconds"],
        )

        if not allowed:
            remaining = self.rate_limiter.get_remaining_requests(
                identifier,
                max_requests=tier_config["max_requests"],
                window_seconds=tier_config["window_seconds"],
            )
            return JSONResponse(
                status_code=429,
                content={
                    "code": 429,
                    "message": "Rate limit exceeded",
                    "details": {
                        "max_requests": tier_config["max_requests"],
                        "window_seconds": tier_config["window_seconds"],
                        "remaining": remaining,
                    },
                    "request_id": LogContext.get_request_id(),
                },
                headers={
                    "X-RateLimit-Limit": str(tier_config["max_requests"]),
                    "X-RateLimit-Remaining": str(remaining),
                    "X-RateLimit-Window": str(tier_config["window_seconds"]),
                },
            )

        response = await call_next(request)

        remaining = self.rate_limiter.get_remaining_requests(
            identifier,
            max_requests=tier_config["max_requests"],
            window_seconds=tier_config["window_seconds"],
        )
        response.headers["X-RateLimit-Limit"] = str(tier_config["max_requests"])
        response.headers["X-RateLimit-Remaining"] = str(remaining)
        response.headers["X-RateLimit-Window"] = str(tier_config["window_seconds"])

        return response


def setup_cors(app):
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
