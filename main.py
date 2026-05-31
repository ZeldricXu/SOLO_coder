from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from config.settings import settings
from infrastructure.logging.logger import get_logger
from application.container import lifespan

from interfaces.api.v1.device_routes import router as device_router
from interfaces.api.v1.telemetry_routes import router as telemetry_router
from interfaces.api.v1.inference_routes import router as inference_router
from interfaces.api.v1.ota_routes import router as ota_router

logger = get_logger(__name__)


def create_app() -> FastAPI:
    app = FastAPI(
        title=settings.app_name,
        description="Industrial IoT Edge Computing Platform",
        version=settings.app_version,
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    api_prefix = "/api/v1"
    app.include_router(device_router, prefix=api_prefix)
    app.include_router(telemetry_router, prefix=api_prefix)
    app.include_router(inference_router, prefix=api_prefix)
    app.include_router(ota_router, prefix=api_prefix)

    @app.get("/health")
    async def health_check():
        return {
            "status": "healthy",
            "app": settings.app_name,
            "version": settings.app_version,
        }

    @app.get("/")
    async def root():
        return {
            "message": f"Welcome to {settings.app_name}",
            "version": settings.app_version,
            "docs": "/docs",
            "api_prefix": "/api/v1",
        }

    logger.info(f"Application created: {settings.app_name} v{settings.app_version}")
    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    logger.info(f"Starting server on {settings.api_host}:{settings.api_port}")
    uvicorn.run(
        "main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.debug,
        log_level="info",
    )
