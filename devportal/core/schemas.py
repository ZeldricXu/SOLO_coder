from datetime import datetime, timezone
from typing import Any, Dict, Generic, List, Optional, TypeVar
from pydantic import BaseModel, Field, ConfigDict, field_validator

T = TypeVar("T")


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class APIResponse(BaseModel, Generic[T]):
    code: int = Field(default=200, ge=100, le=599)
    message: str = "success"
    data: Optional[T] = None
    timestamp: datetime = Field(default_factory=utc_now)

    model_config = ConfigDict(from_attributes=True)


class PaginatedResponse(BaseModel, Generic[T]):
    code: int = 200
    message: str = "success"
    data: List[T]
    total: int
    page: int
    page_size: int
    timestamp: datetime = Field(default_factory=utc_now)


class EntityBase(BaseModel):
    type: str = "entity"
    status: str = "pending"
    attributes: Dict[str, Any] = Field(default_factory=dict)


class EntityCreate(EntityBase):
    pass


class EntityUpdate(BaseModel):
    type: Optional[str] = None
    status: Optional[str] = None
    attributes: Optional[Dict[str, Any]] = None


class EntityResponse(EntityBase):
    id: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class ConfigBase(BaseModel):
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True


class ConfigCreate(ConfigBase):
    pass


class ConfigUpdate(BaseModel):
    namespace: Optional[str] = None
    parameters: Optional[Dict[str, Any]] = None
    enabled: Optional[bool] = None


class ConfigResponse(ConfigBase):
    config_id: str
    applied_at: datetime

    model_config = ConfigDict(from_attributes=True)


class RunInstanceBase(BaseModel):
    entity_id: str
    phase: str = "initializing"
    progress: float = Field(default=0.0, ge=0.0, le=1.0)
    error_detail: Optional[Dict[str, Any]] = None


class RunInstanceCreate(RunInstanceBase):
    pass


class RunInstanceUpdate(BaseModel):
    phase: Optional[str] = None
    progress: Optional[float] = None
    error_detail: Optional[Dict[str, Any]] = None
    completed: bool = False


class RunInstanceResponse(RunInstanceBase):
    run_id: str
    started_at: datetime
    completed_at: Optional[datetime]

    model_config = ConfigDict(from_attributes=True)


class SnapshotBase(BaseModel):
    metrics: Dict[str, Any] = Field(default_factory=dict)
    dimensions: Dict[str, Any] = Field(default_factory=dict)


class SnapshotCreate(SnapshotBase):
    pass


class SnapshotResponse(SnapshotBase):
    snapshot_id: str
    timestamp: datetime

    model_config = ConfigDict(from_attributes=True)


class BatchOperation(BaseModel):
    action: str
    id: str
    params: Dict[str, Any] = Field(default_factory=dict)


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class BatchResult(BaseModel):
    id: str
    action: str
    success: bool
    message: str = ""
    data: Optional[Dict[str, Any]] = None


class BatchResponse(BaseModel):
    code: int = 200
    message: str = "success"
    batch_id: str
    results: List[BatchResult]
    timestamp: datetime = Field(default_factory=utc_now)
