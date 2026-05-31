from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager

from app.config import settings
from app.logging import get_logger, LogContext
from app.api_gateway.middleware import (
    RequestIdMiddleware,
    RequestLoggingMiddleware,
    RateLimitMiddleware,
)
from app.exceptions import (
    PlatformException,
    ValidationError,
    NotFoundError,
    ConflictError,
    AuthenticationError,
    AuthorizationError,
    RateLimitError,
    TransactionFailedError,
    ResourceExhaustedError,
)
from app.database import init_db
from app.api_gateway.router import router as auth_router
from app.feature_store.router import router as feature_router
from app.monitoring.router import router as monitoring_router
from app.gpu_scheduler.router import router as gpu_router
from app.storage.router import router as storage_router
from app.data_access.router import router as data_access_router
from app.prompt_experiment.router import router as prompt_router
from app.core.router import router as core_router

logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(
        "Application starting",
        app_name=settings.app_name,
        environment=settings.environment,
    )

    if settings.auto_migrate:
        try:
            await init_db()
            logger.info("Database initialized")
        except Exception as e:
            logger.warning(
                "Database initialization failed",
                error=str(e),
            )

    yield

    logger.info("Application shutdown")


app = FastAPI(
    title=settings.app_name,
    description="Structured Logging Platform - Feature Store, GPU Scheduler, and more",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.add_middleware(RequestIdMiddleware)
app.add_middleware(RateLimitMiddleware)
app.add_middleware(RequestLoggingMiddleware)


@app.exception_handler(PlatformException)
async def platform_exception_handler(request, exc: PlatformException):
    request_id = LogContext.get_request_id()

    log_level = "warning" if exc.code < 500 else "error"
    log_method = getattr(logger, log_level, logger.warning)

    log_method(
        "Platform exception",
        status_code=exc.code,
        error_code=exc.error_code,
        error_id=exc.error_id,
        message=exc.message,
        request_id=request_id,
        path=request.url.path,
        method=request.method,
    )

    response_content = {
        "code": exc.code,
        "error_code": exc.error_code,
        "error_id": exc.error_id,
        "message": exc.message,
        "details": exc.details,
        "request_id": request_id,
        "timestamp": exc.timestamp,
    }

    if isinstance(exc, TransactionFailedError) and "traceback" in exc.details:
        response_content["details"]["traceback"] = exc.details["traceback"]

    return JSONResponse(
        status_code=exc.code,
        content=response_content,
    )


@app.exception_handler(Exception)
async def general_exception_handler(request, exc):
    request_id = LogContext.get_request_id()
    error_id = f"unhandled_{request_id}"

    logger.error(
        "Unhandled exception",
        error=str(exc),
        exc_info=exc,
        request_id=request_id,
        error_id=error_id,
        path=request.url.path,
        method=request.method,
        error_type=type(exc).__name__,
    )

    return JSONResponse(
        status_code=500,
        content={
            "code": 500,
            "error_code": "ERR_UNHANDLED_EXCEPTION",
            "error_id": error_id,
            "message": "Internal server error",
            "details": {
                "error_type": type(exc).__name__,
                "request_id": request_id,
            },
            "request_id": request_id,
        },
    )


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "app_name": settings.app_name,
        "environment": settings.environment,
        "version": "1.0.0",
    }


@app.get("/")
async def root():
    return {
        "message": "Structured Logging Platform API",
        "docs": "/docs",
        "health": "/health",
    }


app.include_router(auth_router)
app.include_router(feature_router)
app.include_router(monitoring_router)
app.include_router(gpu_router)
app.include_router(storage_router)
app.include_router(data_access_router)
app.include_router(prompt_router)
app.include_router(core_router)

if __name__ == "__main__":
    import uvicorn

    logger.info(
        "Starting server",
        host=settings.server_host,
        port=settings.server_port,
    )

    uvicorn.run(
        "app.main:app",
        host=settings.server_host,
        port=settings.server_port,
        reload=settings.debug,
        log_config=None,
    )
