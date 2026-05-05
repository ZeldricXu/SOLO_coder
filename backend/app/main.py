from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from app.core.config import settings
from app.core.database import init_db
from app.core.di import get_container, DIContainer
from app.core.di_provider import register_services
from app.routers import upload, chat, health


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    
    container = get_container()
    register_services(container)
    
    yield


app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    openapi_url=f"{settings.API_V1_STR}/openapi.json",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["*"],
)

app.include_router(
    health.router,
    prefix=settings.API_V1_STR,
    tags=["健康检查"]
)

app.include_router(
    upload.router,
    prefix=settings.API_V1_STR,
    tags=["文档上传"]
)

app.include_router(
    chat.router,
    prefix=settings.API_V1_STR,
    tags=["对话交互"]
)


@app.get("/")
async def root():
    return {
        "name": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "docs": "/docs",
        "openapi": f"{settings.API_V1_STR}/openapi.json"
    }
