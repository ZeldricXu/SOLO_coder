from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager

from app.core.config import settings
from app.core.database import init_db
from app.modules.model_manager import model_manager
from app.api.routers import classify, model, export


@asynccontextmanager
async def lifespan(app: FastAPI):
    print("正在初始化 TextClassifier 服务...")

    print("初始化数据库...")
    init_db()

    print("初始化模型管理器...")
    model_manager.initialize_default_model()

    print("TextClassifier 服务初始化完成！")

    yield

    print("TextClassifier 服务正在关闭...")


app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="一个基于机器学习的文本分类服务，支持多标签文本分类、情感分析、关键词提取以及模型训练与评估功能。",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    return JSONResponse(
        status_code=500,
        content={
            "code": 500,
            "message": f"服务器内部错误: {str(exc)}"
        }
    )


app.include_router(
    classify.router,
    prefix=f"{settings.API_V1_STR}/classify",
    tags=["分类服务"]
)

app.include_router(
    model.router,
    prefix=f"{settings.API_V1_STR}/model",
    tags=["模型管理"]
)

app.include_router(
    export.router,
    prefix=f"{settings.API_V1_STR}/export",
    tags=["导出服务"]
)


@app.get("/")
async def root():
    return {
        "name": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "status": "running",
        "api_docs": "/docs"
    }


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "timestamp": __import__('datetime').datetime.now().isoformat()
    }


@app.get("/api/v1/info")
async def get_info():
    active_model = model_manager.get_active_model()
    return {
        "name": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "api_version": settings.API_V1_STR,
        "active_model": {
            "model_id": active_model["model_id"] if active_model else None,
            "version": active_model["version"] if active_model else None,
            "labels": active_model["labels"] if active_model else settings.DEFAULT_LABELS
        },
        "default_confidence_threshold": settings.DEFAULT_CONFIDENCE_THRESHOLD,
        "default_model_version": settings.DEFAULT_MODEL_VERSION
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True
    )
