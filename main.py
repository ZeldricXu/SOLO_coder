from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.routes import router as api_router
from .core.config import settings

app = FastAPI(
    title=settings.app_name,
    version=settings.version,
    description="APIShield - API安全网关与攻击检测系统",
    docs_url="/docs",
    redoc_url="/redoc"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_router, prefix="/api/v1")


@app.get("/")
async def root():
    return {
        "service": settings.app_name,
        "version": settings.version,
        "status": "running",
        "modules": [
            "audit_chain - 审计日志防篡改模块",
            "data_masking - 动态数据脱敏模块",
            "shamir - 密钥分片管理模块",
            "tee_manager - 可信执行环境模块",
            "federated_learning - 联邦学习协调模块",
            "data_classification - 数据分类分级模块",
            "differential_privacy - 差分隐私注入模块",
            "mpc_coordinator - 安全多方计算模块"
        ],
        "docs": "/docs",
        "api_base": "/api/v1"
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.debug
    )
