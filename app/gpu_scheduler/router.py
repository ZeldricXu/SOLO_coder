from fastapi import APIRouter, Depends, Query
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional

from app.database import get_db
from app.schemas import (
    GPUTaskCreate,
    GPUTaskResponse,
    GPUTaskStatusUpdate,
    GPUResourceResponse,
    BaseResponse,
    PaginatedResponse,
    TaskPriority,
)
from app.gpu_scheduler.service import GPUTaskScheduler, GPUResourceManager
from app.logging import LogContext

router = APIRouter(prefix="/api/v1/gpu", tags=["GPU Scheduler"])


@router.post("/tasks", response_model=BaseResponse[GPUTaskResponse])
async def submit_task(
    task_in: GPUTaskCreate,
    db: AsyncSession = Depends(get_db),
):
    scheduler = GPUTaskScheduler(db)
    mock_user_id = UUID("00000000-0000-0000-0000-000000000001")
    task = await scheduler.submit_task(task_in, mock_user_id)
    return BaseResponse(
        code=201,
        data=task,
        request_id=LogContext.get_request_id(),
        message="GPU task submitted successfully",
    )


@router.get("/tasks/{task_id}", response_model=BaseResponse[GPUTaskResponse])
async def get_task(
    task_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    scheduler = GPUTaskScheduler(db)
    task = await scheduler.get_task(task_id)
    return BaseResponse(data=task, request_id=LogContext.get_request_id())


@router.get("/tasks", response_model=BaseResponse[PaginatedResponse[GPUTaskResponse]])
async def list_tasks(
    status: Optional[str] = Query(None, description="Filter by status"),
    priority: Optional[TaskPriority] = Query(None, description="Filter by priority"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    scheduler = GPUTaskScheduler(db)
    skip = (page - 1) * page_size
    tasks, total = await scheduler.list_tasks(
        status=status,
        priority=priority.value if priority else None,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=tasks,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.patch("/tasks/{task_id}/status", response_model=BaseResponse[GPUTaskResponse])
async def update_task_status(
    task_id: UUID,
    update_in: GPUTaskStatusUpdate,
    db: AsyncSession = Depends(get_db),
):
    scheduler = GPUTaskScheduler(db)
    task = await scheduler.update_task_status(task_id, update_in)
    return BaseResponse(
        data=task,
        request_id=LogContext.get_request_id(),
        message="Task status updated successfully",
    )


@router.post("/tasks/{task_id}/cancel", response_model=BaseResponse[GPUTaskResponse])
async def cancel_task(
    task_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    scheduler = GPUTaskScheduler(db)
    task = await scheduler.cancel_task(task_id)
    return BaseResponse(
        data=task,
        request_id=LogContext.get_request_id(),
        message="Task cancelled successfully",
    )


@router.get("/queue/status", response_model=BaseResponse[dict])
async def get_queue_status(
    db: AsyncSession = Depends(get_db),
):
    scheduler = GPUTaskScheduler(db)
    status = await scheduler.get_queue_status()
    return BaseResponse(data=status, request_id=LogContext.get_request_id())


@router.post("/resources", response_model=BaseResponse[GPUResourceResponse])
async def register_gpu(
    node_id: str = Query(..., description="Node ID"),
    gpu_index: int = Query(..., description="GPU index"),
    total_memory_gb: float = Query(..., description="Total memory in GB"),
    db: AsyncSession = Depends(get_db),
):
    resource_manager = GPUResourceManager(db)
    gpu = await resource_manager.register_gpu(node_id, gpu_index, total_memory_gb)
    return BaseResponse(
        code=201,
        data=gpu,
        request_id=LogContext.get_request_id(),
        message="GPU registered successfully",
    )


@router.get("/resources", response_model=BaseResponse[list[GPUResourceResponse]])
async def list_gpus(
    node_id: Optional[str] = Query(None, description="Filter by node ID"),
    is_healthy: Optional[str] = Query(None, description="Filter by health status"),
    only_available: bool = Query(False, description="Only show available GPUs"),
    db: AsyncSession = Depends(get_db),
):
    resource_manager = GPUResourceManager(db)
    gpus = await resource_manager.list_gpus(
        node_id=node_id,
        is_healthy=is_healthy,
        only_available=only_available,
    )
    return BaseResponse(data=gpus, request_id=LogContext.get_request_id())


@router.get("/resources/{gpu_id}", response_model=BaseResponse[GPUResourceResponse])
async def get_gpu(
    gpu_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    resource_manager = GPUResourceManager(db)
    gpu = await resource_manager.get_gpu(gpu_id)
    return BaseResponse(data=gpu, request_id=LogContext.get_request_id())
