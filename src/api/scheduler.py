from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from src.core import ApiResponse, get_trace_id, ResourceCreateRequest, ResourceStatusResponse
from src.modules.scheduler import (
    TaskCreateRequest,
    TaskUpdateRequest,
    TaskStatus,
    TaskPhase,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/scheduler", tags=["Scheduler"])


@router.post("/tasks", response_model=ApiResponse)
async def create_task(
    request: TaskCreateRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.scheduler.create_task(request, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/tasks", response_model=ApiResponse)
async def list_tasks(
    status: Optional[TaskStatus] = None,
    task_type: Optional[str] = None,
    limit: int = 100,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.scheduler.list_tasks(status, task_type, limit, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/tasks/{task_id}", response_model=ApiResponse)
async def get_task(
    task_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.scheduler.get_task(task_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/tasks/{task_id}/executions", response_model=ApiResponse)
async def get_task_executions(
    task_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.scheduler.get_task_executions(task_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/executions/{execution_id}", response_model=ApiResponse)
async def get_execution(
    execution_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.scheduler.get_task_execution(execution_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/tasks/{task_id}/cancel", response_model=ApiResponse)
async def cancel_task(
    task_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.scheduler.cancel_task(task_id, trace_id or get_trace_id())
    return ApiResponse.success({"cancelled": result})


@router.get("/summary", response_model=ApiResponse)
async def get_scheduler_summary(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.scheduler.get_summary(trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/resources", response_model=ApiResponse)
async def create_resource(
    request: ResourceCreateRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    from src.core import generate_id
    resource_id = generate_id("rsc")
    result = {
        "id": resource_id,
        "type": request.type,
        "config": request.config,
        "labels": request.labels,
        "status": "provisioning",
    }
    return ApiResponse.created(result, message="Resource provisioning")


@router.get("/resources/{resource_id}/status", response_model=ApiResponse)
async def get_resource_status(
    resource_id: str,
    container: DIContainer = Depends(get_container),
):
    result = ResourceStatusResponse(
        id=resource_id,
        status="running",
        progress=0.8,
    )
    return ApiResponse.success(result)


@router.post("/resources/batch", response_model=ApiResponse)
async def batch_operation(
    request: ResourceCreateRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    from src.core import BatchResult, generate_id
    batch_id = generate_id("batch")
    results = []
    return ApiResponse.success(
        BatchResult(batch_id=batch_id, results=results)
    )
