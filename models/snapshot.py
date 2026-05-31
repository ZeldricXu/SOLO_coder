from datetime import datetime
from typing import Any, Dict, Optional

from sqlalchemy import Column, String, JSON, DateTime, Float
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from .base import BaseModel


class MetricValues(Base):
    __tablename__ = "metric_values"

    id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("mval")
    )
    throughput: Mapped[float] = mapped_column(Float, default=0.0)
    latency_p50: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    latency_p95: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    latency_p99: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    error_rate: Mapped[float] = mapped_column(Float, default=0.0)
    success_rate: Mapped[float] = mapped_column(Float, default=1.0)
    queue_length: Mapped[Optional[int]] = mapped_column(Float, nullable=True)
    resource_usage: Mapped[float] = mapped_column(Float, default=0.0)
    custom_metrics: Mapped[Dict[str, float]] = mapped_column(JSON, default=dict)


class MetricDimensions(Base):
    __tablename__ = "metric_dimensions"

    id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("mdim")
    )
    host: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    region: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    service: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    environment: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    module: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    custom_dimensions: Mapped[Dict[str, str]] = mapped_column(JSON, default=dict)


class MetricsSnapshot(Base):
    __tablename__ = "metrics_snapshots"

    snapshot_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("snap")
    )
    timestamp: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, index=True
    )
    metrics: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    dimensions: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    metrics_values_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    dimensions_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    period: Mapped[str] = mapped_column(String(32), default="1m")
    tags: Mapped[Dict[str, str]] = mapped_column(JSON, default=dict)


class MetricsSnapshotCreate(BaseModel):
    metrics: Dict[str, float]
    dimensions: Dict[str, str]
    period: str = "1m"
    tags: Dict[str, str] = {}


class MetricsSnapshotResponse(BaseModel):
    snapshot_id: str
    timestamp: datetime
    metrics: Dict[str, Any]
    dimensions: Dict[str, Any]
    period: str
    tags: Dict[str, str]
