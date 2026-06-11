from fastapi import APIRouter, Depends, Query, HTTPException
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.models import TaskJob, User
from app.schemas import TaskJobCreate, TaskJob
from app.utils.auth import get_current_active_user, require_role
from app.tiles import tile_generator

import logging

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/admin", tags=["后台管理"])


@router.get("/dashboard")
async def get_admin_dashboard(
    db: Session = Depends(get_db),
    current_user=Depends(require_role(["admin"])),
):
    from app.models import TrafficSensor, TrafficFlowRecord, PredictionModel, DataSource

    stats = {
        "total_sensors": db.query(TrafficSensor).count(),
        "active_sensors": db.query(TrafficSensor).filter(TrafficSensor.status == "active").count(),
        "total_data_sources": db.query(DataSource).count(),
        "active_data_sources": db.query(DataSource).filter(DataSource.status == "active").count(),
        "total_models": db.query(PredictionModel).count(),
        "completed_models": db.query(PredictionModel).filter(PredictionModel.status == "completed").count(),
        "total_users": db.query(User).count(),
        "active_users": db.query(User).filter(User.is_active == True).count(),
        "cache_stats": tile_generator.get_cache_stats(),
    }

    return stats


@router.get("/tasks")
async def list_tasks(
    task_type: Optional[str] = Query(None),
    status: Optional[str] = Query(None),
    limit: int = Query(50),
    offset: int = Query(0),
    db: Session = Depends(get_db),
    current_user=Depends(get_current_active_user),
):
    query = db.query(TaskJob)

    if task_type:
        query = query.filter(TaskJob.task_type == task_type)
    if status:
        query = query.filter(TaskJob.status == status)

    total = query.count()
    tasks = query.order_by(TaskJob.created_at.desc()).offset(offset).limit(limit).all()

    return {
        "total": total,
        "count": len(tasks),
        "tasks": tasks,
    }


@router.post("/tasks", response_model=TaskJob)
async def create_task(
    task: TaskJobCreate,
    db: Session = Depends(get_db),
    current_user=Depends(get_current_active_user),
):
    import uuid

    task_id = str(uuid.uuid4())

    db_task = TaskJob(
        task_type=task.task_type,
        task_id=task_id,
        status="pending",
        progress=0.0,
        params=task.params,
        created_by=current_user.id if hasattr(current_user, 'id') else None,
    )

    db.add(db_task)
    db.commit()
    db.refresh(db_task)

    return db_task


@router.get("/tasks/{task_id}")
async def get_task_status(
    task_id: str,
    db: Session = Depends(get_db),
    current_user=Depends(get_current_active_user),
):
    task = db.query(TaskJob).filter(TaskJob.task_id == task_id).first()

    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    return task


@router.get("/users")
async def list_users(
    role: Optional[str] = Query(None),
    limit: int = Query(50),
    offset: int = Query(0),
    db: Session = Depends(get_db),
    current_user=Depends(require_role(["admin"])),
):
    query = db.query(User)

    if role:
        query = query.filter(User.role == role)

    total = query.count()
    users = query.offset(offset).limit(limit).all()

    return {
        "total": total,
        "count": len(users),
        "users": [
            {
                "id": u.id,
                "username": u.username,
                "email": u.email,
                "full_name": u.full_name,
                "role": u.role,
                "is_active": u.is_active,
                "created_at": u.created_at.isoformat(),
                "last_login": u.last_login.isoformat() if u.last_login else None,
            }
            for u in users
        ],
    }


@router.post("/cache/clear")
async def clear_all_cache(
    layer_type: Optional[str] = Query(None),
    db: Session = Depends(get_db),
    current_user=Depends(require_role(["admin"])),
):
    tile_generator.clear_cache(layer_type)

    from app.utils.redis_client import redis_manager
    if not layer_type:
        redis_manager.delete("heatmap:*")

    return {
        "status": "success",
        "message": f"Cache cleared for {layer_type or 'all layers'}",
    }


@router.get("/cache/stats")
async def get_cache_stats(
    current_user=Depends(get_current_active_user),
):
    stats = tile_generator.get_cache_stats()
    return stats


@router.get("/system/health")
async def system_health_check():
    return {
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat(),
    }
