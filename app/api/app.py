"""
FastAPI application for the cloud native middleware platform.
"""

import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.logging import get_logger, LoggingManager
from app.api.routes import router
from app.api.routes import (
    scheduler, notifications, monitoring
)


logging_manager = LoggingManager()
logger = get_logger("platform")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Platform starting up")
    
    scheduler.start()
    notifications.start()
    monitoring.start(evaluation_interval_seconds=30.0)
    
    logger.info("Platform startup complete")
    
    try:
        yield
    finally:
        scheduler.stop()
        notifications.stop()
        monitoring.stop()
        
        logging_manager.cleanup_old_logs()
        logger.info("Platform shutdown complete")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Cloud Native Middleware Platform",
        description="Event-driven middleware platform with 10 core modules",
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
    
    app.include_router(router)
    
    return app


app = create_app()
