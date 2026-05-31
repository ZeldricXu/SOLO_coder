import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware as FastAPICORSMiddleware

from config import settings
from core import init_db, emit_event, EventTypes
from modules.api_gateway.middleware import RequestTracingMiddleware, RateLimiterMiddleware
from modules.api_gateway.routes import router as api_gateway_router
from modules.edge_rule_engine.routes import router as edge_rule_router
from modules.firmware_ota.routes import router as firmware_ota_router
from modules.scheduler.routes import router as scheduler_router
from modules.device_lifecycle.routes import router as device_router
from modules.storage_manager.routes import router as storage_router
from modules.protocol_adapter.routes import router as protocol_router
from modules.scheduler.engine import scheduler_engine


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    
    if not settings.environment == "testing":
        await scheduler_engine.start()
    
    emit_event(
        EventTypes.TASK_CREATED,
        "system",
        {"message": "Application started", "version": settings.version},
    )
    
    yield
    
    if not settings.environment == "testing":
        await scheduler_engine.stop()
    
    emit_event(
        EventTypes.TASK_COMPLETED,
        "system",
        {"message": "Application shutdown"},
    )


app = FastAPI(
    title=settings.app_name,
    version=settings.version,
    description="IoT Platform with Request Logging and Distributed Tracing",
    lifespan=lifespan,
)

app.add_middleware(RequestTracingMiddleware, service_name="api-gateway")
app.add_middleware(RateLimiterMiddleware, requests_per_minute=1000)
app.add_middleware(
    FastAPICORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_gateway_router)
app.include_router(edge_rule_router)
app.include_router(firmware_ota_router)
app.include_router(scheduler_router)
app.include_router(device_router)
app.include_router(storage_router)
app.include_router(protocol_router)


@app.get("/health")
async def health_check():
    return {
        "code": 200,
        "data": {
            "status": "healthy",
            "version": settings.version,
            "app_name": settings.app_name,
        },
    }


@app.get("/")
async def root():
    return {
        "code": 200,
        "data": {
            "message": f"Welcome to {settings.app_name}",
            "version": settings.version,
            "docs": "/docs",
        },
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.environment == "development",
    )
