from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional

from sqlalchemy import JSON, DateTime, Float, String
from sqlalchemy import Enum as SQLEnum
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now

from .base import BaseModel


class RunPhase(str, Enum):
    INITIALIZING = "initializing"
    PROCESSING = "processing"
    FINALIZING = "finalizing"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class RunStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    TIMEOUT = "timeout"


class RunInstance(Base):
    __tablename__ = "run_instances"

    run_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("run")
    )
    entity_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    phase: Mapped[RunPhase] = mapped_column(
        SQLEnum(RunPhase), default=RunPhase.INITIALIZING, index=True
    )
    status: Mapped[RunStatus] = mapped_column(
        SQLEnum(RunStatus), default=RunStatus.PENDING, index=True
    )
    progress: Mapped[float] = mapped_column(Float, default=0.0)
    started_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )
    completed_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    error_detail: Mapped[Optional[Dict[str, Any]]] = mapped_column(JSON, nullable=True)
    context: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True, nullable=True)
    trace_id: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    results: Mapped[Optional[Dict[str, Any]]] = mapped_column(JSON, nullable=True)


class RunInstanceCreate(BaseModel):
    entity_id: str
    context: Dict[str, Any] = {}
    tenant_id: Optional[str] = None
    trace_id: Optional[str] = None
    phase: RunPhase = RunPhase.INITIALIZING
    status: RunStatus = RunStatus.PENDING


class RunInstanceResponse(BaseModel):
    run_id: str
    entity_id: str
    phase: RunPhase
    status: RunStatus
    progress: float
    started_at: Optional[datetime]
    completed_at: Optional[datetime]
    error_detail: Optional[Dict[str, Any]]
    context: Dict[str, Any]
    tenant_id: Optional[str]
    trace_id: Optional[str]
    results: Optional[Dict[str, Any]]
