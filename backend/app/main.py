from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from app.core.config import settings
from app.api.v1 import api_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时的初始化代码
    yield
    # 关闭时的清理代码


app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="InkSoul AI 智能小说创作管理平台 - 后端 API",
    openapi_url=f"{settings.API_V1_STR}/openapi.json",
    lifespan=lifespan,
)

# 配置 CORS
if settings.BACKEND_CORS_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[str(origin) for origin in settings.BACKEND_CORS_ORIGINS],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

# 注册路由
app.include_router(api_router, prefix=settings.API_V1_STR)


@app.get("/")
async def root():
    return {"message": "InkSoul AI Backend", "version": settings.VERSION}


@app.get("/health")
async def health_check():
    return {"status": "healthy"}
