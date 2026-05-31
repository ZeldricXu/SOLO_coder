from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from .models import (
    SLAPolicyCreate,
    SLAPolicyResponse,
    SLATrackerCreate,
    SLATrackerResponse,
    SLAEventResponse,
    SLATargetType,
    SLASeverity,
)
from .service import SLAPolicyService, SLATrackerService, SLAMonitorService

router = APIRouter(prefix="/sla", tags=["SLA时效监控"])


@router.post("/policies", response_model=Dict[str, Any], status_code=201)
async def create_sla_policy(
    policy_data: SLAPolicyCreate,
    db: AsyncSession = Depends(get_db),
):
    service = SLAPolicyService(db)
    policy = await service.create_policy(policy_data)
    return {
        "code": 201,
        "data": policy.model_dump(),
        "message": "SLA策略创建成功",
    }


@router.get("/policies/{policy_id}", response_model=Dict[str, Any])
async def get_sla_policy(
    policy_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLAPolicyService(db)
    policy = await service.get_policy(policy_id, tenant_id)
    return {
        "code": 200,
        "data": policy.model_dump(),
        "message": "查询成功",
    }


@router.get("/policies", response_model=Dict[str, Any])
async def get_active_policies(
    target_type: Optional[SLATargetType] = Query(None),
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLAPolicyService(db)
    policies = await service.get_active_policies(target_type, tenant_id)
    return {
        "code": 200,
        "data": [p.model_dump() for p in policies],
        "total": len(policies),
        "message": "查询成功",
    }


@router.post("/trackers", response_model=Dict[str, Any], status_code=201)
async def create_sla_tracker(
    tracker_data: SLATrackerCreate,
    db: AsyncSession = Depends(get_db),
):
    service = SLATrackerService(db)
    tracker = await service.create_tracker(tracker_data)
    return {
        "code": 201,
        "data": tracker.model_dump(),
        "message": "SLA追踪器创建成功",
    }


@router.get("/trackers/{tracker_id}", response_model=Dict[str, Any])
async def get_sla_tracker(
    tracker_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLATrackerService(db)
    tracker = await service.get_tracker(tracker_id, tenant_id)
    return {
        "code": 200,
        "data": tracker.model_dump(),
        "message": "查询成功",
    }


@router.get("/trackers", response_model=Dict[str, Any])
async def get_active_trackers(
    entity_id: Optional[str] = Query(None),
    tenant_id: Optional[str] = Query(None),
    status: Optional[SLASeverity] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLATrackerService(db)
    trackers = await service.get_active_trackers(entity_id, tenant_id, status)
    return {
        "code": 200,
        "data": [t.model_dump() for t in trackers],
        "total": len(trackers),
        "message": "查询成功",
    }


@router.post("/trackers/{tracker_id}/pause", response_model=Dict[str, Any])
async def pause_sla_tracker(
    tracker_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLATrackerService(db)
    tracker = await service.pause_tracker(tracker_id, tenant_id)
    return {
        "code": 200,
        "data": tracker.model_dump(),
        "message": "SLA追踪器已暂停",
    }


@router.post("/trackers/{tracker_id}/resume", response_model=Dict[str, Any])
async def resume_sla_tracker(
    tracker_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLATrackerService(db)
    tracker = await service.resume_tracker(tracker_id, tenant_id)
    return {
        "code": 200,
        "data": tracker.model_dump(),
        "message": "SLA追踪器已恢复",
    }


@router.post("/trackers/{tracker_id}/complete", response_model=Dict[str, Any])
async def complete_sla_tracker(
    tracker_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLATrackerService(db)
    tracker = await service.complete_tracker(tracker_id, tenant_id)
    return {
        "code": 200,
        "data": tracker.model_dump(),
        "message": "SLA追踪器已完成",
    }


@router.get("/trackers/{tracker_id}/events", response_model=Dict[str, Any])
async def get_tracker_events(
    tracker_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SLATrackerService(db)
    events = await service.get_tracker_events(tracker_id, tenant_id)
    return {
        "code": 200,
        "data": [e.model_dump() for e in events],
        "total": len(events),
        "message": "查询成功",
    }


@router.post("/check", response_model=Dict[str, Any])
async def run_sla_check(
    db: AsyncSession = Depends(get_db),
):
    service = SLAMonitorService(db)
    events = await service.run_sla_check()
    return {
        "code": 200,
        "data": events,
        "triggered_count": len(events),
        "message": "SLA检查完成",
    }
