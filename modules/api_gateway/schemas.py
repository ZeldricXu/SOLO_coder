from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class RequestLogResponse(BaseModel):
    id: str
    trace_id: str
    span_id: str
    parent_span_id: Optional[str]
    service_name: str
    method: str
    path: str
    status_code: Optional[int]
    client_ip: Optional[str]
    user_agent: Optional[str]
    user_id: Optional[str]
    duration_ms: Optional[float]
    error_message: Optional[str]
    started_at: datetime
    completed_at: Optional[datetime]
    created_at: datetime

    class Config:
        from_attributes = True


class TraceSpanResponse(BaseModel):
    id: str
    trace_id: str
    span_id: str
    parent_span_id: Optional[str]
    name: str
    service_name: str
    kind: Optional[str]
    attributes: Dict[str, Any]
    status: Optional[str]
    status_message: Optional[str]
    started_at: datetime
    ended_at: Optional[datetime]
    duration_ms: Optional[float]

    class Config:
        from_attributes = True


class TraceDetailResponse(BaseModel):
    trace_id: str
    spans: List[TraceSpanResponse]
    total_duration_ms: Optional[float]
    start_time: Optional[datetime]
    end_time: Optional[datetime]
    status: str


class LogQueryRequest(BaseModel):
    trace_id: Optional[str] = None
    service_name: Optional[str] = None
    method: Optional[str] = None
    path: Optional[str] = None
    status_code: Optional[int] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    user_id: Optional[str] = None
    client_ip: Optional[str] = None


class MetricResponse(BaseModel):
    total_requests: int
    success_count: int
    error_count: int
    avg_duration_ms: float
    p95_duration_ms: float
    p99_duration_ms: float
    requests_per_minute: float
    top_endpoints: List[Dict[str, Any]]
    error_rates: List[Dict[str, Any]]
    timestamp: datetime


class RateLimitConfigCreate(BaseModel):
    path: str
    method: str
    limit_per_minute: int = 60
    limit_per_hour: int = 1000
    limit_per_day: int = 10000
    enabled: bool = True
    client_key: Optional[str] = None


class RateLimitConfigResponse(BaseModel):
    id: str
    path: str
    method: str
    limit_per_minute: int
    limit_per_hour: int
    limit_per_day: int
    enabled: bool
    client_key: Optional[str]
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class GatewayRouteCreate(BaseModel):
    path: str
    target_service: str
    target_url: str
    method: Optional[str] = None
    timeout_ms: int = 30000
    retry_count: int = 0
    enabled: bool = True


class GatewayRouteResponse(BaseModel):
    id: str
    path: str
    target_service: str
    target_url: str
    method: Optional[str]
    timeout_ms: int
    retry_count: int
    enabled: bool
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True
