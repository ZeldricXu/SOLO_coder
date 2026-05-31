from typing import Optional, Dict, Any, List
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from datetime import datetime

from application.services.telemetry_service import TelemetryService

router = APIRouter(prefix="/telemetry", tags=["telemetry"])


class TelemetryIngestRequest(BaseModel):
    device_id: str
    data: Dict[str, Any]
    timestamp: Optional[datetime] = None


class BatchIngestRequest(BaseModel):
    device_id: str
    data_points: List[Dict[str, Any]]


class AggregationRuleRequest(BaseModel):
    device_id: str
    metric: str
    aggregation_type: str
    interval_seconds: int


_telemetry_service: Optional[TelemetryService] = None


def set_telemetry_service(service: TelemetryService) -> None:
    global _telemetry_service
    _telemetry_service = service


def get_telemetry_service() -> TelemetryService:
    if _telemetry_service is None:
        raise RuntimeError("TelemetryService not initialized")
    return _telemetry_service


@router.post("/ingest")
def ingest_telemetry(request: TelemetryIngestRequest):
    service = get_telemetry_service()
    result = service.ingest_telemetry(
        device_id=request.device_id,
        data=request.data,
        timestamp=request.timestamp,
    )
    return result


@router.post("/batch")
def batch_ingest(request: BatchIngestRequest):
    service = get_telemetry_service()
    result = service.batch_ingest(
        device_id=request.device_id,
        data_points=request.data_points,
    )
    return result


@router.get("/aggregated")
def get_aggregated_data(
    device_id: str,
    metric: str,
    start_time: datetime,
    end_time: datetime,
    aggregation_type: str = Query("average", description="average, sum, min, max, count"),
):
    service = get_telemetry_service()
    data = service.get_aggregated_data(
        device_id=device_id,
        metric=metric,
        start_time=start_time,
        end_time=end_time,
        aggregation_type=aggregation_type,
    )
    if not data:
        raise HTTPException(status_code=404, detail="No aggregated data found")
    return {
        "device_id": device_id,
        "metric": metric,
        "aggregation_type": aggregation_type,
        "value": data.value,
        "count": data.count,
        "start_time": data.start_time.isoformat(),
        "end_time": data.end_time.isoformat(),
    }


@router.post("/aggregation-rules")
def add_aggregation_rule(request: AggregationRuleRequest):
    service = get_telemetry_service()
    rule_id = service.add_aggregation_rule(
        device_id=request.device_id,
        metric=request.metric,
        aggregation_type=request.aggregation_type,
        interval_seconds=request.interval_seconds,
    )
    return {"rule_id": rule_id, "message": "Aggregation rule added successfully"}


@router.get("/aggregation-rules")
def get_aggregation_rules(device_id: Optional[str] = None):
    service = get_telemetry_service()
    rules = service.get_aggregation_rules(device_id=device_id)
    return {"rules": rules, "count": len(rules)}


@router.delete("/aggregation-rules/{rule_id}")
def remove_aggregation_rule(rule_id: str):
    service = get_telemetry_service()
    success = service.remove_aggregation_rule(rule_id)
    if not success:
        raise HTTPException(status_code=404, detail="Aggregation rule not found")
    return {"message": "Aggregation rule removed successfully"}


@router.post("/run-aggregation")
def run_aggregation():
    service = get_telemetry_service()
    result = service.run_aggregation()
    return result


@router.get("/cache-stats")
def get_cache_stats():
    service = get_telemetry_service()
    stats = service.get_offline_cache_stats()
    return stats


@router.post("/sync-offline")
def sync_offline_data():
    service = get_telemetry_service()
    result = service.sync_offline_data()
    return result


@router.post("/read-and-ingest")
def read_and_ingest(device_id: str, points: str = Query(..., description="Comma-separated list of data points")):
    service = get_telemetry_service()
    point_list = [p.strip() for p in points.split(",")]
    result = service.read_and_ingest(device_id, point_list)
    return result
