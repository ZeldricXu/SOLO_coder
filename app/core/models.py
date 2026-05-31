from pydantic import BaseModel, Field, validator
from datetime import datetime
from typing import Any, Dict, List, Optional
from enum import Enum
import uuid


class ResourceStatus(str, Enum):
    PROVISIONING = "provisioning"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    ACTIVE = "active"


class PhaseStatus(str, Enum):
    PENDING = "pending"
    EXECUTING = "executing"
    COMPLETED = "completed"
    FAILED = "failed"


class SensitivityLevel(str, Enum):
    PUBLIC = "public"
    INTERNAL = "internal"
    CONFIDENTIAL = "confidential"
    RESTRICTED = "restricted"


class DataCategory(str, Enum):
    PII = "pii"
    FINANCIAL = "financial"
    HEALTH = "health"
    LOCATION = "location"
    GENERAL = "general"


class BaseEntity(BaseModel):
    id: str = Field(default_factory=lambda: f"ent_{uuid.uuid4().hex[:8]}")
    type: str = "resource"
    status: str = ResourceStatus.ACTIVE
    attributes: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        use_enum_values = True
        json_encoders = {datetime: lambda v: v.isoformat() + "Z"}


class ConfigEntity(BaseModel):
    config_id: str = Field(default_factory=lambda: f"cfg_{uuid.uuid4().hex[:8]}")
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        use_enum_values = True
        json_encoders = {datetime: lambda v: v.isoformat() + "Z"}


class RunInstance(BaseModel):
    run_id: str = Field(default_factory=lambda: f"run_{uuid.uuid4().hex[:8]}")
    entity_id: str
    phase: str = PhaseStatus.PENDING
    progress: float = 0.0
    started_at: datetime = Field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None

    class Config:
        use_enum_values = True
        json_encoders = {datetime: lambda v: v.isoformat() + "Z"}


class Snapshot(BaseModel):
    snapshot_id: str = Field(default_factory=lambda: f"snap_{uuid.uuid4().hex[:8]}")
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    metrics: Dict[str, Any] = Field(default_factory=lambda: {"throughput": 0, "latency_p99": 0, "error_rate": 0.0})
    dimensions: Dict[str, str] = Field(default_factory=dict)

    class Config:
        use_enum_values = True
        json_encoders = {datetime: lambda v: v.isoformat() + "Z"}


class ResourceRequest(BaseModel):
    type: str = "workflow"
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class ResourceResponse(BaseModel):
    id: str
    status: str = ResourceStatus.PROVISIONING


class StatusResponse(BaseModel):
    id: str
    status: str
    progress: float = 0.0


class BatchOperation(BaseModel):
    action: str
    id: str
    params: Dict[str, Any] = Field(default_factory=dict)


class BatchResponse(BaseModel):
    batch_id: str = Field(default_factory=lambda: f"batch_{uuid.uuid4().hex[:8]}")
    results: List[Dict[str, Any]] = Field(default_factory=list)


class APIResponse(BaseModel):
    code: int
    data: Optional[Any] = None
    message: str = "success"
    timestamp: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        json_encoders = {datetime: lambda v: v.isoformat() + "Z"}


class DataClassificationResult(BaseModel):
    field_name: str
    category: DataCategory
    sensitivity: SensitivityLevel
    confidence: float
    matched_patterns: List[str]


class AuditLogEntry(BaseModel):
    log_id: str = Field(default_factory=lambda: f"audit_{uuid.uuid4().hex[:8]}")
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    actor: str
    action: str
    resource_type: str
    resource_id: Optional[str] = None
    details: Dict[str, Any] = Field(default_factory=dict)
    status: str = "success"
    previous_hash: str = "0" * 64
    current_hash: str

    class Config:
        json_encoders = {datetime: lambda v: v.isoformat() + "Z"}
