from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional
from enum import Enum
from datetime import datetime


class GpuTaskStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    PREEMPTED = "preempted"
    COMPLETED = "completed"
    FAILED = "failed"


class GpuPriority(int, Enum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


class GpuDevice(BaseModel):
    gpu_id: str
    index: int
    name: str
    total_memory_gb: float
    used_memory_gb: float = 0.0
    available_memory_gb: float = 0.0
    utilization: float = 0.0
    temperature: float = 0.0
    healthy: bool = True


class GpuTask(BaseModel):
    task_id: Optional[str] = None
    name: str
    priority: GpuPriority = GpuPriority.MEDIUM
    required_memory_gb: float
    required_gpus: int = 1
    allow_preemption: bool = True
    payload: Dict[str, Any] = Field(default_factory=dict)
    callback_url: Optional[str] = None
    max_runtime_seconds: int = 3600
    created_at: datetime = Field(default_factory=datetime.utcnow)


class GpuTaskExecution(BaseModel):
    execution_id: str
    task_id: str
    status: GpuTaskStatus = GpuTaskStatus.PENDING
    gpu_ids: List[str] = Field(default_factory=list)
    allocated_memory_gb: float = 0.0
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    preemption_count: int = 0


class GpuAllocation(BaseModel):
    allocation_id: str
    task_id: str
    gpu_id: str
    memory_gb: float
    priority: GpuPriority
    start_time: datetime


class GpuClusterStats(BaseModel):
    total_gpus: int
    available_gpus: int
    total_memory_gb: float
    used_memory_gb: float
    pending_tasks: int
    running_tasks: int
    avg_utilization: float


class GpuTaskSubmitRequest(BaseModel):
    name: str
    priority: GpuPriority = GpuPriority.MEDIUM
    required_memory_gb: float
    required_gpus: int = 1
    allow_preemption: bool = True
    payload: Dict[str, Any] = Field(default_factory=dict)
    callback_url: Optional[str] = None
    max_runtime_seconds: int = 3600
