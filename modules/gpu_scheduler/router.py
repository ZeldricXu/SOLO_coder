from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, Header, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db

from .models import (
    GpuNodeCreate,
    GpuTaskCreate,
    GpuTaskStatus,
)
from .service import GpuSchedulerService


router = APIRouter(prefix="/gpu-scheduler", tags=["GPU任务调度"])


@router.post("/nodes", response_model=Dict[str, Any], status_code=201)
async def register_node(
    node_data: GpuNodeCreate,
    db: AsyncSession = Depends(get_db),
):
    service = GpuSchedulerService(db)
    node = await service.register_node(node_data)
    return {
        "code": 201,
        "data": node.model_dump(),
        "message": "GPU节点注册成功",
    }


@router.get("/nodes/{node_id}", response_model=Dict[str, Any])
async def get_node(
    node_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = GpuSchedulerService(db)
    node = await service.get_node(node_id, tenant_id)
    return {
        "code": 200,
        "data": node.model_dump(),
        "message": "查询成功",
    }


@router.post("/tasks", response_model=Dict[str, Any], status_code=201)
async def submit_task(
    task_data: GpuTaskCreate,
    db: AsyncSession = Depends(get_db),
):
    service = GpuSchedulerService(db)
    task = await service.submit_task(task_data)
    return {
        "code": 201,
        "data": task.model_dump(),
        "message": "GPU任务提交成功",
    }


@router.get("/tasks/{task_id}", response_model=Dict[str, Any])
async def get_task(
    task_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = GpuSchedulerService(db)
    task = await service.get_task(task_id, tenant_id)
    return {
        "code": 200,
        "data": task.model_dump(),
        "message": "查询成功",
    }


@router.post("/tasks/{task_id}/schedule", response_model=Dict[str, Any])
async def schedule_task(
    task_id: str,
    node_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = GpuSchedulerService(db)
    task = await service.schedule_task(task_id, node_id, tenant_id)
    return {
        "code": 200,
        "data": task.model_dump(),
        "message": "任务调度成功",
    }


@router.patch("/tasks/{task_id}/progress", response_model=Dict[str, Any])
async def update_task_progress(
    task_id: str,
    progress: int,
    status: Optional[GpuTaskStatus] = None,
    error_message: Optional[str] = None,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = GpuSchedulerService(db)
    task = await service.update_task_progress(
        task_id, progress, status, error_message, tenant_id
    )
    return {
        "code": 200,
        "data": task.model_dump(),
        "message": "任务进度更新成功",
    }


@router.get("/cluster/stats", response_model=Dict[str, Any])
async def get_cluster_stats(
    user_agent: str = Header("desktop", alias="User-Agent"),
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = GpuSchedulerService(db)
    stats = await service.get_cluster_stats(user_agent, tenant_id)
    return {
        "code": 200,
        "data": stats.model_dump(),
        "message": "查询成功",
    }
