from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.modules.notification import NotificationManager
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import NotificationCreate, APIResponse
from app.logger import logger

router = APIRouter(prefix="/api/v1/notifications", tags=["Notifications"])


@router.post("", response_model=APIResponse)
async def create_notification(
    data: NotificationCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = NotificationManager(db)
    notification = await manager.create_notification(
        title=data.title,
        content=data.content,
        user_id=data.user_id or user.get("user_id"),
        priority=data.priority,
        category=data.category
    )
    
    if not notification:
        return APIResponse(
            code=202,
            data={"suppressed": True}
        )
    
    await db.commit()
    
    return APIResponse(
        code=201,
        data={
            "id": notification.id,
            "title": notification.title,
            "priority": notification.priority,
            "category": notification.category
        }
    )


@router.post("/broadcast", response_model=APIResponse)
async def broadcast_notification(
    data: NotificationCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = NotificationManager(db)
    notifications = await manager.broadcast_notification(
        title=data.title,
        content=data.content,
        priority=data.priority,
        category=data.category
    )
    await db.commit()
    
    return APIResponse(
        code=201,
        data={
            "count": len(notifications),
            "title": data.title
        }
    )


@router.get("", response_model=APIResponse)
async def get_user_notifications(
    include_read: bool = False,
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = NotificationManager(db)
    notifications = await manager.get_user_notifications(
        user_id=user["user_id"],
        include_read=include_read,
        limit=limit
    )
    
    return APIResponse(code=200, data=notifications)


@router.get("/unread-count", response_model=APIResponse)
async def get_unread_count(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = NotificationManager(db)
    count = await manager.get_unread_count(user_id=user["user_id"])
    
    return APIResponse(code=200, data={"unread_count": count})


@router.post("/{notification_id}/read", response_model=APIResponse)
async def mark_as_read(
    notification_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = NotificationManager(db)
    success = await manager.mark_as_read(
        notification_id=notification_id,
        user_id=user["user_id"]
    )
    
    if not success:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Notification not found"
        )
    
    await db.commit()
    return APIResponse(code=200, data={"notification_id": notification_id, "is_read": True})


@router.post("/mark-all-read", response_model=APIResponse)
async def mark_all_as_read(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = NotificationManager(db)
    count = await manager.mark_all_as_read(user_id=user["user_id"])
    await db.commit()
    
    return APIResponse(code=200, data={"marked_count": count})


@router.delete("/{notification_id}", response_model=APIResponse)
async def delete_notification(
    notification_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = NotificationManager(db)
    success = await manager.delete_notification(
        notification_id=notification_id,
        user_id=user["user_id"]
    )
    
    if not success:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Notification not found"
        )
    
    await db.commit()
    return APIResponse(code=200, data={"notification_id": notification_id, "deleted": True})


@router.post("/suppress", response_model=APIResponse)
async def suppress_notifications(
    category: str = None,
    duration_minutes: int = 60,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = NotificationManager(db)
    await manager.suppress_notifications(
        user_id=user["user_id"],
        category=category,
        duration_minutes=duration_minutes
    )
    await db.commit()
    
    return APIResponse(
        code=200,
        data={
            "category": category,
            "duration_minutes": duration_minutes,
            "suppressed": True
        }
    )


@router.post("/rules", response_model=APIResponse)
async def register_suppression_rule(
    category: str,
    window_minutes: int = 60,
    max_notifications: int = 5,
    min_interval_seconds: int = 300,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = NotificationManager(db)
    manager.register_suppression_rule(
        category=category,
        window_minutes=window_minutes,
        max_notifications=max_notifications,
        min_interval_seconds=min_interval_seconds
    )
    
    return APIResponse(
        code=200,
        data={
            "category": category,
            "window_minutes": window_minutes,
            "max_notifications": max_notifications,
            "min_interval_seconds": min_interval_seconds
        }
    )
