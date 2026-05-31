from datetime import datetime, timezone
from typing import Any, Dict, Optional

from pydantic import BaseModel as PydanticBaseModel, Field


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class BaseModel(PydanticBaseModel):
    model_config = {"populate_by_name": True, "from_attributes": True}


class EntityModel(BaseModel):
    id: str
    type: str
    status: str
    attributes: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class ConfigModel(BaseModel):
    config_id: str
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: datetime = Field(default_factory=utc_now)


class RunInstanceModel(BaseModel):
    run_id: str
    entity_id: str
    phase: str = "initializing"
    progress: float = 0.0
    started_at: datetime = Field(default_factory=utc_now)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None


class SnapshotModel(BaseModel):
    snapshot_id: str
    timestamp: datetime = Field(default_factory=utc_now)
    metrics: Dict[str, float] = Field(default_factory=dict)
    dimensions: Dict[str, str] = Field(default_factory=dict)


class CommandRecord(BaseModel):
    command_id: str
    command_type: str
    payload: Dict[str, Any] = Field(default_factory=dict)
    issued_by: str = "system"
    issued_at: datetime = Field(default_factory=utc_now)
    correlation_id: Optional[str] = None


class AuditLogEntry(BaseModel):
    log_id: str
    timestamp: datetime = Field(default_factory=utc_now)
    action: str
    actor: str
    resource: str
    details: Dict[str, Any] = Field(default_factory=dict)
    command_id: Optional[str] = None
    correlation_id: Optional[str] = None


class TaskDefinition(BaseModel):
    task_id: str
    name: str
    dependencies: list[str] = Field(default_factory=list)
    handler: str
    parameters: Dict[str, Any] = Field(default_factory=dict)
    timeout: int = 300
    retries: int = 3


class WorkflowDefinition(BaseModel):
    workflow_id: str
    name: str
    tasks: list[TaskDefinition] = Field(default_factory=list)
    parameters: Dict[str, Any] = Field(default_factory=dict)
