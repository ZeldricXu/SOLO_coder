from fastapi import APIRouter, Depends, Body
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional

from app.database import get_db
from app.schemas import (
    ResourceCreate,
    ResourceResponse,
    ResourceStatusResponse,
    BatchOperationRequest,
    BatchOperationResponse,
    TaskExecuteRequest,
    TaskExecuteResponse,
    BaseResponse,
)
from app.core.service import TaskExecutionService
from app.logging import LogContext

router = APIRouter(prefix="/api/v1/core", tags=["Core"])

_execution_service = TaskExecutionService()


@router.post("/resources", response_model=BaseResponse[ResourceResponse])
async def create_resource(
    resource_in: ResourceCreate,
):
    resource = await _execution_service.create_resource(
        resource_type=resource_in.type,
        config=resource_in.config,
        labels=resource_in.labels,
        namespace=resource_in.namespace,
    )
    return BaseResponse(
        code=201,
        data=resource,
        request_id=LogContext.get_request_id(),
        message="Resource created successfully",
    )


@router.get("/resources/{resource_id}/status", response_model=BaseResponse[ResourceStatusResponse])
async def get_resource_status(
    resource_id: str,
):
    status = await _execution_service.get_resource_status(resource_id)
    return BaseResponse(data=status, request_id=LogContext.get_request_id())


@router.post("/resources/batch", response_model=BaseResponse[BatchOperationResponse])
async def batch_operations(
    request: BatchOperationRequest,
):
    operations = [op.model_dump() for op in request.operations]
    result = await _execution_service.batch_operations(
        operations=operations,
        timeout_seconds=request.timeout_seconds,
    )
    return BaseResponse(
        data=BatchOperationResponse(**result),
        request_id=LogContext.get_request_id(),
        message="Batch operations completed",
    )


@router.post("/tasks/execute", response_model=BaseResponse[TaskExecuteResponse])
async def execute_task(
    request: TaskExecuteRequest,
):
    result = await _execution_service.execute_handler(
        task_type=request.task_type,
        namespace=request.namespace,
        payload=request.payload,
        priority=request.priority,
        callback_url=request.callback_url,
    )
    return BaseResponse(
        code=200,
        data=TaskExecuteResponse(**result),
        request_id=LogContext.get_request_id(),
        message="Task execution initiated",
    )


@router.get("/tasks/{task_id}/result", response_model=BaseResponse)
async def get_task_result(
    task_id: str,
):
    result = _execution_service.get_task_result(task_id)
    if not result:
        from app.exceptions import NotFoundError
        raise NotFoundError(f"Task {task_id} not found")
    return BaseResponse(data=result, request_id=LogContext.get_request_id())


@router.get("/tasks", response_model=BaseResponse[list])
async def list_tasks(
    status: Optional[str] = None,
    task_type: Optional[str] = None,
    limit: int = 100,
):
    tasks = await _execution_service.list_tasks(
        status=status,
        task_type=task_type,
        limit=limit,
    )
    return BaseResponse(data=tasks, request_id=LogContext.get_request_id())
