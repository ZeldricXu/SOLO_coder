import uuid
import time
import asyncio
import sys
import os
from typing import Optional

from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.responses import JSONResponse, Response, RedirectResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import StreamingResponse

from gateway.config import get_settings
from gateway.logger import setup_logging, get_logger
from gateway.db import init_db, init_redis, init_clickhouse, close_redis, close_clickhouse
from gateway.routing import get_router, get_proxy_client, get_route_watcher, convert_to_starlette_response
from gateway.auth import AuthMiddleware
from gateway.rate_limit import get_rate_limiter, RateLimitMiddleware
from gateway.circuit_breaker import CircuitBreakerMiddleware
from gateway.transform import TransformMiddleware, CORSMiddleware
from gateway.analytics import get_analytics_collector, AnalyticsMiddleware
from gateway.developer_portal import portal_router

setup_logging()
logger = get_logger("main")
settings = get_settings()

app = FastAPI(
    title="API Gateway",
    description="Unified API Gateway Service",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
)

app.add_middleware(CORSMiddleware)


class RequestIDMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        request.state.request_id = request_id
        request.state.start_time = time.time()
        request.state.user = None
        request.state.is_authenticated = False
        request.state.rate_limited = False
        request.state.circuit_broken = False
        request.state.upstream_latency = 0

        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id

        total_latency = int((time.time() - request.state.start_time) * 1000)
        response.headers["X-Latency"] = f"{total_latency}ms"

        return response


class RouteMatchingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        path = request.url.path

        skip_paths = [
            "/health",
            "/metrics",
            "/docs",
            "/openapi.json",
            "/redoc",
            "/api/portal",
            "/portal",
            "/static",
        ]
        if any(path.startswith(p) for p in skip_paths):
            return await call_next(request)

        router = get_router()
        user_id = None

        user = getattr(request.state, "user", None)
        if user and isinstance(user, dict):
            user_id = user.get("user_id")

        route_match = await router.match(path, request.method, user_id)

        if not route_match:
            logger.warning("No route matched", path=path, method=request.method,
                          request_id=request.state.request_id)
            return JSONResponse(
                status_code=404,
                content={
                    "error": {
                        "code": "NOT_FOUND",
                        "status": 404,
                        "message": "Not Found",
                        "detail": f"No route found for {request.method} {path}",
                        "request_id": request.state.request_id,
                    }
                },
            )

        request.state.route_match = route_match

        return await call_next(request)


class ProxyMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        route_match = getattr(request.state, "route_match", None)
        if not route_match:
            return await call_next(request)

        fallback_target = getattr(request.state, "fallback_target", None)
        if fallback_target:
            route_match.target.url = fallback_target

        proxy_client = get_proxy_client()

        modified_headers = getattr(request.state, "modified_headers", None)
        modified_body = getattr(request.state, "modified_body", None)
        modified_query = getattr(request.state, "modified_query", None)

        if modified_query and request.url.query:
            from urllib.parse import urlparse, parse_qs, urlencode
            parsed = urlparse(str(request.url))
            parsed = parsed._replace(query=modified_query)
            request._url = parsed.geturl()

        response, upstream_latency = await proxy_client.forward(
            request,
            route_match,
            modified_headers=modified_headers,
            modified_body=modified_body,
        )

        request.state.upstream_latency = upstream_latency

        return convert_to_starlette_response(response)


app.add_middleware(RequestIDMiddleware)
app.add_middleware(AuthMiddleware)
app.add_middleware(RouteMatchingMiddleware)
app.add_middleware(RateLimitMiddleware)
app.add_middleware(CircuitBreakerMiddleware)
app.add_middleware(TransformMiddleware)
app.add_middleware(AnalyticsMiddleware)
app.add_middleware(ProxyMiddleware)

app.include_router(portal_router)

app.mount("/static", StaticFiles(directory=os.path.join(os.path.dirname(__file__), "static")), name="static")


@app.get("/", include_in_schema=False)
async def root():
    return RedirectResponse(url="/portal/")


@app.get("/portal/", include_in_schema=False)
async def portal():
    from fastapi.responses import HTMLResponse
    html_path = os.path.join(os.path.dirname(__file__), "static", "index.html")
    with open(html_path, "r") as f:
        return HTMLResponse(content=f.read())


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "service": "api-gateway",
        "version": "1.0.0",
        "timestamp": time.time(),
    }


@app.get("/metrics")
async def metrics():
    from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST,
    )


@app.on_event("startup")
async def startup_event():
    logger.info("Starting API Gateway...")

    try:
        await init_db()
        logger.info("Database initialized")
    except Exception as e:
        logger.error("Failed to initialize database", error=str(e))

    try:
        await init_redis()
        logger.info("Redis initialized")
    except Exception as e:
        logger.error("Failed to initialize Redis", error=str(e))

    try:
        await init_clickhouse()
        logger.info("ClickHouse initialized")
    except Exception as e:
        logger.error("Failed to initialize ClickHouse", error=str(e))

    try:
        rate_limiter = get_rate_limiter()
        await rate_limiter.init_script()
        logger.info("Rate limiter initialized")
    except Exception as e:
        logger.error("Failed to initialize rate limiter", error=str(e))

    try:
        route_watcher = get_route_watcher()
        await route_watcher.start()
        logger.info("Route watcher started")
    except Exception as e:
        logger.error("Failed to start route watcher", error=str(e))

    try:
        analytics_collector = get_analytics_collector()
        await analytics_collector.start()
        logger.info("Analytics collector started")
    except Exception as e:
        logger.error("Failed to start analytics collector", error=str(e))

    logger.info("API Gateway started successfully",
                host=settings.gateway.host,
                port=settings.gateway.port)


@app.on_event("shutdown")
async def shutdown_event():
    logger.info("Shutting down API Gateway...")

    try:
        route_watcher = get_route_watcher()
        await route_watcher.stop()
        logger.info("Route watcher stopped")
    except Exception as e:
        logger.error("Error stopping route watcher", error=str(e))

    try:
        analytics_collector = get_analytics_collector()
        await analytics_collector.stop()
        logger.info("Analytics collector stopped")
    except Exception as e:
        logger.error("Error stopping analytics collector", error=str(e))

    try:
        proxy_client = get_proxy_client()
        await proxy_client.close()
        logger.info("Proxy client closed")
    except Exception as e:
        logger.error("Error closing proxy client", error=str(e))

    try:
        await close_redis()
        logger.info("Redis connections closed")
    except Exception as e:
        logger.error("Error closing Redis connections", error=str(e))

    try:
        await close_clickhouse()
        logger.info("ClickHouse connection closed")
    except Exception as e:
        logger.error("Error closing ClickHouse connection", error=str(e))

    logger.info("API Gateway shutdown complete")


def main():
    import uvicorn

    try:
        import uvloop
        asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
        logger.info("Using uvloop for async IO")
    except ImportError:
        pass

    uvicorn.run(
        "gateway.main:app",
        host=settings.gateway.host,
        port=settings.gateway.port,
        workers=settings.gateway.workers,
        log_level=settings.gateway.log_level,
        access_log=False,
        reload=False,
    )


if __name__ == "__main__":
    main()
