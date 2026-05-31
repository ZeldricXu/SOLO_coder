from datetime import datetime
from typing import Any, Dict, List, Optional, Generic, TypeVar
from pydantic import BaseModel, Field, ConfigDict

T = TypeVar("T")


class BaseSchema(BaseModel):
    model_config = ConfigDict(
        from_attributes=True,
        populate_by_name=True,
        arbitrary_types_allowed=True,
    )


class ResponseModel(BaseSchema, Generic[T]):
    code: int = Field(default=200)
    message: str = Field(default="success")
    data: Optional[T] = None


class PaginatedResponse(BaseSchema, Generic[T]):
    code: int = Field(default=200)
    message: str = Field(default="success")
    data: List[T]
    total: int
    page: int
    page_size: int


class EntityBase(BaseSchema):
    type: str
    status: str = "pending"
    attributes: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, Any] = Field(default_factory=dict)


class EntityCreate(EntityBase):
    pass


class EntityUpdate(BaseSchema):
    status: Optional[str] = None
    attributes: Optional[Dict[str, Any]] = None
    labels: Optional[Dict[str, Any]] = None
    metadata: Optional[Dict[str, Any]] = None


class EntityResponse(EntityBase):
    id: str
    created_at: datetime
    updated_at: datetime
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ConfigBase(BaseSchema):
    config_id: str
    namespace: str = "default"
    version: str = "1.0.0"
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: Optional[datetime] = None


class ConfigCreate(ConfigBase):
    pass


class ConfigUpdate(BaseSchema):
    parameters: Optional[Dict[str, Any]] = None
    enabled: Optional[bool] = None
    version: Optional[str] = None


class ConfigResponse(ConfigBase):
    id: str
    created_at: datetime
    updated_at: datetime


class RunInstanceBase(BaseSchema):
    run_id: str
    entity_id: str
    phase: str = "pending"
    progress: float = 0.0
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error_detail: Optional[Dict[str, Any]] = None


class RunInstanceCreate(RunInstanceBase):
    pass


class RunInstanceUpdate(BaseSchema):
    phase: Optional[str] = None
    progress: Optional[float] = None
    completed_at: Optional[datetime] = None
    error_detail: Optional[Dict[str, Any]] = None


class RunInstanceResponse(RunInstanceBase):
    id: str
    created_at: datetime
    updated_at: datetime


class SnapshotBase(BaseSchema):
    snapshot_id: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    metrics: Dict[str, Any] = Field(default_factory=dict)
    dimensions: Dict[str, Any] = Field(default_factory=dict)


class SnapshotCreate(SnapshotBase):
    pass


class SnapshotResponse(SnapshotBase):
    id: str
    created_at: datetime


class BatchOperation(BaseSchema):
    action: str
    id: str
    parameters: Dict[str, Any] = Field(default_factory=dict)


class BatchRequest(BaseSchema):
    operations: List[BatchOperation]


class BatchResult(BaseSchema):
    id: str
    success: bool
    error: Optional[str] = None


class BatchResponse(BaseSchema):
    batch_id: str
    results: List[BatchResult]


class StatusResponse(BaseSchema):
    id: str
    status: str
    progress: float = 0.0
    phase: Optional[str] = None
    updated_at: datetime
