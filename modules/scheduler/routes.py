from typing import List, Optional
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core import get_db
from models import ResponseModel, PaginatedResponse
from .schemas import (
    ScheduledTaskCreate,
    ScheduledTaskResponse,
    ScheduledTaskUpdate,
    TaskExecutionResponse,
    TaskTriggerRequest,
)
from .service import SchedulerService

router = APIRouter(prefix="/api/v1/scheduler", tags=["Scheduler"])


@router.post("/tasks", response_model=ResponseModel[ScheduledTaskResponse])
async def create_task(
    data: ScheduledTaskCreate,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    task = await service.create_task(data)
    return ResponseModel(data=ScheduledTaskResponse.model_validate(task))


@router.get("/tasks", response_model=PaginatedResponse[ScheduledTaskResponse])
async def list_tasks(
    page: int = 1,
    page_size: int = 20,
    enabled: Optional[bool] = None,
    task_type: Optional[str] = None,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    tasks = await service.list_tasks(page, page_size, enabled, task_type)
    return PaginatedResponse(
        data=[ScheduledTaskResponse.model_validate(t) for t in tasks],
        total=len(tasks),
        page=page,
        page_size=page_size,
    )


@router.get("/tasks/{task_id}", response_model=ResponseModel[ScheduledTaskResponse])
async def get_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    task = await service.get_task(task_id)
    return ResponseModel(data=ScheduledTaskResponse.model_validate(task))


@router.put("/tasks/{task_id}", response_model=ResponseModel[ScheduledTaskResponse])
async def update_task(
    task_id: str,
    data: ScheduledTaskUpdate,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    task = await service.update_task(task_id, data)
    return ResponseModel(data=ScheduledTaskResponse.model_validate(task))


@router.delete("/tasks/{task_id}", response_model=ResponseModel)
async def delete_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    await service.delete_task(task_id)
    return ResponseModel(message="Task deleted successfully")


@router.post("/tasks/trigger", response_model=ResponseModel)
async def trigger_task(
    data: TaskTriggerRequest,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    triggered = await service.trigger_task(data.task_id)
    return ResponseModel(data={"triggered": triggered})


@router.post("/tasks/{task_id}/pause", response_model=ResponseModel[ScheduledTaskResponse])
async def pause_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    task = await service.pause_task(task_id)
    return ResponseModel(data=ScheduledTaskResponse.model_validate(task))


@router.post("/tasks/{task_id}/resume", response_model=ResponseModel[ScheduledTaskResponse])
async def resume_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    task = await service.resume_task(task_id)
    return ResponseModel(data=ScheduledTaskResponse.model_validate(task))


@router.get("/tasks/{task_id}/executions", response_model=PaginatedResponse[TaskExecutionResponse])
async def get_task_executions(
    task_id: str,
    page: int = 1,
    page_size: int = 50,
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    executions = await service.get_task_executions(task_id, page, page_size)
    return PaginatedResponse(
        data=[TaskExecutionResponse.model_validate(e) for e in executions],
        total=len(executions),
        page=page,
        page_size=page_size,
    )


@router.post("/start", response_model=ResponseModel)
async def start_scheduler(
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    await service.start_scheduler()
    return ResponseModel(message="Scheduler started successfully")


@router.post("/stop", response_model=ResponseModel)
async def stop_scheduler(
    db: AsyncSession = Depends(get_db),
):
    service = SchedulerService(db)
    await service.stop_scheduler()
    return ResponseModel(message="Scheduler stopped successfully")
