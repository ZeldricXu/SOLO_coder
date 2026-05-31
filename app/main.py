from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from app.core.config import settings
from app.core.logger import logger
from app.api import api_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("正在启动隐私计算与数据安全服务...")
    settings.ensure_dirs()
    logger.info("服务初始化完成")
    yield
    logger.info("服务正在关闭...")


app = FastAPI(
    title="隐私计算与数据安全服务",
    description="实现数据迁移与Schema版本控制、存储管理、数据分类分级、差分隐私、审计日志等功能的平台级服务",
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

app.include_router(api_router)


@app.get("/")
async def root():
    return {
        "name": "隐私计算与数据安全服务",
        "version": "1.0.0",
        "status": "running",
        "docs": "/docs",
        "api_prefix": "/api/v1"
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True
    )
