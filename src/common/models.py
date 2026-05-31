from __future__ import annotations

from datetime import datetime, UTC
from enum import Enum
from typing import Any, Dict, Optional
from uuid import uuid4

from pydantic import BaseModel, Field, ConfigDict


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:8]}"


def utc_now() -> datetime:
    return datetime.now(UTC)


class Status(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class EntityType(str, Enum):
    RECORD = "record"
    JOB = "job"
    TASK = "task"
    RESOURCE = "resource"


class BaseEntity(BaseModel):
    model_config = ConfigDict(from_attributes=True, extra="allow")

    id: str = Field(default_factory=lambda: generate_id("ent"))
    type: EntityType = EntityType.RECORD
    status: Status = Status.PENDING
    attributes: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class ConfigDefinition(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    config_id: str = Field(default_factory=lambda: generate_id("cfg"))
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: datetime = Field(default_factory=utc_now)


class RunInstance(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    run_id: str = Field(default_factory=lambda: generate_id("run"))
    entity_id: str
    phase: str = "initializing"
    progress: float = 0.0
    started_at: datetime = Field(default_factory=utc_now)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None


class Metrics(BaseModel):
    throughput: float = 0.0
    latency_p99: float = 0.0
    error_rate: float = 0.0


class StatsSnapshot(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    snapshot_id: str = Field(default_factory=lambda: generate_id("snap"))
    timestamp: datetime = Field(default_factory=utc_now)
    metrics: Metrics = Field(default_factory=Metrics)
    dimensions: Dict[str, str] = Field(default_factory=dict)


class APIResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: Optional[Any] = None


class PaginationParams(BaseModel):
    page: int = 1
    page_size: int = 20

    @property
    def offset(self) -> int:
        return (self.page - 1) * self.page_size

    @property
    def limit(self) -> int:
        return self.page_size
