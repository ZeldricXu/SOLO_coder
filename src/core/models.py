from pydantic import BaseModel, Field
from datetime import datetime
from typing import Any, Dict, Optional
import uuid


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


class CoreEntity(BaseModel):
    id: str = Field(default_factory=lambda: generate_id("ent"))
    type: str
    status: str = "active"
    attributes: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class ConfigDefinition(BaseModel):
    config_id: str = Field(default_factory=lambda: generate_id("cfg"))
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: datetime = Field(default_factory=datetime.utcnow)


class RunInstance(BaseModel):
    run_id: str = Field(default_factory=lambda: generate_id("run"))
    entity_id: str
    phase: str = "initializing"
    progress: float = 0.0
    started_at: datetime = Field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None


class MetricsSnapshot(BaseModel):
    snapshot_id: str = Field(default_factory=lambda: generate_id("snap"))
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    metrics: Dict[str, float] = Field(default_factory=dict)
    dimensions: Dict[str, str] = Field(default_factory=dict)
