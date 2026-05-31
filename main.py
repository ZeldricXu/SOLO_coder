from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from src.common.exceptions import InfrastructureError
from src.common.logging_config import setup_logging
from src.common.models import APIResponse
from src.data_access.router import router as data_access_router
from src.api_gateway.router import router as api_gateway_router
from src.document_index.router import router as document_index_router
from src.monitoring.router import router as monitoring_router
from src.contract_testing.router import router as contract_testing_router
from src.storage.router import router as storage_router
from src.config.router import router as config_router
from src.scaffold.router import router as scaffold_router
from src.service_discovery.router import router as service_discovery_router

setup_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting Infrastructure Platform...")
    yield
    logger.info("Shutting down Infrastructure Platform...")


app = FastAPI(
    title="Enterprise Infrastructure Platform",
    description="""
    企业级基础设施平台 - 提供完整的基础设施组件服务。

    ## 功能模块

    - **数据访问模块**: 缓存策略与失效管理
    - **API网关模块**: 认证鉴权与速率限制
    - **内部文档索引模块**: 多源技术文档聚合、全文搜索与权限过滤
    - **监控统计模块**: 告警规则评估与通知
    - **API契约测试模块**: OpenAPI/GraphQL Schema校验、Mock Server自动生成
    - **存储管理模块**: 对象存储适配与元数据索引
    - **配置管理模块**: 多源配置加载与动态更新
    - **项目脚手架生成模块**: 基于模板生成项目骨架
    - **软件目录与发现模块**: 服务/库的元数据注册、检索与依赖关系展示
    """,
    version="1.0.0",
    lifespan=lifespan,
    contact={
        "name": "Infrastructure Team",
        "email": "infra@example.com",
    },
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(InfrastructureError)
async def infrastructure_error_handler(request, exc: InfrastructureError):
    return JSONResponse(
        status_code=exc.status_code,
        content=APIResponse(
            code=exc.status_code,
            message=exc.message,
            error=exc.error_code,
        ).model_dump(),
    )


@app.exception_handler(Exception)
async def general_exception_handler(request, exc: Exception):
    logger.exception(f"Unhandled exception: {exc}")
    return JSONResponse(
        status_code=500,
        content=APIResponse(
            code=500,
            message="Internal server error",
            error="INTERNAL_ERROR",
        ).model_dump(),
    )


@app.get("/", tags=["Root"])
async def root() -> APIResponse:
    """Root endpoint - returns platform information."""
    return APIResponse(data={
        "name": "Enterprise Infrastructure Platform",
        "version": "1.0.0",
        "modules": [
            "data_access",
            "api_gateway",
            "document_index",
            "monitoring",
            "contract_testing",
            "storage",
            "config",
            "scaffold",
            "service_discovery",
        ],
        "docs": "/docs",
        "openapi": "/openapi.json",
    })


@app.get("/health", tags=["Health"])
async def health_check() -> APIResponse:
    """Health check endpoint."""
    return APIResponse(data={
        "status": "healthy",
        "timestamp": None,
    })


@app.get("/api/v1/modules", tags=["Platform"])
async def list_modules() -> APIResponse:
    """List all available modules and their endpoints."""
    modules = [
        {
            "id": "data_access",
            "name": "数据访问模块",
            "description": "缓存策略与失效管理",
            "prefix": "/data-access",
            "endpoints": [
                "GET /caches",
                "GET /caches/{name}",
                "POST /caches/{name}/entry",
                "DELETE /caches/{name}/entry/{key}",
                "POST /caches/{name}/invalidate",
            ],
        },
        {
            "id": "api_gateway",
            "name": "API网关模块",
            "description": "认证鉴权与速率限制",
            "prefix": "/gateway",
            "endpoints": [
                "POST /auth/login",
                "POST /auth/register",
                "GET /auth/verify",
                "GET /rate-limit/{key}",
            ],
        },
        {
            "id": "document_index",
            "name": "内部文档索引模块",
            "description": "多源技术文档聚合、全文搜索与权限过滤",
            "prefix": "/documents",
            "endpoints": [
                "GET /",
                "POST /",
                "GET /{doc_id}",
                "DELETE /{doc_id}",
                "GET /search",
                "POST /sync",
            ],
        },
        {
            "id": "monitoring",
            "name": "监控统计模块",
            "description": "告警规则评估与通知",
            "prefix": "/monitoring",
            "endpoints": [
                "GET /alert-rules",
                "POST /alert-rules",
                "POST /metrics/record",
                "POST /alerts/evaluate",
                "GET /alerts",
            ],
        },
        {
            "id": "contract_testing",
            "name": "API契约测试模块",
            "description": "OpenAPI/GraphQL Schema校验、Mock Server自动生成",
            "prefix": "/contract",
            "endpoints": [
                "POST /schemas",
                "GET /schemas",
                "POST /schemas/{schema_id}/validate",
                "POST /mock-servers",
                "GET /mock-servers",
            ],
        },
        {
            "id": "storage",
            "name": "存储管理模块",
            "description": "对象存储适配与元数据索引",
            "prefix": "/storage",
            "endpoints": [
                "POST /upload",
                "GET /download/{object_id}",
                "DELETE /objects/{object_id}",
                "GET /search",
            ],
        },
        {
            "id": "config",
            "name": "配置管理模块",
            "description": "多源配置加载与动态更新",
            "prefix": "/config",
            "endpoints": [
                "GET /",
                "GET /{key}",
                "PUT /{key}",
                "DELETE /{key}",
                "GET /sources",
                "GET /snapshots",
            ],
        },
        {
            "id": "scaffold",
            "name": "项目脚手架生成模块",
            "description": "基于模板生成项目骨架",
            "prefix": "/scaffold",
            "endpoints": [
                "GET /templates",
                "GET /templates/{template_id}",
                "POST /generate",
            ],
        },
        {
            "id": "service_discovery",
            "name": "软件目录与发现模块",
            "description": "服务/库的元数据注册、检索与依赖关系展示",
            "prefix": "/discovery",
            "endpoints": [
                "GET /services",
                "POST /services",
                "GET /services/{service_id}",
                "DELETE /services/{service_id}",
                "GET /search",
                "GET /graph",
            ],
        },
    ]
    return APIResponse(data=modules)


app.include_router(data_access_router, prefix="/api/v1")
app.include_router(api_gateway_router, prefix="/api/v1")
app.include_router(document_index_router, prefix="/api/v1")
app.include_router(monitoring_router, prefix="/api/v1")
app.include_router(contract_testing_router, prefix="/api/v1")
app.include_router(storage_router, prefix="/api/v1")
app.include_router(config_router, prefix="/api/v1")
app.include_router(scaffold_router, prefix="/api/v1")
app.include_router(service_discovery_router, prefix="/api/v1")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
    )
