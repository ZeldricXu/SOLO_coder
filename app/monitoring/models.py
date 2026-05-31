from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime


class MetricQueryRequest(BaseModel):
    metric_name: str
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    dimensions: Optional[Dict[str, str]] = None
    aggregation: Optional[str] = Field(default="avg", pattern="^(avg|sum|count|max|min|p50|p95|p99)$")


class MetricQueryResponse(BaseModel):
    metric_name: str
    values: List[Dict[str, Any]]
    count: int
    aggregation: str


class SnapshotRequest(BaseModel):
    metrics: Dict[str, float]
    dimensions: Dict[str, str] = Field(default_factory=dict)


class HealthStatus(BaseModel):
    service: str
    status: str
    details: Optional[Dict[str, Any]] = None
    last_check: datetime
