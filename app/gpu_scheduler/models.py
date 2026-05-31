from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime
from enum import IntEnum


class TaskPriority(IntEnum):
    LOW = 1
    MEDIUM = 3
    HIGH = 7
    CRITICAL = 10


class TaskStatus(str):
    PENDING = "pending"
    QUEUED = "queued"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    PREEMPTED = "preempted"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class GPUStatus(str):
    IDLE = "idle"
    PARTIAL = "partial"
    FULL = "full"
    UNAVAILABLE = "unavailable"


class TaskRequest(BaseModel):
    name: str = Field(..., max_length=200)
    priority: int = Field(default=3, ge=1, le=10)
    gpu_count: int = Field(default=1, ge=1)
    gpu_memory_mb: int = Field(default=1024, ge=128)
    estimated_duration_seconds: float = Field(default=60.0, ge=1.0)
    preemptible: bool = Field(default=True)
    command: Optional[str] = None
    parameters: Dict[str, Any] = Field(default_factory=dict)
    callback_url: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class TaskResponse(BaseModel):
    task_id: str
    name: str
    status: str
    gpu_ids: Optional[List[int]] = None
    queued_at: Optional[datetime] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    queue_position: Optional[int] = None
    estimated_wait_seconds: Optional[float] = None


class GPUAllocation(BaseModel):
    gpu_id: int
    memory_allocated_mb: int
    task_id: str


class GPUStatusReport(BaseModel):
    gpu_id: int
    status: str
    total_memory_mb: int
    available_memory_mb: int
    utilized_memory_mb: int
    utilization_percent: float
    current_tasks: List[str]
    temperature_celsius: Optional[float] = None
