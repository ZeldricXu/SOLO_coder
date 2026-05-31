from fastapi import APIRouter, Depends, Query, Response
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional
from datetime import datetime

from app.database import get_db
from app.schemas import (
    MetricSnapshotCreate,
    MetricSnapshotResponse,
    MetricsQuery,
    MetricsResponse,
    AuditLogCreate,
    AuditLogResponse,
    BaseResponse,
    PaginatedResponse,
)
from app.monitoring.service import MonitoringService, MetricsCollector
from app.logging import LogContext

router = APIRouter(prefix="/api/v1/monitoring", tags=["Monitoring"])


@router.post("/snapshots", response_model=BaseResponse[MetricSnapshotResponse])
async def create_snapshot(
    snapshot_in: MetricSnapshotCreate,
    db: AsyncSession = Depends(get_db),
):
    service = MonitoringService(db)
    snapshot = await service.record_metric_snapshot(snapshot_in)
    return BaseResponse(
        code=201,
        data=snapshot,
        request_id=LogContext.get_request_id(),
        message="Metric snapshot recorded successfully",
    )


@router.get("/snapshots/{snapshot_id}", response_model=BaseResponse[MetricSnapshotResponse])
async def get_snapshot(
    snapshot_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = MonitoringService(db)
    snapshot = await service.get_snapshot(snapshot_id)
    return BaseResponse(data=snapshot, request_id=LogContext.get_request_id())


@router.get("/snapshots", response_model=BaseResponse[PaginatedResponse[MetricSnapshotResponse]])
async def list_snapshots(
    start_time: Optional[datetime] = Query(None, description="Start time"),
    end_time: Optional[datetime] = Query(None, description="End time"),
    host: Optional[str] = Query(None, description="Filter by host"),
    service: Optional[str] = Query(None, description="Filter by service"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=5000, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    monitoring_service = MonitoringService(db)
    skip = (page - 1) * page_size
    snapshots, total = await monitoring_service.list_snapshots(
        start_time=start_time,
        end_time=end_time,
        host=host,
        service=service,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=snapshots,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.post("/query", response_model=BaseResponse[MetricsResponse])
async def query_metrics(
    query: MetricsQuery,
    use_cache: bool = Query(True, description="Enable query caching"),
    aggregate: bool = Query(False, description="Enable time window aggregation"),
    aggregation_window: int = Query(60, ge=1, le=3600, description="Aggregation window in seconds"),
    db: AsyncSession = Depends(get_db),
):
    service = MonitoringService(db)
    result = await service.query_metrics(
        query,
        use_cache=use_cache,
        aggregate=aggregate,
        aggregation_window=aggregation_window,
    )
    return BaseResponse(data=MetricsResponse(**result), request_id=LogContext.get_request_id())


@router.get("/metrics")
async def get_prometheus_metrics():
    collector = MetricsCollector()
    metrics_data = collector.generate_metrics()
    return Response(content=metrics_data, media_type=collector.content_type)


@router.get("/cache/stats", response_model=BaseResponse)
async def get_cache_stats(
    db: AsyncSession = Depends(get_db),
):
    service = MonitoringService(db)
    stats = await service.get_cache_stats()
    return BaseResponse(data=stats, request_id=LogContext.get_request_id())


@router.post("/cache/clear", response_model=BaseResponse)
async def clear_cache(
    db: AsyncSession = Depends(get_db),
):
    service = MonitoringService(db)
    await service.clear_cache()
    return BaseResponse(
        data={"cleared": True},
        request_id=LogContext.get_request_id(),
        message="Query cache cleared successfully",
    )


@router.post("/audit-logs", response_model=BaseResponse[AuditLogResponse])
async def create_audit_log(
    audit_in: AuditLogCreate,
    db: AsyncSession = Depends(get_db),
):
    service = MonitoringService(db)
    audit_log = await service.record_audit_log(audit_in)
    return BaseResponse(
        code=201,
        data=audit_log,
        request_id=LogContext.get_request_id(),
        message="Audit log recorded successfully",
    )


@router.get("/audit-logs", response_model=BaseResponse[PaginatedResponse[AuditLogResponse]])
async def list_audit_logs(
    user_id: Optional[UUID] = Query(None, description="Filter by user ID"),
    action: Optional[str] = Query(None, description="Filter by action"),
    resource_type: Optional[str] = Query(None, description="Filter by resource type"),
    start_time: Optional[datetime] = Query(None, description="Start time"),
    end_time: Optional[datetime] = Query(None, description="End time"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=5000, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    service = MonitoringService(db)
    skip = (page - 1) * page_size
    logs, total = await service.list_audit_logs(
        user_id=user_id,
        action=action,
        resource_type=resource_type,
        start_time=start_time,
        end_time=end_time,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=logs,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )
