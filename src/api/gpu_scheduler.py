from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from src.core import ApiResponse, get_trace_id
from src.modules.gpu_scheduler import (
    GpuTaskSubmitRequest,
    GpuTaskStatus,
    GpuPriority,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/gpu", tags=["GPU Scheduler"])


@router.post("/tasks", response_model=ApiResponse)
async def submit_task(
    request: GpuTaskSubmitRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.gpu_scheduler.submit_task(request, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/tasks", response_model=ApiResponse)
async def list_tasks(
    status: Optional[GpuTaskStatus] = None,
    priority: Optional[GpuPriority] = None,
    limit: int = 100,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    from src.modules.gpu_scheduler import GpuTask
    tasks = list(container.gpu_scheduler._tasks.values())
    if status:
        tasks = [
            t for t in tasks
            if container.gpu_scheduler._task_executions.get(t.task_id, [None])[-1]
            and container.gpu_scheduler._executions[container.gpu_scheduler._task_executions[t.task_id][-1]].status == status
        ]
    return ApiResponse.success(tasks[:limit])


@router.get("/tasks/{task_id}", response_model=ApiResponse)
async def get_task(
    task_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.gpu_scheduler.get_task(task_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/executions/{execution_id}", response_model=ApiResponse)
async def get_execution(
    execution_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.gpu_scheduler.get_task_execution(execution_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/tasks/{task_id}/cancel", response_model=ApiResponse)
async def cancel_task(
    task_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.gpu_scheduler.cancel_task(task_id, trace_id or get_trace_id())
    return ApiResponse.success({"cancelled": result})


@router.get("/devices", response_model=ApiResponse)
async def list_devices(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.gpu_scheduler.list_devices(trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/cluster/stats", response_model=ApiResponse)
async def get_cluster_stats(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.gpu_scheduler.get_cluster_stats(trace_id or get_trace_id())
    return ApiResponse.success(result)
