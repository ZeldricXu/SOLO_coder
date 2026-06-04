from fastapi import FastAPI
from loguru import logger
import sys

from config import settings
from recommendation_engine.api.dependencies import app_lifespan
from recommendation_engine.api.middleware import register_middlewares
from recommendation_engine.api.routers import (
    recommend,
    user_profile,
    content_index,
    collaborative_filter,
    abtest,
    feedback,
    model_serving,
    health,
)

logger.remove()
logger.add(
    sys.stdout,
    level=settings.log_level.upper(),
    format="<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
           "<level>{level: <8}</level> | "
           "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - "
           "<level>{message}</level>",
)
logger.add(
    "./data/logs/recommendation_engine_{time:YYYY-MM-DD}.log",
    level="INFO",
    rotation="00:00",
    retention="30 days",
    compression="zip",
    enqueue=True,
)

app = FastAPI(
    title="Recommendation Engine API",
    description="Enterprise-grade recommendation engine with full pipeline engineering",
    version="1.0.0",
    lifespan=app_lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
)

app.state.service_name = settings.service_name

register_middlewares(app)

app.include_router(health.router)
app.include_router(recommend.router)
app.include_router(user_profile.router)
app.include_router(content_index.router)
app.include_router(collaborative_filter.router)
app.include_router(abtest.router)
app.include_router(feedback.router)
app.include_router(model_serving.router)


@app.get("/")
async def root():
    return {
        "service": settings.service_name,
        "version": "1.0.0",
        "status": "running",
        "docs": "/docs",
    }


if __name__ == "__main__":
    import uvicorn
    import os

    os.makedirs("./data/logs", exist_ok=True)
    os.makedirs("./data/faiss_index", exist_ok=True)
    os.makedirs("./data/iceberg_warehouse/fallback", exist_ok=True)

    uvicorn.run(
        "main:app",
        host=settings.service_host,
        port=settings.service_port,
        log_level=settings.log_level,
        reload=False,
        workers=1,
    )
