import time
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from src.api import router
from src.config import get_settings
from src.logging_ import get_logger, setup_logging
from src.utils.errors import TaskOrchestratorError

settings = get_settings()
setup_logging(
    name=settings.APP_NAME,
    level=settings.LOG_LEVEL,
    log_file=f"{settings.LOG_DIR}/app.log",
)
logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    logger.info("Starting Task Orchestrator Service")
    logger.info(f"Environment: {settings.APP_ENV}")
    logger.info(f"Debug mode: {settings.DEBUG}")
    yield
    logger.info("Shutting down Task Orchestrator Service")


app = FastAPI(
    title="Task Orchestrator API",
    description="轻量高效的依赖任务编排中间件",
    version="1.0.0",
    lifespan=lifespan,
    debug=settings.DEBUG,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def add_request_id(request: Request, call_next):
    start_time = time.time()
    response = await call_next(request)
    process_time = (time.time() - start_time) * 1000
    response.headers["X-Process-Time"] = f"{process_time:.2f}ms"
    return response


@app.exception_handler(TaskOrchestratorError)
async def task_orchestrator_error_handler(request: Request, exc: TaskOrchestratorError):
    logger.error(f"Task orchestrator error: {exc.message}", extra=exc.details)
    return JSONResponse(
        status_code=exc.code,
        content={
            "code": exc.code,
            "error": exc.message,
            "details": exc.details,
        },
    )


@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception):
    logger.exception(f"Unhandled exception: {exc}")
    return JSONResponse(
        status_code=500,
        content={
            "code": 500,
            "error": "内部服务器错误",
            "details": {"message": str(exc)},
        },
    )


app.include_router(router)


@app.get("/", include_in_schema=False)
async def root():
    return {
        "name": "Task Orchestrator Service",
        "version": "1.0.0",
        "docs": "/docs",
        "health": "/api/v1/health",
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "src.main:app",
        host=settings.APP_HOST,
        port=settings.APP_PORT,
        reload=settings.DEBUG,
    )
