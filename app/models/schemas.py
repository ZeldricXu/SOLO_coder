from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4

from pydantic import BaseModel, Field


class ResourceStatus(str, Enum):
    PROVISIONING = "provisioning"
    RUNNING = "running"
    STOPPED = "stopped"
    FAILED = "failed"
    COMPLETED = "completed"


class ResourceCreate(BaseModel):
    type: str = Field(..., description="资源类型，如 workflow, database, storage 等")
    config: Dict[str, Any] = Field(default_factory=dict, description="资源配置参数")
    labels: Dict[str, str] = Field(default_factory=dict, description="资源标签")


class ResourceResponse(BaseModel):
    code: int = 201
    data: Dict[str, Any]


class BatchOperation(BaseModel):
    action: str
    id: str


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class BatchResultItem(BaseModel):
    id: str
    success: bool
    message: Optional[str] = None


class BatchResult(BaseModel):
    code: int = 200
    data: Dict[str, Any]


class EntityModel(BaseModel):
    id: str = Field(default_factory=lambda: f"ent_{uuid4().hex[:8]}")
    type: str
    status: str = "completed"
    attributes: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class ConfigModel(BaseModel):
    config_id: str = Field(default_factory=lambda: f"cfg_{uuid4().hex[:8]}")
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: Optional[datetime] = None


class RunPhase(str, Enum):
    INITIALIZING = "initializing"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"


class RunModel(BaseModel):
    run_id: str = Field(default_factory=lambda: f"run_{uuid4().hex[:8]}")
    entity_id: str
    phase: RunPhase = RunPhase.INITIALIZING
    progress: float = 0.0
    started_at: datetime = Field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None


class MetricsSnapshotModel(BaseModel):
    snapshot_id: str = Field(default_factory=lambda: f"snap_{uuid4().hex[:8]}")
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    metrics: Dict[str, float] = Field(default_factory=dict)
    dimensions: Dict[str, str] = Field(default_factory=dict)


class ErrorResponse(BaseModel):
    code: int
    error: str
    details: Optional[Dict[str, Any]] = None
