import logging
import traceback
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from core.settings import get_settings
from core.database import Base, engine, get_db
from core.middleware import (
    TraceIDMiddleware,
    MetricsMiddleware,
    ErrorHandlerMiddleware,
)
from core.exceptions import BaseAppException, ValidationError
from core.utils import generate_id, utc_now

from modules.ticket_assignment import router as ticket_assignment_router
from modules.metering_billing import router as metering_billing_router
from modules.sla_monitor import router as sla_monitor_router
from modules.skill_graph import router as skill_graph_router
from modules.multitenant import router as multitenant_router
from modules.workflow_designer import router as workflow_designer_router
from modules.document_diff import router as document_diff_router
from modules.approval_engine import router as approval_engine_router

from models.entity import Entity, EntityCreate, EntityResponse, EntityStatus, EntityType
from models.config import ConfigDefinition, ConfigCreate, ConfigResponse
from models.run_instance import RunInstance, RunInstanceCreate, RunInstanceResponse
from models.snapshot import (
    MetricsSnapshot as Snapshot,
    MetricsSnapshotCreate as SnapshotCreate,
    MetricsSnapshotResponse as SnapshotResponse,
)

settings = get_settings()

logging.basicConfig(level=settings.log_level, format=settings.log_format)
logger = logging.getLogger(__name__)


def create_app() -> FastAPI:
    app = FastAPI(
        title="工单智能分配系统 API",
        description="基于技能匹配与负载均衡的工单路由分配系统",
        version=settings.app_version,
        debug=settings.app_debug,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.add_middleware(TraceIDMiddleware)
    app.add_middleware(MetricsMiddleware)
    app.add_middleware(ErrorHandlerMiddleware)

    @app.exception_handler(BaseAppException)
    async def app_exception_handler(request: Request, exc: BaseAppException) -> JSONResponse:
        trace_id = getattr(request.state, "trace_id", generate_id("trace"))
        logger.warning(
            f"[{trace_id}] Application exception: {exc.status_code} - {exc.message}"
        )
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "code": exc.status_code,
                "message": exc.message,
                "details": exc.details,
                "trace_id": trace_id,
                "timestamp": utc_now().isoformat(),
            },
        )

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
        trace_id = getattr(request.state, "trace_id", generate_id("trace"))
        logger.warning(f"[{trace_id}] HTTP exception: {exc.status_code} - {exc.detail}")
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "code": exc.status_code,
                "message": exc.detail,
                "trace_id": trace_id,
                "timestamp": utc_now().isoformat(),
            },
        )

    @app.exception_handler(Exception)
    async def general_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        trace_id = getattr(request.state, "trace_id", generate_id("trace"))
        logger.error(f"[{trace_id}] Unhandled exception: {str(exc)}")
        logger.error(traceback.format_exc())
        return JSONResponse(
            status_code=500,
            content={
                "code": 500,
                "message": "内部处理错误",
                "error": str(exc),
                "trace_id": trace_id,
                "timestamp": utc_now().isoformat(),
            },
        )

    @app.on_event("startup")
    async def startup_event():
        logger.info("Starting application...")
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        logger.info("Database tables initialized")

    @app.on_event("shutdown")
    async def shutdown_event():
        logger.info("Shutting down application...")
        await engine.dispose()

    @app.get("/", tags=["系统"])
    async def root():
        return {
            "code": 200,
            "data": {
                "name": "工单智能分配系统",
                "version": settings.app_version,
                "env": settings.app_env,
                "status": "running",
                "timestamp": utc_now().isoformat(),
            },
            "message": "服务运行正常",
        }

    @app.get("/health", tags=["系统"])
    async def health_check():
        return {
            "code": 200,
            "data": {
                "status": "healthy",
                "database": "connected",
                "timestamp": utc_now().isoformat(),
            },
            "message": "健康检查通过",
        }

    @app.post("/api/v1/resources", tags=["资源管理API"], status_code=201)
    async def create_resource(
        request: Dict[str, Any],
        db: AsyncSession = Depends(get_db),
    ):
        resource_type = request.get("type", "resource")
        config = request.get("config", {})
        labels = request.get("labels", {})
        tenant_id = request.get("tenant_id")

        entity = Entity(
            type=EntityType(resource_type),
            status=EntityStatus.PROVISIONING,
            attributes={**config, **labels},
            tenant_id=tenant_id,
        )
        db.add(entity)
        await db.flush()

        return {
            "code": 201,
            "data": {
                "id": entity.id,
                "status": "provisioning",
                "type": resource_type,
                "created_at": entity.created_at.isoformat(),
            },
            "message": "资源创建成功",
        }

    @app.get("/api/v1/resources/{id}/status", tags=["状态查询API"])
    async def get_resource_status(
        id: str,
        tenant_id: Optional[str] = None,
        db: AsyncSession = Depends(get_db),
    ):
        query = select(Entity).where(Entity.id == id)
        if tenant_id:
            query = query.where(Entity.tenant_id == tenant_id)

        result = await db.execute(query)
        entity = result.scalar_one_or_none()

        if not entity:
            return {
                "code": 404,
                "data": {
                    "id": id,
                    "status": "not_found",
                    "progress": 0.0,
                },
                "message": "资源不存在",
            }

        query_instance = select(RunInstance).where(RunInstance.entity_id == id)
        result_instance = await db.execute(query_instance)
        instance = result_instance.scalar_one_or_none()

        progress = instance.progress if instance else 0.0
        status = instance.phase if instance else entity.status

        return {
            "code": 200,
            "data": {
                "id": id,
                "status": status,
                "progress": progress,
                "entity": EntityResponse.model_validate(entity).model_dump() if entity else None,
                "instance": RunInstanceResponse.model_validate(instance).model_dump() if instance else None,
            },
            "message": "查询成功",
        }

    @app.post("/api/v1/resources/batch", tags=["批量操作API"])
    async def batch_operations(
        request: Dict[str, Any],
        db: AsyncSession = Depends(get_db),
    ):
        operations = request.get("operations", [])
        results = []

        for op in operations:
            action = op.get("action")
            resource_id = op.get("id")
            params = op.get("params", {})

            try:
                if action == "restart":
                    query = select(Entity).where(Entity.id == resource_id)
                    result = await db.execute(query)
                    entity = result.scalar_one_or_none()
                    if entity:
                        entity.status = EntityStatus.RESTARTING
                        db.add(entity)

                        query_instance = select(RunInstance).where(
                            RunInstance.entity_id == resource_id
                        )
                        result_instance = await db.execute(query_instance)
                        instance = result_instance.scalar_one_or_none()
                        if instance:
                            instance.phase = "restarting"
                            instance.progress = 0.0
                            db.add(instance)

                        results.append(
                            {
                                "id": resource_id,
                                "action": action,
                                "status": "success",
                                "message": f"资源 {resource_id} 重启中",
                            }
                        )
                    else:
                        results.append(
                            {
                                "id": resource_id,
                                "action": action,
                                "status": "failed",
                                "message": f"资源 {resource_id} 不存在",
                            }
                        )

                elif action == "stop":
                    query = select(Entity).where(Entity.id == resource_id)
                    result = await db.execute(query)
                    entity = result.scalar_one_or_none()
                    if entity:
                        entity.status = EntityStatus.STOPPED
                        db.add(entity)
                        results.append(
                            {
                                "id": resource_id,
                                "action": action,
                                "status": "success",
                                "message": f"资源 {resource_id} 已停止",
                            }
                        )
                    else:
                        results.append(
                            {
                                "id": resource_id,
                                "action": action,
                                "status": "failed",
                                "message": f"资源 {resource_id} 不存在",
                            }
                        )

                elif action == "delete":
                    query = select(Entity).where(Entity.id == resource_id)
                    result = await db.execute(query)
                    entity = result.scalar_one_or_none()
                    if entity:
                        entity.status = EntityStatus.DELETED
                        db.add(entity)
                        results.append(
                            {
                                "id": resource_id,
                                "action": action,
                                "status": "success",
                                "message": f"资源 {resource_id} 已删除",
                            }
                        )
                    else:
                        results.append(
                            {
                                "id": resource_id,
                                "action": action,
                                "status": "failed",
                                "message": f"资源 {resource_id} 不存在",
                            }
                        )

                else:
                    results.append(
                        {
                            "id": resource_id,
                            "action": action,
                            "status": "failed",
                            "message": f"不支持的操作: {action}",
                        }
                    )

            except Exception as e:
                results.append(
                    {
                        "id": resource_id,
                        "action": action,
                        "status": "failed",
                        "message": str(e),
                    }
                )

        await db.flush()

        return {
            "code": 200,
            "data": {
                "batch_id": generate_id("batch"),
                "results": results,
                "total": len(operations),
                "success_count": sum(1 for r in results if r["status"] == "success"),
                "failed_count": sum(1 for r in results if r["status"] == "failed"),
            },
            "message": "批量操作执行完成",
        }

    @app.get("/api/v1/configs", tags=["配置管理"])
    async def list_configs(
        namespace: Optional[str] = None,
        tenant_id: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
        db: AsyncSession = Depends(get_db),
    ):
        query = select(ConfigDefinition)
        if namespace:
            query = query.where(ConfigDefinition.namespace == namespace)
        if tenant_id:
            query = query.where(ConfigDefinition.tenant_id == tenant_id)

        query = query.order_by(ConfigDefinition.version.desc()).limit(limit).offset(offset)
        result = await db.execute(query)
        configs = result.scalars().all()

        return {
            "code": 200,
            "data": [ConfigResponse.model_validate(c).model_dump() for c in configs],
            "total": len(configs),
            "message": "查询成功",
        }

    @app.post("/api/v1/configs", tags=["配置管理"], status_code=201)
    async def create_config(
        config_data: ConfigCreate,
        db: AsyncSession = Depends(get_db),
    ):
        config = ConfigDefinition(**config_data.model_dump())
        db.add(config)
        await db.flush()

        return {
            "code": 201,
            "data": ConfigResponse.model_validate(config).model_dump(),
            "message": "配置创建成功",
        }

    @app.get("/api/v1/snapshots", tags=["统计快照"])
    async def list_snapshots(
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        tenant_id: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
        db: AsyncSession = Depends(get_db),
    ):
        query = select(Snapshot)
        if start_time:
            query = query.where(Snapshot.timestamp >= start_time)
        if end_time:
            query = query.where(Snapshot.timestamp <= end_time)
        if tenant_id:
            query = query.where(Snapshot.tenant_id == tenant_id)

        query = query.order_by(Snapshot.timestamp.desc()).limit(limit).offset(offset)
        result = await db.execute(query)
        snapshots = result.scalars().all()

        return {
            "code": 200,
            "data": [SnapshotResponse.model_validate(s).model_dump() for s in snapshots],
            "total": len(snapshots),
            "message": "查询成功",
        }

    @app.post("/api/v1/snapshots", tags=["统计快照"], status_code=201)
    async def create_snapshot(
        snapshot_data: SnapshotCreate,
        db: AsyncSession = Depends(get_db),
    ):
        snapshot = Snapshot(**snapshot_data.model_dump())
        db.add(snapshot)
        await db.flush()

        return {
            "code": 201,
            "data": SnapshotResponse.model_validate(snapshot).model_dump(),
            "message": "快照创建成功",
        }

    app.include_router(ticket_assignment_router, prefix="/api/v1")
    app.include_router(metering_billing_router, prefix="/api/v1")
    app.include_router(sla_monitor_router, prefix="/api/v1")
    app.include_router(skill_graph_router, prefix="/api/v1")
    app.include_router(multitenant_router, prefix="/api/v1")
    app.include_router(workflow_designer_router, prefix="/api/v1")
    app.include_router(document_diff_router, prefix="/api/v1")
    app.include_router(approval_engine_router, prefix="/api/v1")

    return app


app = create_app()
