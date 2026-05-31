import asyncio
from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional
from app.database import get_async_db
from app.modules.device_shadow import DeviceShadowManager
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import DeviceShadowUpdate, APIResponse, AsyncOperationRequest, BatchAsyncRequest
from app.logger import logger

router = APIRouter(prefix="/api/v1/devices", tags=["Device Shadows"])


@router.get("/{device_id}/shadow", response_model=APIResponse)
async def get_device_shadow(
    device_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = DeviceShadowManager(db)
    shadow = await manager.get_or_create_shadow(device_id)
    
    return APIResponse(
        code=200,
        data={
            "device_id": shadow.device_id,
            "desired": shadow.desired,
            "reported": shadow.reported,
            "delta": shadow.delta,
            "version": shadow.version,
            "last_sync_at": shadow.last_sync_at.isoformat() if shadow.last_sync_at else None
        }
    )


@router.patch("/{device_id}/shadow/desired", response_model=APIResponse)
async def update_desired_state(
    device_id: str,
    data: DeviceShadowUpdate,
    async_mode: bool = Query(False, description="Execute asynchronously"),
    priority: int = Query(0, description="Priority for async execution"),
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = DeviceShadowManager(db)
    result = await manager.update_desired(device_id, data.state, async_mode=async_mode, priority=priority)
    
    if async_mode:
        return APIResponse(code=202, data=result)
    
    await db.commit()
    return APIResponse(code=200, data=result)


@router.patch("/{device_id}/shadow/reported", response_model=APIResponse)
async def update_reported_state(
    device_id: str,
    data: DeviceShadowUpdate,
    async_mode: bool = Query(False, description="Execute asynchronously"),
    priority: int = Query(0, description="Priority for async execution"),
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = DeviceShadowManager(db)
    result = await manager.update_reported(device_id, data.state, async_mode=async_mode, priority=priority)
    
    if async_mode:
        return APIResponse(code=202, data=result)
    
    await db.commit()
    return APIResponse(code=200, data=result)


@router.post("/{device_id}/shadow/sync", response_model=APIResponse)
async def sync_device_shadow(
    device_id: str,
    async_mode: bool = Query(False, description="Execute asynchronously"),
    priority: int = Query(0, description="Priority for async execution"),
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    manager = DeviceShadowManager(db)
    result = await manager.sync_shadow(device_id, async_mode=async_mode, priority=priority)
    
    if async_mode:
        return APIResponse(code=202, data=result)
    
    return APIResponse(code=200, data=result)


@router.delete("/{device_id}/shadow", response_model=APIResponse)
async def delete_device_shadow(
    device_id: str,
    async_mode: bool = Query(False, description="Execute asynchronously"),
    priority: int = Query(0, description="Priority for async execution"),
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = DeviceShadowManager(db)
    result = await manager.delete_shadow(device_id, async_mode=async_mode, priority=priority)
    
    if async_mode:
        return APIResponse(code=202, data=result)
    
    if not result.get("deleted"):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device shadow not found"
        )
    
    await db.commit()
    return APIResponse(code=200, data=result)


@router.get("", response_model=APIResponse)
async def list_device_shadows(
    limit: int = 100,
    offset: int = 0,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = DeviceShadowManager(db)
    shadows = await manager.list_shadows(limit, offset)
    
    return APIResponse(code=200, data=shadows)


@router.post("/async/batch", response_model=APIResponse)
async def submit_batch_async_operations(
    data: BatchAsyncRequest,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = DeviceShadowManager(db)
    
    operations = [
        {
            "device_id": op.device_id,
            "operation": op.operation,
            "state": op.state
        }
        for op in data.operations
    ]
    
    results = await manager.batch_update_async(operations, priority=data.priority)
    
    return APIResponse(
        code=202,
        data={
            "batch_size": len(results),
            "operations": results
        }
    )


@router.get("/async/tasks/{task_id}", response_model=APIResponse)
async def get_async_operation_status(
    task_id: str,
    wait: bool = Query(False, description="Wait for completion"),
    timeout: float = Query(30.0, description="Wait timeout in seconds"),
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = DeviceShadowManager(db)
    
    if wait:
        try:
            result = await manager.wait_for_operation(task_id, timeout=timeout)
        except asyncio.TimeoutError:
            raise HTTPException(
                status_code=status.HTTP_408_REQUEST_TIMEOUT,
                detail="Operation timeout"
            )
    else:
        result = manager.get_operation_status(task_id)
    
    if result is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Task not found"
        )
    
    return APIResponse(code=200, data=result)


@router.post("/async/tasks/{task_id}/cancel", response_model=APIResponse)
async def cancel_async_operation(
    task_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = DeviceShadowManager(db)
    success = manager.cancel_operation(task_id)
    
    if not success:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Task not found or already executing"
        )
    
    return APIResponse(
        code=200,
        data={"task_id": task_id, "cancelled": True}
    )


@router.get("/async/tasks", response_model=APIResponse)
async def list_queued_operations(
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = DeviceShadowManager(db)
    tasks = manager.list_queued_operations(limit)
    
    return APIResponse(code=200, data=tasks)


@router.get("/async/metrics", response_model=APIResponse)
async def get_async_executor_metrics(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = DeviceShadowManager(db)
    metrics = manager.get_executor_metrics()
    
    return APIResponse(code=200, data=metrics)
