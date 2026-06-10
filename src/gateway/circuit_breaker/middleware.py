from typing import Optional
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from gateway.circuit_breaker.breaker import get_circuit_breaker, CircuitState
from gateway.logger import get_logger

logger = get_logger("circuit-breaker-middleware")


class CircuitBreakerMiddleware(BaseHTTPMiddleware):
    def __init__(self, app):
        super().__init__(app)
        self.breaker = get_circuit_breaker()

    async def dispatch(self, request: Request, call_next):
        path = request.url.path

        if self._should_skip(path):
            return await call_next(request)

        route_match = getattr(request.state, "route_match", None)
        if not route_match:
            return await call_next(request)

        route = route_match.route
        if not route.circuit_breaker_enabled:
            return await call_next(request)

        service_name = self._get_service_name(route_match)
        config = route.circuit_breaker_config or {}

        result = await self.breaker.check(service_name, config)

        if not result.allowed:
            logger.warning(
                "Circuit breaker blocked request",
                service_name=service_name,
                state=result.state.value,
                retry_after=result.retry_after,
                request_id=getattr(request.state, "request_id", ""),
            )

            request.state.circuit_broken = True

            if result.fallback_response:
                return JSONResponse(
                    status_code=200,
                    content=result.fallback_response,
                    headers={
                        "X-Circuit-State": result.state.value,
                        "X-Circuit-Fallback": "static",
                    },
                )
            elif result.fallback_target:
                request.state.fallback_target = result.fallback_target
                return await call_next(request)
            else:
                return JSONResponse(
                    status_code=503,
                    content={
                        "error": {
                            "code": 503,
                            "message": "Service Unavailable",
                            "detail": "Service is temporarily unavailable due to high failure rate.",
                        }
                    },
                    headers={
                        "Retry-After": str(result.retry_after),
                        "X-Circuit-State": result.state.value,
                    },
                )

        request.state.circuit_state = result.state
        request.state.circuit_service_name = service_name

        start_time = getattr(request.state, "start_time", __import__("time").time())

        try:
            response = await call_next(request)

            latency = __import__("time").time() - start_time
            is_slow = latency > self.breaker.cb_settings.slow_request_duration

            if 200 <= response.status_code < 500 and not is_slow:
                await self.breaker.record_success(service_name, latency)
            else:
                await self.breaker.record_failure(service_name, latency, is_slow=is_slow)

            response.headers["X-Circuit-State"] = result.state.value
            response.headers["X-Circuit-Latency"] = f"{latency * 1000:.2f}ms"

            return response

        except Exception as e:
            latency = __import__("time").time() - start_time
            await self.breaker.record_failure(service_name, latency)

            logger.error(
                "Request failed, recorded as circuit failure",
                service_name=service_name,
                error=str(e),
                request_id=getattr(request.state, "request_id", ""),
            )
            raise

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

    def _get_service_name(self, route_match) -> str:
        route = route_match.route
        target = route_match.target

        route_name = route.name
        target_host = target.url.replace("http://", "").replace("https://", "").split("/")[0]

        return f"{route_name}:{target_host}"
