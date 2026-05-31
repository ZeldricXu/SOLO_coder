from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager
import time

from common.config import settings
from common.logger import get_logger
from common.exceptions import LLMGatewayException
from common.schemas import BaseResponse

from adversarial import router as adversarial_router
from feature_store import router as feature_store_router
from prompt_experiments import router as prompt_experiments_router
from gpu_scheduler import router as gpu_scheduler_router
from evaluation_dashboard import router as evaluation_router
from document_pipeline import router as document_router
from inference_gateway import router as inference_router
from model_registry import router as model_registry_router

logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting LLMGateway Service...")
    logger.info(f"Service Name: {settings.SERVICE_NAME}")
    logger.info(f"Environment: {settings.ENVIRONMENT}")
    yield
    logger.info("Shutting down LLMGateway Service...")


app = FastAPI(
    title="LLMGateway API",
    description="大语言模型推理网关 - 统一接入、智能路由、可观测",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def add_process_time_header(request: Request, call_next):
    start_time = time.time()
    response = await call_next(request)
    process_time = (time.time() - start_time) * 1000
    response.headers["X-Process-Time"] = f"{process_time:.2f}ms"
    return response


@app.exception_handler(LLMGatewayException)
async def llm_gateway_exception_handler(request: Request, exc: LLMGatewayException):
    logger.error(f"API Exception: {exc.message}", extra={"code": exc.code, "details": exc.details})
    return JSONResponse(
        status_code=exc.status_code,
        content=BaseResponse(
            code=exc.code,
            message=exc.message,
            data=None,
            details=exc.details,
        ).model_dump(),
    )


@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception):
    logger.error(f"Unhandled Exception: {str(exc)}", exc_info=True)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=BaseResponse(
            code=500,
            message="内部服务器错误",
            data=None,
        ).model_dump(),
    )


@app.get("/", response_model=BaseResponse[dict])
async def root():
    return BaseResponse(
        data={
            "service": "LLMGateway",
            "version": "1.0.0",
            "status": "running",
            "docs": "/docs",
        },
        message="LLMGateway Service is running",
    )


@app.get("/health", response_model=BaseResponse[dict])
async def health_check():
    return BaseResponse(
        data={
            "status": "healthy",
            "timestamp": time.time(),
        },
        message="OK",
    )


app.include_router(adversarial_router)
app.include_router(feature_store_router)
app.include_router(prompt_experiments_router)
app.include_router(gpu_scheduler_router)
app.include_router(evaluation_router)
app.include_router(document_router)
app.include_router(inference_router)
app.include_router(model_registry_router)


if __name__ == "__main__":
    import uvicorn
    logger.info(f"Starting server on {settings.HOST}:{settings.PORT}")
    uvicorn.run(
        "main:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.ENVIRONMENT == "development",
        workers=settings.WORKERS,
    )
