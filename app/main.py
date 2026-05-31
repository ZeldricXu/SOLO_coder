from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from app.config import settings
from app.database import init_db, async_engine
from app.logger import configure_logging, logger
from app.modules.api_gateway import instance_manager, api_gateway
from app.routers import (
    auth,
    configs,
    devices,
    ota,
    tasks,
    inference,
    notifications,
    processing,
    storage,
    gateway
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    configure_logging()
    logger.info("Starting application", app_name=settings.APP_NAME, version=settings.APP_VERSION)
    
    logger.info("Initializing database...")
    await init_db()
    logger.info("Database initialized successfully")
    
    logger.info("Initializing API Gateway components...")
    
    default_instance_id = f"main_instance_{int(__import__('time').time())}"
    await instance_manager.register_instance(default_instance_id, initial_weight=1.0)
    logger.info("Default instance registered", instance_id=default_instance_id)
    
    api_gateway.autoscaler.start()
    logger.info("Autoscaler started")
    
    logger.info("Application ready")
    
    yield
    
    logger.info("Shutting down application...")
    api_gateway.autoscaler.stop()
    logger.info("Autoscaler stopped")
    
    await async_engine.dispose()
    logger.info("Application shutdown complete")


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    description="Edge Computing & IoT Platform API",
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
async def request_logger(request: Request, call_next):
    import time
    start_time = time.time()
    
    logger.info(
        "Incoming request",
        method=request.method,
        path=request.url.path,
        client_ip=request.client.host if request.client else "unknown"
    )
    
    response = await call_next(request)
    
    process_time = time.time() - start_time
    logger.info(
        "Request completed",
        method=request.method,
        path=request.url.path,
        status_code=response.status_code,
        process_time_ms=round(process_time * 1000, 2)
    )
    
    return response


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(
        "Unhandled exception",
        method=request.method,
        path=request.url.path,
        error=str(exc)
    )
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "code": 500,
            "error": "Internal server error",
            "message": str(exc)
        }
    )


app.include_router(auth.router)
app.include_router(configs.router)
app.include_router(devices.router)
app.include_router(ota.router)
app.include_router(tasks.router)
app.include_router(inference.router)
app.include_router(notifications.router)
app.include_router(processing.router)
app.include_router(storage.router)
app.include_router(gateway.router)


@app.get("/")
async def root():
    return {
        "name": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "status": "running",
        "documentation": "/docs",
        "features": [
            "Device Shadow Async Execution",
            "Multi-level Config Cache",
            "API Gateway Autoscaling"
        ]
    }


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "timestamp": __import__("datetime").datetime.utcnow().isoformat(),
        "version": settings.APP_VERSION
    }


@app.get("/status")
async def status_check():
    return {
        "status": "running",
        "version": settings.APP_VERSION,
        "features": {
            "async_device_shadow": True,
            "multi_level_cache": settings.CACHE_ENABLED,
            "api_gateway_autoscaling": settings.AUTOSCALE_ENABLED
        }
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.DEBUG
    )
