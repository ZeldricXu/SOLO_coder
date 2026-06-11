from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.openapi.utils import get_openapi
from contextlib import asynccontextmanager

from app.core.config import settings
from app.core.logging import configure_logging, get_logger
from app.core.middleware import RequestIDMiddleware, AuditMiddleware, LoggingMiddleware
from app.routers import (
    auth,
    users,
    roles,
    sku,
    attribute,
    product,
    warehouse,
    inventory,
    purchase_order,
    approval,
    alert,
    replenishment,
    batch,
    serial,
    document,
    stocktake,
    audit,
    health,
    supplier,
    import_export,
)

configure_logging()
logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting Inventory Management Platform...")
    logger.info(
        "Application info",
        name=settings.APP_NAME,
        version=settings.APP_VERSION,
        environment=settings.APP_ENV,
    )
    yield
    logger.info("Shutting down Inventory Management Platform...")


app = FastAPI(
    title=settings.APP_NAME,
    description="Unified E-commerce Inventory Management Platform - RESTful API",
    version=settings.APP_VERSION,
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.add_middleware(RequestIDMiddleware)
app.add_middleware(LoggingMiddleware)
app.add_middleware(AuditMiddleware)

app.include_router(auth.router, prefix=f"{settings.API_PREFIX}/auth", tags=["Authentication"])
app.include_router(users.router, prefix=f"{settings.API_PREFIX}/users", tags=["Users"])
app.include_router(roles.router, prefix=f"{settings.API_PREFIX}/roles", tags=["Roles & Permissions"])
app.include_router(sku.router, prefix=f"{settings.API_PREFIX}/sku", tags=["SKU Management"])
app.include_router(
    attribute.router, prefix=f"{settings.API_PREFIX}/attributes", tags=["Attribute Management"]
)
app.include_router(
    product.router, prefix=f"{settings.API_PREFIX}/products", tags=["Product Management"]
)
app.include_router(
    supplier.router, prefix=f"{settings.API_PREFIX}/suppliers", tags=["Supplier Management"]
)
app.include_router(warehouse.router, prefix=f"{settings.API_PREFIX}/warehouses", tags=["Warehouses"])
app.include_router(inventory.router, prefix=f"{settings.API_PREFIX}/inventory", tags=["Inventory"])
app.include_router(
    purchase_order.router, prefix=f"{settings.API_PREFIX}/purchase-orders", tags=["Purchase Orders"]
)
app.include_router(
    approval.router, prefix=f"{settings.API_PREFIX}/approvals", tags=["Approval Workflow"]
)
app.include_router(alert.router, prefix=f"{settings.API_PREFIX}/alerts", tags=["Inventory Alerts"])
app.include_router(
    replenishment.router,
    prefix=f"{settings.API_PREFIX}/replenishment",
    tags=["Replenishment Suggestions"],
)
app.include_router(batch.router, prefix=f"{settings.API_PREFIX}/batches", tags=["Batch Tracking"])
app.include_router(
    serial.router, prefix=f"{settings.API_PREFIX}/serials", tags=["Serial Number Tracking"]
)
app.include_router(
    document.router, prefix=f"{settings.API_PREFIX}/documents", tags=["Inventory Documents"]
)
app.include_router(
    stocktake.router, prefix=f"{settings.API_PREFIX}/stocktakes", tags=["Stocktaking & Reconciliation"]
)
app.include_router(audit.router, prefix=f"{settings.API_PREFIX}/audit", tags=["Audit Logs"])
app.include_router(health.router, prefix=f"{settings.API_PREFIX}/health", tags=["Health Check"])
app.include_router(
    import_export.router, prefix=f"{settings.API_PREFIX}", tags=["Import & Export"]
)
app.include_router(
    sync_strategy.router, prefix=f"{settings.API_PREFIX}/warehouses", tags=["Inventory Sync Strategy"]
)


def custom_openapi():
    if app.openapi_schema:
        return app.openapi_schema
    openapi_schema = get_openapi(
        title=settings.APP_NAME,
        version=settings.APP_VERSION,
        description="""
# 电子商务库存管理平台 API

## 项目概述
本平台旨在构建统一的库存数据中枢，替代当前分散于ERP、WMS、OMS三套系统中的异构库存记录，
实现全局库存可视化与自动化管控。

## 技术架构
- **后端框架**: Python 3.12 + FastAPI
- **数据库**: PostgreSQL 16 + SQLAlchemy 2.0
- **异步任务**: Celery + Redis
- **缓存层**: Redis Cluster
- **服务通信**: gRPC

## 功能模块
1. **商品SKU管理与属性配置** - 多规格属性组合自动生成SKU、属性模板继承、SKU生命周期状态机管理
2. **多仓库库存同步引擎** - CDC变更数据捕获、实时同步、冲突检测与解决
3. **采购订单自动生成与审批流** - 安全库存阈值触发、需求预测、多级审批工作流
4. **库存预警与智能补货建议** - 多维度预警规则、季节性趋势分析
5. **批次与序列号追溯跟踪** - 入库批次生成、FIFO/FEFO策略、全生命周期追踪
6. **出入库单据管理** - 五种单据类型、状态流转控制、扫码枪快速录入
7. **库存盘点与差异对账** - 动态盘点计划、移动端适配、差异自动调整
8. **RESTful管理API与Web后台** - Swagger文档、RBAC权限控制、操作审计日志

## 认证说明
所有API接口（除登录、健康检查外）都需要Bearer Token认证。
请先调用 `/api/v1/auth/login` 接口获取访问令牌。
        """,
        routes=app.routes,
    )
    openapi_schema["components"]["securitySchemes"] = {
        "BearerAuth": {
            "type": "http",
            "scheme": "bearer",
            "bearerFormat": "JWT",
            "description": "JWT Authorization header using the Bearer scheme.",
        }
    }
    openapi_schema["security"] = [{"BearerAuth": []}]

    openapi_schema["info"]["contact"] = {
        "name": "Inventory Management Team",
        "email": "tech-support@inventory.com",
    }
    openapi_schema["info"]["license"] = {
        "name": "Commercial",
        "url": "https://inventory.com/license",
    }

    app.openapi_schema = openapi_schema
    return app.openapi_schema


app.openapi = custom_openapi


@app.get("/", tags=["Root"])
async def root():
    return {
        "name": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "environment": settings.APP_ENV,
        "docs": "/docs",
        "redoc": "/redoc",
        "health": f"{settings.API_PREFIX}/health",
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.DEBUG,
        workers=1,
    )
