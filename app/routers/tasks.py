from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.modules.scheduler import TaskScheduler, TaskDependencyError
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import TaskCreate, APIResponse
from app.logger import logger

router = APIRouter(prefix="/api/v1/tasks", tags=["Task Scheduler"])


@router.post("", response_model=APIResponse)
async def create_task(
    data: TaskCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    scheduler = TaskScheduler(db)
    
    try:
        task = await scheduler.create_task(
            name=data.name,
            task_type=data.task_type,
            payload=data.payload,
            dependencies=data.dependencies,
            priority=data.priority,
            scheduled_at=data.scheduled_at
        )
        await db.commit()
        
        return APIResponse(
            code=201,
            data={
                "task_id": task.id,
                "name": task.name,
                "task_type": task.task_type,
                "status": task.status,
                "priority": task.priority
            }
        )
    except TaskDependencyError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.get("/{task_id}", response_model=APIResponse)
async def get_task_status(
    task_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    scheduler = TaskScheduler(db)
    status_data = await scheduler.get_task_status(task_id)
    
    if not status_data:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Task not found"
        )
    
    return APIResponse(code=200, data=status_data)


@router.post("/{task_id}/execute", response_model=APIResponse)
async def execute_task(
    task_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    scheduler = TaskScheduler(db)
    
    try:
        result = await scheduler.execute_task(task_id)
        await db.commit()
        return APIResponse(code=200, data=result)
    except TaskDependencyError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e)
        )


@router.post("/{task_id}/cancel", response_model=APIResponse)
async def cancel_task(
    task_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    scheduler = TaskScheduler(db)
    cancelled = await scheduler.cancel_task(task_id)
    
    if not cancelled:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Task cannot be cancelled"
        )
    
    await db.commit()
    return APIResponse(code=200, data={"task_id": task_id, "cancelled": True})


@router.get("", response_model=APIResponse)
async def list_tasks(
    task_status: str = None,
    task_type: str = None,
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    scheduler = TaskScheduler(db)
    tasks = await scheduler.list_tasks(task_status, task_type, limit)
    
    return APIResponse(code=200, data=tasks)


@router.get("/{task_id}/graph", response_model=APIResponse)
async def get_dependency_graph(
    task_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    scheduler = TaskScheduler(db)
    graph = await scheduler.build_dependency_graph(task_id)
    
    return APIResponse(code=200, data=graph)


@router.get("/next", response_model=APIResponse)
async def get_next_pending_task(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    scheduler = TaskScheduler(db)
    task = await scheduler.get_next_task()
    
    if not task:
        return APIResponse(code=200, data=None)
    
    return APIResponse(
        code=200,
        data={
            "task_id": task.id,
            "name": task.name,
            "task_type": task.task_type,
            "priority": task.priority,
            "payload": task.payload
        }
    )
