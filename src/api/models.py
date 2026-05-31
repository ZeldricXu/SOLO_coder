from typing import Any, Dict, List, Optional, Generic, TypeVar
from datetime import datetime
from pydantic import BaseModel, Field, ConfigDict

T = TypeVar('T')


class ApiResponse(BaseModel, Generic[T]):
    code: int = 200
    message: str = "success"
    data: Optional[T] = None
    trace_id: Optional[str] = None
    timestamp: datetime = Field(default_factory=datetime.utcnow)

    model_config = ConfigDict(json_encoders={datetime: lambda v: v.isoformat()})


class ResourceCreateRequest(BaseModel):
    type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, Any] = Field(default_factory=dict)
    user_id: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ResourceCreateResponse(BaseModel):
    id: str
    status: str
    trace_id: str


class ResourceStatusResponse(BaseModel):
    id: str
    status: str
    progress: int
    active_runs: int
    created_at: str
    updated_at: str


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
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class BatchResponse(BaseModel):
    batch_id: str
    results: List[BatchResult]


class TaskResponse(BaseModel):
    task_id: str
    name: str
    status: str
    priority: int
    created_at: str
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    result: Optional[Any] = None
    error: Optional[str] = None


class NotificationRequest(BaseModel):
    title: str
    message: str
    priority: str = "medium"
    channels: List[str] = Field(default_factory=list)
    recipients: List[str] = Field(default_factory=list)
    tags: List[str] = Field(default_factory=list)


class FaultCreateRequest(BaseModel):
    fault_type: str
    scope: str
    target: str
    parameters: Dict[str, Any] = Field(default_factory=dict)
    description: Optional[str] = None


class ConfigCreateRequest(BaseModel):
    config_id: str
    namespace: str
    parameters: Dict[str, Any]
    description: Optional[str] = None


class AuditQueryRequest(BaseModel):
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    user_id: Optional[str] = None
    action: Optional[str] = None
    limit: int = 100


class BackupRequest(BaseModel):
    source_path: str
    backup_name: Optional[str] = None


class RestoreRequest(BaseModel):
    backup_id: str
    destination_path: str
    overwrite: bool = False
