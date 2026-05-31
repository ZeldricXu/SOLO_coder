from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core import get_db
from models import ResponseModel, PaginatedResponse
from .schemas import (
    LogQueryRequest,
    MetricResponse,
    RateLimitConfigCreate,
    RateLimitConfigResponse,
    RequestLogResponse,
    TraceDetailResponse,
)
from .service import APIGatewayService

router = APIRouter(prefix="/api/v1/gateway", tags=["API Gateway"])


@router.get("/logs", response_model=PaginatedResponse[RequestLogResponse])
async def get_request_logs(
    page: int = 1,
    page_size: int = 20,
    trace_id: Optional[str] = None,
    service_name: Optional[str] = None,
    method: Optional[str] = None,
    path: Optional[str] = None,
    status_code: Optional[int] = None,
    user_id: Optional[str] = None,
    client_ip: Optional[str] = None,
    db: AsyncSession = Depends(get_db),
):
    service = APIGatewayService(db)
    query_params = LogQueryRequest(
        trace_id=trace_id,
        service_name=service_name,
        method=method,
        path=path,
        status_code=status_code,
        user_id=user_id,
        client_ip=client_ip,
    )
    logs = await service.get_request_logs(query_params, page, page_size)
    return PaginatedResponse(
        data=[RequestLogResponse.model_validate(l) for l in logs],
        total=len(logs),
        page=page,
        page_size=page_size,
    )


@router.get("/traces/{trace_id}", response_model=ResponseModel[TraceDetailResponse])
async def get_trace_detail(
    trace_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = APIGatewayService(db)
    trace_detail = await service.get_trace_detail(trace_id)
    return ResponseModel(
        data=TraceDetailResponse(
            trace_id=trace_detail["trace_id"],
            spans=trace_detail["spans"],
            total_duration_ms=trace_detail["total_duration_ms"],
            start_time=trace_detail["start_time"],
            end_time=trace_detail["end_time"],
            status=trace_detail["status"],
        )
    )


@router.get("/metrics", response_model=ResponseModel[MetricResponse])
async def get_metrics(
    hours: int = Query(24, ge=1, le=720),
    db: AsyncSession = Depends(get_db),
):
    service = APIGatewayService(db)
    metrics = await service.get_metrics(hours)
    return ResponseModel(data=metrics)


@router.post("/rate-limits", response_model=ResponseModel[RateLimitConfigResponse])
async def create_rate_limit(
    data: RateLimitConfigCreate,
    db: AsyncSession = Depends(get_db),
):
    service = APIGatewayService(db)
    rate_limit = await service.create_rate_limit(data)
    return ResponseModel(data=RateLimitConfigResponse.model_validate(rate_limit))


@router.get("/rate-limits", response_model=PaginatedResponse[RateLimitConfigResponse])
async def list_rate_limits(
    page: int = 1,
    page_size: int = 20,
    db: AsyncSession = Depends(get_db),
):
    service = APIGatewayService(db)
    skip = (page - 1) * page_size
    rate_limits = await service.list_rate_limits(skip, page_size)
    return PaginatedResponse(
        data=[RateLimitConfigResponse.model_validate(r) for r in rate_limits],
        total=len(rate_limits),
        page=page,
        page_size=page_size,
    )


@router.delete("/rate-limits/{rate_limit_id}", response_model=ResponseModel)
async def delete_rate_limit(
    rate_limit_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = APIGatewayService(db)
    await service.delete_rate_limit(rate_limit_id)
    return ResponseModel(message="Rate limit deleted successfully")
