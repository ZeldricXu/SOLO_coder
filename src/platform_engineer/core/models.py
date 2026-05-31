from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from uuid import uuid4
from pydantic import BaseModel, Field


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:8]}"


class BaseEntity(BaseModel):
    id: str = Field(default_factory=lambda: generate_id("ent"))
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    class Config:
        json_encoders = {datetime: lambda v: v.isoformat()}


class CoreEntity(BaseEntity):
    type: str
    status: str
    attributes: Dict[str, Any] = Field(default_factory=dict)

    def update_attributes(self, updates: Dict[str, Any]) -> None:
        self.attributes.update(updates)
        self.updated_at = datetime.now(timezone.utc)


class ConfigDefinition(BaseEntity):
    config_id: str = Field(default_factory=lambda: generate_id("cfg"))
    namespace: str
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: Optional[datetime] = None

    def bump_version(self) -> None:
        self.version += 1
        self.updated_at = datetime.now(timezone.utc)


class RunInstance(BaseEntity):
    run_id: str = Field(default_factory=lambda: generate_id("run"))
    entity_id: str
    phase: str
    progress: float = 0.0
    started_at: Optional[datetime] = Field(default_factory=lambda: datetime.now(timezone.utc))
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None

    def update_progress(self, progress: float) -> None:
        self.progress = max(0.0, min(1.0, progress))
        self.updated_at = datetime.now(timezone.utc)

    def mark_completed(self, error_detail: Optional[str] = None) -> None:
        self.completed_at = datetime.now(timezone.utc)
        self.phase = "completed"
        self.progress = 1.0
        self.error_detail = error_detail
        self.updated_at = self.completed_at


class StatsSnapshot(BaseEntity):
    snapshot_id: str = Field(default_factory=lambda: generate_id("snap"))
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    metrics: Dict[str, float] = Field(default_factory=dict)
    dimensions: Dict[str, str] = Field(default_factory=dict)

    def get_metric(self, name: str) -> Optional[float]:
        return self.metrics.get(name)
