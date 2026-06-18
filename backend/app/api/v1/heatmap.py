from fastapi import APIRouter, Depends, Query, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse, Response
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.heatmap import (
    heatmap_service,
    temporal_heatmap_service,
    heatmap_dimension_service,
)
from app.schemas import HeatmapTileQuery, HeatmapQuery
from app.utils.auth import get_current_active_user

import io
import logging

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/heatmap", tags=["热力图"])


@router.get("/tile/{z}/{x}/{y}.png")
async def get_heatmap_tile(
    z: int,
    x: int,
    y: int,
    timestamp: Optional[str] = Query(None),
    data_type: str = Query("vehicle", description="数据类型: vehicle, pedestrian, congestion"),
    vehicle_type: str = Query("all", description="车辆类型"),
    road_level: str = Query("all", description="道路等级: all/expressway/main_road/secondary/branch"),
    direction: str = Query("both", description="行驶方向: both/up/down"),
    db: Session = Depends(get_db),
):
    dt = None
    if timestamp:
        dt = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))

    tile_bytes = heatmap_service.generate_tile(
        db, z, x, y, dt, data_type, vehicle_type
    )

    return StreamingResponse(
        io.BytesIO(tile_bytes),
        media_type="image/png",
        headers={
            "Cache-Control": "public, max-age=300",
            "Access-Control-Allow-Origin": "*",
        }
    )


@router.get("/temporal/frame/{z}/{x}/{y}.png")
async def get_temporal_frame_tile(
    z: int,
    x: int,
    y: int,
    frame_time: str = Query(..., description="帧时间, ISO格式"),
    data_type: str = Query("vehicle"),
    vehicle_type: str = Query("all"),
    road_level: str = Query("all"),
    direction: str = Query("both"),
    db: Session = Depends(get_db),
):
    try:
        frame_dt = datetime.fromisoformat(frame_time.replace('Z', '+00:00'))
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid frame_time: {e}")

    tile_bytes = temporal_heatmap_service.get_frame_tile(
        db, z, x, y, frame_dt,
        data_type=data_type,
        vehicle_type=vehicle_type,
        road_level=road_level,
        direction=direction,
    )
    return StreamingResponse(
        io.BytesIO(tile_bytes),
        media_type="image/png",
        headers={
            "Cache-Control": "public, max-age=86400",
            "Access-Control-Allow-Origin": "*",
            "X-Frame-Time": frame_dt.isoformat(),
        }
    )


@router.get("/temporal/blended/{z}/{x}/{y}.png")
async def get_temporal_blended_tile(
    z: int,
    x: int,
    y: int,
    current_time: str = Query(..., description="精确时间点"),
    alpha: Optional[float] = Query(None, ge=0.0, le=1.0, description="混合比例，默认自动计算"),
    data_type: str = Query("vehicle"),
    vehicle_type: str = Query("all"),
    road_level: str = Query("all"),
    direction: str = Query("both"),
    db: Session = Depends(get_db),
):
    try:
        current_dt = datetime.fromisoformat(current_time.replace('Z', '+00:00'))
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid current_time: {e}")

    tile_bytes = temporal_heatmap_service.get_blended_tile(
        db, z, x, y, current_dt,
        data_type=data_type,
        vehicle_type=vehicle_type,
        road_level=road_level,
        direction=direction,
        alpha=alpha,
    )
    return StreamingResponse(
        io.BytesIO(tile_bytes),
        media_type="image/png",
        headers={
            "Cache-Control": "public, max-age=300",
            "Access-Control-Allow-Origin": "*",
        }
    )


@router.get("/temporal/summary")
async def get_temporal_summary(
    date: Optional[str] = Query(None, description="YYYY-MM-DD, 默认今天"),
    data_type: str = Query("vehicle"),
    vehicle_type: str = Query("all"),
    db: Session = Depends(get_db),
):
    if date:
        try:
            date_dt = datetime.strptime(date, "%Y-%m-%d")
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"Invalid date: {e}")
    else:
        date_dt = None

    summary = temporal_heatmap_service.get_timeline_summary(
        db, date=date_dt, data_type=data_type, vehicle_type=vehicle_type
    )
    return summary


@router.get("/temporal/frame-timestamps")
async def get_frame_timestamps(
    date: Optional[str] = Query(None),
):
    if date:
        try:
            date_dt = datetime.strptime(date, "%Y-%m-%d")
        except Exception:
            date_dt = None
    else:
        date_dt = None
    return {"timestamps": temporal_heatmap_service.get_frame_timestamps(date_dt)}


@router.get("/dimensions/meta")
async def get_dimensions_meta():
    return heatmap_dimension_service.get_all_dimensions()


@router.get("/dimensions/available")
async def get_available_dimensions(
    bbox: Optional[str] = Query(None),
    start_time: Optional[str] = Query(None),
    end_time: Optional[str] = Query(None),
    db: Session = Depends(get_db),
):
    bbox_list = None
    if bbox:
        bbox_list = tuple(float(x) for x in bbox.split(","))
    start_dt = None
    end_dt = None
    if start_time:
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))
    if end_time:
        end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
    return heatmap_dimension_service.get_available_dimensions(
        db, bbox=bbox_list, start_time=start_dt, end_time=end_dt
    )


@router.get("/dimensions/combos")
async def list_dimension_combos(
    include_all: bool = Query(True),
):
    return {"combos": heatmap_dimension_service.get_all_combos(include_all=include_all)}


@router.get("/dimensions/popular")
async def list_popular_dimension_combos(
    top_n: int = Query(20, ge=1, le=200),
    db: Session = Depends(get_db),
):
    return {"combos": heatmap_dimension_service.list_popular_combos(db, top_n=top_n)}


@router.post("/dimensions/validate")
async def validate_dimensions(dimensions: dict):
    ok, msg = heatmap_dimension_service.validate_dimensions(dimensions)
    if not ok:
        raise HTTPException(status_code=400, detail=msg)
    return {"valid": True, "key": heatmap_dimension_service.encode_dimension_key(
        dimensions.get("data_type", "vehicle"),
        dimensions.get("vehicle_type", "all"),
        dimensions.get("road_level", "all"),
        dimensions.get("direction", "both"),
    )}


@router.post("/temporal/pregenerate")
async def trigger_temporal_pregeneration(
    date: Optional[str] = Query(None),
    min_zoom: int = Query(10, ge=0, le=20),
    max_zoom: int = Query(14, ge=0, le=20),
    bbox: Optional[str] = Query(None),
    data_type: str = Query("vehicle"),
    vehicle_type: str = Query("all"),
    road_level: str = Query("all"),
    direction: str = Query("both"),
    current_user=Depends(get_current_active_user),
):
    from app.services.tasks import pregenerate_day_frames_task

    bbox_list = None
    if bbox:
        bbox_list = [float(x) for x in bbox.split(",")]

    result = pregenerate_day_frames_task.apply_async(kwargs={
        "date_iso": date,
        "min_zoom": min_zoom,
        "max_zoom": max_zoom,
        "bbox": bbox_list,
        "data_type": data_type,
        "vehicle_type": vehicle_type,
        "road_level": road_level,
        "direction": direction,
    })

    return {"task_id": result.id, "status": "queued"}


@router.get("/data")
async def get_heatmap_data(
    start_time: Optional[str] = Query(None),
    end_time: Optional[str] = Query(None),
    data_type: str = Query("vehicle"),
    vehicle_type: str = Query("all"),
    bbox: Optional[str] = Query(None),
    db: Session = Depends(get_db),
):
    end_dt = datetime.utcnow()
    start_dt = end_dt - timedelta(hours=1)

    if end_time:
        end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
    if start_time:
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))

    bbox_list = None
    if bbox:
        bbox_list = [float(x) for x in bbox.split(',')]

    result = heatmap_service.get_dynamic_heatmap(
        db, time_range="1h", data_type=data_type,
        vehicle_type=vehicle_type, bbox=bbox_list
    )

    return result


@router.get("/timeline")
async def get_heatmap_timeline(
    start_time: str,
    end_time: str,
    interval: str = Query("1h"),
    data_type: str = Query("vehicle"),
    vehicle_type: str = Query("all"),
    db: Session = Depends(get_db),
):
    start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))
    end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))

    timeline = heatmap_service.generate_timeline_heatmap(
        db, start_dt, end_dt, interval, data_type, vehicle_type
    )

    return {
        "start_time": start_time,
        "end_time": end_time,
        "interval": interval,
        "data_type": data_type,
        "timeline": timeline,
    }


@router.get("/statistics")
async def get_heatmap_statistics(
    start_time: Optional[str] = Query(None),
    end_time: Optional[str] = Query(None),
    data_type: str = Query("vehicle"),
    vehicle_type: str = Query("all"),
    db: Session = Depends(get_db),
):
    end_dt = datetime.utcnow()
    start_dt = end_dt - timedelta(hours=24)

    if end_time:
        end_dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
    if start_time:
        start_dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))

    stats = heatmap_service.get_heatmap_statistics(
        db, start_dt, end_dt, data_type, vehicle_type
    )

    return stats
