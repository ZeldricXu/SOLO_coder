from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class APIResponse(BaseModel):
    code: int = 200
    data: Optional[Any] = None
    error: Optional[str] = None
    trace_id: Optional[str] = None


class ConfigCreate(BaseModel):
    config_id: str
    namespace: str = "default"
    parameters: Dict[str, Any]
    enabled: bool = True


class ConfigUpdate(BaseModel):
    parameters: Dict[str, Any]


class ConfigRollback(BaseModel):
    target_version: int


class ConfigResponse(BaseModel):
    config_id: str
    namespace: str
    version: int
    parameters: Dict[str, Any]
    enabled: bool
    applied_at: Optional[datetime] = None
    created_at: Optional[datetime] = None


class DeviceShadowUpdate(BaseModel):
    device_id: str
    state: Dict[str, Any]


class DeviceShadowResponse(BaseModel):
    device_id: str
    desired: Dict[str, Any]
    reported: Dict[str, Any]
    delta: Dict[str, Any]
    version: int
    last_sync_at: Optional[datetime] = None


class FirmwareCreate(BaseModel):
    version: str
    device_model: str
    file_path: str
    release_notes: Optional[str] = None
    diff_from_version: Optional[str] = None


class OTACampaignCreate(BaseModel):
    firmware_id: str
    name: str
    device_ids: List[str]
    grayscale_percent: int = 100
    auto_rollback: bool = True


class OTADeviceStatusUpdate(BaseModel):
    status: str
    error_message: Optional[str] = None
    current_version: Optional[str] = None


class OTACampaignResponse(BaseModel):
    id: str
    name: str
    status: str
    current_batch: int
    total_devices: int
    success_count: int
    failed_count: int
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class TaskCreate(BaseModel):
    name: str
    task_type: str
    payload: Dict[str, Any] = Field(default_factory=dict)
    dependencies: List[str] = Field(default_factory=list)
    priority: int = 0
    scheduled_at: Optional[datetime] = None


class TaskResponse(BaseModel):
    task_id: str
    name: str
    task_type: str
    status: str
    priority: int
    result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class EdgeModelCreate(BaseModel):
    model_id: str
    name: str
    version: str
    model_type: str
    model_path: str
    input_spec: Dict[str, Any] = Field(default_factory=dict)
    output_spec: Dict[str, Any] = Field(default_factory=dict)
    requirements: Dict[str, Any] = Field(default_factory=dict)


class InferenceJobCreate(BaseModel):
    model_id: str
    device_id: str
    input_data: Dict[str, Any]


class InferenceJobResponse(BaseModel):
    job_id: str
    model_id: str
    device_id: str
    status: str
    result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    latency_ms: Optional[int] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class NotificationCreate(BaseModel):
    title: str
    content: str
    user_id: Optional[str] = None
    priority: int = 0
    category: Optional[str] = None


class NotificationResponse(BaseModel):
    id: str
    user_id: Optional[str] = None
    title: str
    content: str
    priority: int
    category: Optional[str] = None
    is_read: bool
    created_at: Optional[datetime] = None


class ProcessingRequest(BaseModel):
    payload: Any
    trace_id: Optional[str] = None
    pipeline_name: Optional[str] = None


class EntityCreate(BaseModel):
    type: str
    attributes: Dict[str, Any] = Field(default_factory=dict)


class EntityUpdate(BaseModel):
    status: Optional[str] = None
    attributes: Optional[Dict[str, Any]] = None


class EntityResponse(BaseModel):
    id: str
    type: str
    status: str
    attributes: Dict[str, Any]
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None


class ResourceCreate(BaseModel):
    type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class BatchOperation(BaseModel):
    action: str
    id: str


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class BackupRequest(BaseModel):
    backup_type: str = "full"
    tables: Optional[List[str]] = None


class RestoreRequest(BaseModel):
    backup_id: str
    tables: Optional[List[str]] = None


class UserCreate(BaseModel):
    username: str
    email: str
    password: str


class UserLogin(BaseModel):
    username: str
    password: str


class AuthResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: Dict[str, Any]


class AsyncOperationRequest(BaseModel):
    device_id: str
    operation: str
    state: Dict[str, Any] = {}
    priority: int = 0
    async_mode: bool = True


class AsyncOperationResponse(BaseModel):
    task_id: str
    device_id: str
    operation: str
    status: str
    priority: int


class BatchAsyncRequest(BaseModel):
    operations: List[AsyncOperationRequest]
    priority: int = 0


class CacheMetricsResponse(BaseModel):
    enabled: bool
    total_requests: int
    cache_hits: int
    cache_misses: int
    hit_rate_percent: float
    l1: Dict[str, Any]
    l2: Dict[str, Any]
    l3: Dict[str, Any]
    db_hits: int


class CacheInvalidateRequest(BaseModel):
    config_id: Optional[str] = None
    namespace: Optional[str] = None
    version: Optional[int] = None


class AutoscaleMetricsResponse(BaseModel):
    enabled: bool
    current_instances: int
    min_instances: int
    max_instances: int
    target_cpu_percent: float
    target_latency_ms: int
    scale_ups: int
    scale_downs: int
    target_instances: int


class InstanceMetricsResponse(BaseModel):
    instance_count: int
    instances: Dict[str, Any]


class CircuitBreakerMetricsResponse(BaseModel):
    circuits: Dict[str, Any]
