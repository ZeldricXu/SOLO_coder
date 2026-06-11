from fastapi import APIRouter, Depends, Query
from fastapi.responses import StreamingResponse, JSONResponse
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from typing import Optional, List

from app.database import get_db
from app.heatmap import heatmap_service
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
