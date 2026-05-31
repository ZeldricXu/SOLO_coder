from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI, Request, Response, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes import router as api_router
from app.config.settings import get_settings
from app.data.database import init_db, shutdown_db
from app.monitoring.metrics import get_metrics_collector, MetricType
from app.monitoring.tracing import get_tracer
from app.gateway.middleware import get_gateway


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()

    await init_db()
    metrics = get_metrics_collector()
    metrics.set_gauge("app_startup_time", 1.0)
    metrics.increment_counter("app_starts")

    tracer = get_tracer()
    with tracer.span("app_initialization"):
        pass

    yield

    await shutdown_db()
    tracer.cleanup_old_traces()


settings = get_settings()

app = FastAPI(
    title="DB Pool Platform API",
    description="云原生基础设施平台 - 数据库连接池管理与查询优化",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def request_logging_middleware(request: Request, call_next):
    import time
    gateway = get_gateway()
    tracer = get_tracer()
    metrics = get_metrics_collector()

    start_time = time.perf_counter()
    method = request.method
    path = request.url.path
    client_ip = request.client.host if request.client else "unknown"
    user_agent = request.headers.get("user-agent", "")

    trace_id = gateway.extract_trace_context(dict(request.headers))

    with tracer.span(f"http_{method.lower()}", trace_id=trace_id):
        try:
            response = await call_next(request)
            status_code = response.status_code
        except HTTPException as exc:
            status_code = exc.status_code
            response = JSONResponse(
                status_code=status_code,
                content={"detail": exc.detail}
            )
        except Exception as exc:
            status_code = 500
            response = JSONResponse(
                status_code=500,
                content={"detail": str(exc)}
            )

    end_time = time.perf_counter()
    duration_ms = (end_time - start_time) * 1000

    actual_trace_id = tracer.get_current_trace_id() or trace_id or "unknown"

    from app.gateway.middleware import RequestLogEntry
    from datetime import datetime

    entry = RequestLogEntry(
        timestamp=datetime.utcnow(),
        trace_id=actual_trace_id,
        span_id="middleware",
        method=method,
        path=path,
        status_code=status_code,
        duration_ms=duration_ms,
        client_ip=client_ip,
        user_agent=user_agent
    )
    gateway.request_logger.log_request(entry)

    metrics.increment_counter(
        "http_requests_total",
        labels={"method": method, "path": path, "status": str(status_code)}
    )
    metrics.record_histogram(
        "http_request_duration_ms",
        duration_ms,
        labels={"method": method, "path": path}
    )

    if response is not None and actual_trace_id:
        response.headers["X-Trace-ID"] = actual_trace_id

    return response


@app.get("/", tags=["root"])
async def root():
    return {
        "name": "DB Pool Platform",
        "version": "1.0.0",
        "status": "running",
        "docs": "/docs",
        "openapi": "/openapi.json"
    }


@app.get("/health", tags=["health"])
async def health_check():
    return {
        "status": "healthy",
        "timestamp": __import__("datetime").datetime.utcnow().isoformat(),
        "version": "1.0.0"
    }


@app.get("/ready", tags=["health"])
async def ready_check():
    return {
        "status": "ready",
        "services": {
            "database": "connected",
            "config": "loaded",
            "metrics": "active"
        }
    }


app.include_router(api_router)


def create_app() -> FastAPI:
    return app


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host=settings.app_host,
        port=settings.app_port,
        reload=settings.app_debug
    )
