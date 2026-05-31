from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from .models import (
    WorkflowCreate,
    WorkflowUpdate,
    WorkflowResponse,
    WorkflowInstanceCreate,
    WorkflowInstanceResponse,
    NodeExecutionResponse,
    WorkflowStatus,
)
from .service import WorkflowDesignerService, WorkflowEngineService

router = APIRouter(prefix="/workflows", tags=["可视化流程设计"])


@router.post("", response_model=Dict[str, Any], status_code=201)
async def create_workflow(
    workflow_data: WorkflowCreate,
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowDesignerService(db)
    workflow = await service.create_workflow(workflow_data)
    return {
        "code": 201,
        "data": workflow.model_dump(),
        "message": "流程创建成功",
    }


@router.get("/{workflow_id}", response_model=Dict[str, Any])
async def get_workflow(
    workflow_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowDesignerService(db)
    workflow = await service.get_workflow(workflow_id, tenant_id)
    return {
        "code": 200,
        "data": workflow.model_dump(),
        "message": "查询成功",
    }


@router.put("/{workflow_id}", response_model=Dict[str, Any])
async def update_workflow(
    workflow_id: str,
    update_data: WorkflowUpdate,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowDesignerService(db)
    workflow = await service.update_workflow(workflow_id, update_data, tenant_id)
    return {
        "code": 200,
        "data": workflow.model_dump(),
        "message": "流程更新成功",
    }


@router.get("/{workflow_id}/validate", response_model=Dict[str, Any])
async def validate_workflow(
    workflow_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowDesignerService(db)
    result = await service.validate_workflow(workflow_id, tenant_id)
    return {
        "code": 200,
        "data": result,
        "message": "校验完成",
    }


@router.get("", response_model=Dict[str, Any])
async def list_workflows(
    tenant_id: Optional[str] = Query(None),
    status: Optional[WorkflowStatus] = Query(None),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowDesignerService(db)
    workflows = await service.list_workflows(tenant_id, status, limit, offset)
    return {
        "code": 200,
        "data": [w.model_dump() for w in workflows],
        "total": len(workflows),
        "message": "查询成功",
    }


@router.post("/instances", response_model=Dict[str, Any], status_code=201)
async def start_workflow_instance(
    instance_data: WorkflowInstanceCreate,
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowEngineService(db)
    instance = await service.start_instance(instance_data)
    return {
        "code": 201,
        "data": instance.model_dump(),
        "message": "流程实例启动成功",
    }


@router.get("/instances/{instance_id}", response_model=Dict[str, Any])
async def get_workflow_instance(
    instance_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowEngineService(db)
    instance = await service.get_instance(instance_id, tenant_id)
    return {
        "code": 200,
        "data": instance.model_dump(),
        "message": "查询成功",
    }


@router.get("/instances/{instance_id}/executions", response_model=Dict[str, Any])
async def get_instance_executions(
    instance_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = WorkflowEngineService(db)
    executions = await service.get_instance_executions(instance_id, tenant_id)
    return {
        "code": 200,
        "data": [e.model_dump() for e in executions],
        "total": len(executions),
        "message": "查询成功",
    }
