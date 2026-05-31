from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from contextlib import asynccontextmanager
import time
import logging
from src.core import (
    settings,
    PlatformError,
    get_trace_id,
    set_trace_id,
    ApiResponse,
    get_metrics_collector,
    emit_event,
)
from src.api import (
    document_pipeline_router,
    feature_store_router,
    api_gateway_router,
    scheduler_router,
    model_registry_router,
    evaluation_dashboard_router,
    storage_manager_router,
    gpu_scheduler_router,
    data_access_router,
    notification_router,
)
from src.di import get_container

logging.basicConfig(
    level=logging.INFO if not settings.debug else logging.DEBUG,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Starting {settings.app_name} in {settings.app_env} mode")
    container = get_container()
    _ = container.document_pipeline
    _ = container.feature_store
    _ = container.api_gateway
    _ = container.scheduler
    _ = container.model_registry
    _ = container.evaluation_dashboard
    _ = container.storage_manager
    _ = container.gpu_scheduler
    _ = container.data_access
    _ = container.notification
    logger.info("All services initialized")
    yield
    logger.info("Shutting down application")
    await container.close()
    logger.info("Application shutdown complete")


app = FastAPI(
    title=settings.app_name,
    description="轻量高效的技术中间件平台 - 请求路由与协议转换",
    version="0.1.0",
    lifespan=lifespan,
    debug=settings.debug,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def request_middleware(request: Request, call_next):
    start_time = time.time()
    trace_id = request.headers.get("x-trace-id", get_trace_id())
    set_trace_id(trace_id)

    metrics = get_metrics_collector()
    metrics.increment("http_requests_total")
    timer_id = metrics.start_timer("http_request_duration")

    try:
        response = await call_next(request)
        response.headers["x-trace-id"] = trace_id
        process_time = time.time() - start_time
        response.headers["x-process-time"] = str(process_time)
        metrics.increment(f"http_requests_{response.status_code}")
        return response
    except Exception as e:
        logger.error(f"Unhandled exception: {e}", exc_info=True)
        metrics.increment("http_requests_500")
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content=ApiResponse.error(500, str(e)).model_dump(mode="json"),
            headers={"x-trace-id": trace_id},
        )
    finally:
        metrics.stop_timer(timer_id)


@app.exception_handler(PlatformError)
async def platform_error_handler(request: Request, exc: PlatformError):
    logger.warning(f"Platform error: {exc.code} - {exc.message}")
    get_metrics_collector().increment(f"platform_errors_{exc.code}")
    emit_event("error.platform", {"code": exc.code, "message": exc.message})
    return JSONResponse(
            status_code=exc.code,
            content=ApiResponse.error(exc.code, exc.message, exc.details).model_dump(mode="json"),
        )


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    logger.warning(f"Validation error: {exc.errors()}")
    get_metrics_collector().increment("validation_errors")
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content=ApiResponse.error(422, "参数校验失败", {"errors": exc.errors()}).model_dump(mode="json"),
    )


@app.exception_handler(status.HTTP_404_NOT_FOUND)
async def not_found_handler(request: Request, exc):
    return JSONResponse(
        status_code=status.HTTP_404_NOT_FOUND,
        content=ApiResponse.error(404, "资源不存在").model_dump(mode="json"),
    )


@app.get("/", tags=["Health"])
async def root():
    return ApiResponse.success({
        "app": settings.app_name,
        "version": "0.1.0",
        "status": "running",
        "env": settings.app_env,
    })


@app.get("/health", tags=["Health"])
async def health_check():
    return ApiResponse.success({"status": "healthy", "timestamp": time.time()})


@app.get("/metrics", tags=["Health"])
async def get_metrics():
    metrics = get_metrics_collector()
    return ApiResponse.success(metrics.get_snapshot())


app.include_router(document_pipeline_router)
app.include_router(feature_store_router)
app.include_router(api_gateway_router)
app.include_router(scheduler_router)
app.include_router(model_registry_router)
app.include_router(evaluation_dashboard_router)
app.include_router(storage_manager_router)
app.include_router(gpu_scheduler_router)
app.include_router(data_access_router)
app.include_router(notification_router)

logger.info(f"Application initialized with {len(app.routes)} routes")
