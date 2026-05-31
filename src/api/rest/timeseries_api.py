from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

router = APIRouter()
_ts_service = None


def _get_service():
    global _ts_service
    if _ts_service is None:
        from src.service.timeseries_service import TimeseriesService
        _ts_service = TimeseriesService()
    return _ts_service


class IngestRequest(BaseModel):
    metric_name: str
    timestamps: List[int]
    values: List[float]
    tags: Optional[Dict[str, str]] = None


class CompressRequest(BaseModel):
    metric_name: str
    timestamps: List[int]
    values: List[float]


class DownsampleRequest(BaseModel):
    metric_name: str
    timestamps: List[int]
    values: List[float]
    interval: str
    aggregation: Optional[str] = None


class QueryRequest(BaseModel):
    metric_name: str
    start_ts: int
    end_ts: int
    resolution: Optional[str] = None
    tags: Optional[Dict[str, str]] = None


class WritePointRequest(BaseModel):
    metric_name: str
    timestamp: int
    value: float
    tags: Optional[Dict[str, str]] = None


class WritePointsRequest(BaseModel):
    points: List[Dict[str, Any]]


@router.post("/ingest")
async def ingest(request: IngestRequest):
    service = _get_service()
    return service.ingest(request.metric_name, request.timestamps, request.values, request.tags)


@router.post("/compress")
async def compress(request: CompressRequest):
    service = _get_service()
    return service.compress(request.metric_name, request.timestamps, request.values)


@router.post("/downsample")
async def downsample(request: DownsampleRequest):
    service = _get_service()
    return service.downsample(request.metric_name, request.timestamps, request.values, request.interval, request.aggregation)


@router.post("/query")
async def query(request: QueryRequest):
    service = _get_service()
    return {"data": service.query(request.metric_name, request.start_ts, request.end_ts, request.resolution, request.tags)}


@router.post("/write")
async def write_point(request: WritePointRequest):
    service = _get_service()
    service.write_point(request.metric_name, request.timestamp, request.value, request.tags)
    return {"status": "ok"}


@router.post("/write/batch")
async def write_points(request: WritePointsRequest):
    service = _get_service()
    service.write_points(request.points)
    return {"status": "ok", "count": len(request.points)}


@router.get("/retention")
async def get_retention_info():
    service = _get_service()
    return {"retention_levels": service.get_retention_info()}


@router.post("/cleanup")
async def cleanup_expired(current_timestamp: int):
    service = _get_service()
    return service.cleanup_expired(current_timestamp)


@router.get("/compression/stats")
async def get_compression_stats():
    service = _get_service()
    return service.get_compression_stats()
