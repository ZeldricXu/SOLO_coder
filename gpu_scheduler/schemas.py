from datetime import datetime
from typing import List, Optional, Dict, Any
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class JobStatus(str, Enum):
    PENDING = "pending"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    PREEMPTED = "preempted"


class JobPriority(int, Enum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4
    EMERGENCY = 5


class PreemptionPolicy(str, Enum):
    NONE = "none"
    LOWER_PRIORITY = "lower_priority"
    EARLIEST_DEADLINE = "earliest_deadline"
    LEAST_PROGRESS = "least_progress"
    COST_AWARE = "cost_aware"


class SchedulerStatus(str, Enum):
    RUNNING = "running"
    PAUSED = "paused"
    DRAINING = "draining"
    ERROR = "error"


class GPUResource(BaseModel):
    gpu_type: str = Field(..., description="GPU型号，如 A100, H100, RTX4090")
    gpu_count: int = Field(ge=0, description="GPU数量")
    vram_gb: float = Field(ge=0.0, description="显存大小(GB)")
    compute_capability: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class GPUComputeNode(BaseModel):
    node_id: str
    hostname: str
    ip_address: str
    region: str
    zone: str
    total_gpus: GPUResource
    available_gpus: GPUResource
    cpu_cores: int
    memory_gb: float
    status: str = Field(default="active")
    labels: Optional[Dict[str, str]] = None
    last_heartbeat: Optional[datetime] = None
    registered_at: datetime


class ResourceRequest(BaseModel):
    gpu_type: Optional[str] = None
    min_gpu_count: int = Field(default=1, ge=1)
    min_vram_gb: Optional[float] = None
    cpu_cores: int = Field(default=1, ge=1)
    memory_gb: float = Field(default=4.0, ge=0.5)
    node_labels: Optional[Dict[str, str]] = None
    regions: Optional[List[str]] = None


class GPUJobRequest(BaseModel):
    job_name: str
    job_type: str = Field(default="inference")
    resource_request: ResourceRequest
    priority: JobPriority = Field(default=JobPriority.MEDIUM)
    max_runtime_seconds: Optional[int] = Field(default=None, description="最大运行时间(秒)")
    deadline: Optional[datetime] = None
    command: str
    args: Optional[List[str]] = None
    env_vars: Optional[Dict[str, str]] = None
    working_dir: Optional[str] = None
    preemption_policy: PreemptionPolicy = Field(default=PreemptionPolicy.LOWER_PRIORITY)
    allow_preemption: bool = Field(default=True)
    checkpoint_path: Optional[str] = None
    retry_count: int = Field(default=0, ge=0, le=5)
    tags: Optional[List[str]] = None
    submitted_by: Optional[str] = None


class GPUJob(BaseModel):
    job_id: str
    job_name: str
    job_type: str
    resource_request: ResourceRequest
    priority: JobPriority
    status: JobStatus
    allocated_node_id: Optional[str] = None
    allocated_gpus: Optional[GPUResource] = None
    max_runtime_seconds: Optional[int] = None
    deadline: Optional[datetime] = None
    command: str
    args: Optional[List[str]] = None
    env_vars: Optional[Dict[str, str]] = None
    working_dir: Optional[str] = None
    preemption_policy: PreemptionPolicy
    allow_preemption: bool
    checkpoint_path: Optional[str] = None
    retry_count: int
    tags: Optional[List[str]] = None
    submitted_by: Optional[str] = None
    submitted_at: datetime
    scheduled_at: Optional[datetime] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    progress: float = Field(default=0.0, ge=0.0, le=1.0)
    exit_code: Optional[int] = None
    error_message: Optional[str] = None
    logs_url: Optional[str] = None
    metrics: Optional[Dict[str, Any]] = None

    model_config = ConfigDict(from_attributes=True)


class GPUJobResponse(BaseModel):
    job: GPUJob
    queue_position: Optional[int] = None
    estimated_wait_time_seconds: Optional[float] = None


class JobCancelRequest(BaseModel):
    job_id: str
    force: bool = Field(default=False)
    reason: Optional[str] = None


class JobCancelResponse(BaseModel):
    job_id: str
    status: JobStatus
    message: str


class SchedulerMetrics(BaseModel):
    total_jobs: int
    pending_jobs: int
    running_jobs: int
    completed_jobs: int
    failed_jobs: int
    average_wait_time_seconds: float
    average_run_time_seconds: float
    total_gpu_utilization: float
    queue_depth: int


class ClusterStatusResponse(BaseModel):
    cluster_id: str
    scheduler_status: SchedulerStatus
    total_nodes: int
    active_nodes: int
    total_gpus: int
    available_gpus: int
    total_vram_gb: float
    available_vram_gb: float
    nodes: List[GPUComputeNode]
    metrics: SchedulerMetrics
    last_updated: datetime


class JobQueryParams(BaseModel):
    status: Optional[JobStatus] = None
    priority: Optional[JobPriority] = None
    job_type: Optional[str] = None
    submitted_by: Optional[str] = None
    node_id: Optional[str] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
