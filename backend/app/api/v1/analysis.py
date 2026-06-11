from fastapi import APIRouter, Depends, Query, HTTPException
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.routing import path_analysis_service
from app.schemas import ODQuery, SignalTimingCreate, SignalTiming
from app.utils.auth import get_current_active_user

import logging

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/analysis", tags=["路径分析"])


@router.get("/od-flows")
async def get_od_flows(
    origin_zone: Optional[int] = Query(None),
    dest_zone: Optional[int] = Query(None),
    time_period: str = Query("morning_peak"),
    travel_mode: str = Query("all"),
    limit: int = Query(1000),
    db: Session = Depends(get_db),
):
    flows = path_analysis_service.analyze_od_flows(
        db, origin_zone, dest_zone, time_period, travel_mode, limit
    )
    return {
        "count": len(flows),
        "flows": flows,
    }


@router.get("/od-flows/geojson")
async def get_od_flow_geojson(
    origin_zone: Optional[int] = Query(None),
    dest_zone: Optional[int] = Query(None),
    time_period: str = Query("morning_peak"),
    min_trips: int = Query(10),
    db: Session = Depends(get_db),
):
    geojson = path_analysis_service.get_od_flow_lines(
        db, origin_zone, dest_zone, time_period, min_trips
    )
    return geojson


@router.get("/congestion-nodes")
async def get_congestion_nodes(
    start_time: Optional[str] = Query(None),
    end_time: Optional[str] = Query(None),
    threshold: float = Query(0.7),
    top_n: int = Query(50),
    db: Session = Depends(get_db),
):
    end_dt = datetime.utcnow()
    start_dt = end_dt - timedelta(hours=1)

    if end_time:
        end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
    if start_time:
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))

    nodes = path_analysis_service.find_congestion_nodes(
        db, start_dt, end_dt, threshold, top_n
    )

    return {
        "start_time": start_dt.isoformat(),
        "end_time": end_dt.isoformat(),
        "threshold": threshold,
        "nodes": nodes,
    }


@router.get("/congestion-trace/{sensor_id}")
async def trace_congestion(
    sensor_id: str,
    start_time: Optional[str] = Query(None),
    end_time: Optional[str] = Query(None),
    max_depth: int = Query(3),
    db: Session = Depends(get_db),
):
    end_dt = datetime.utcnow()
    start_dt = end_dt - timedelta(hours=1)

    if end_time:
        end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
    if start_time:
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))

    result = path_analysis_service.trace_upstream_downstream(
        db, sensor_id, start_dt, end_dt, max_depth
    )

    return result


@router.get("/signal-timing/plans")
async def list_signal_plans(
    db: Session = Depends(get_db),
):
    from app.models import SignalTimingPlan
    plans = db.query(SignalTimingPlan).all()
    return {
        "count": len(plans),
        "plans": [
            {
                "id": p.id,
                "name": p.name,
                "intersection_id": p.intersection_id,
                "intersection_name": p.intersection_name,
                "cycle_length": p.cycle_length,
                "status": p.status,
                "created_at": p.created_at.isoformat(),
            }
            for p in plans
        ],
    }


@router.post("/signal-timing/plans", response_model=SignalTiming)
async def create_signal_plan(
    plan: SignalTimingCreate,
    db: Session = Depends(get_db),
    current_user=Depends(get_current_active_user),
):
    from app.models import SignalTimingPlan
    db_plan = SignalTimingPlan(
        name=plan.name,
        intersection_id=plan.intersection_id,
        intersection_name=plan.intersection_name,
        phases=plan.phases,
        cycle_length=plan.cycle_length,
        status="draft",
        created_by=current_user.id if hasattr(current_user, 'id') else None,
    )
    db.add(db_plan)
    db.commit()
    db.refresh(db_plan)
    return db_plan


@router.post("/signal-timing/simulate/{plan_id}")
async def simulate_signal_timing(
    plan_id: int,
    start_time: Optional[str] = Query(None),
    end_time: Optional[str] = Query(None),
    db: Session = Depends(get_db),
):
    end_dt = datetime.utcnow()
    start_dt = end_dt - timedelta(hours=2)

    if end_time:
        end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
    if start_time:
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))

    result = path_analysis_service.simulate_signal_timing(
        db, plan_id, start_dt, end_dt
    )

    if "error" in result:
        raise HTTPException(status_code=404, detail=result["error"])

    return result


@router.post("/signal-timing/compare")
async def compare_signal_plans(
    plan_ids: List[int],
    start_time: Optional[str] = Query(None),
    end_time: Optional[str] = Query(None),
    db: Session = Depends(get_db),
):
    end_dt = datetime.utcnow()
    start_dt = end_dt - timedelta(hours=2)

    if end_time:
        end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
    if start_time:
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))

    result = path_analysis_service.compare_signal_plans(
        db, plan_ids, start_dt, end_dt
    )

    return result


@router.get("/zones")
async def get_traffic_zones(
    zone_type: Optional[str] = Query(None),
    db: Session = Depends(get_db),
):
    zones = path_analysis_service.get_zones(db, zone_type)
    return {
        "count": len(zones),
        "zones": zones,
    }
