from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional, Callable
from enum import Enum
from datetime import datetime


class TaskStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class TaskPhase(str, Enum):
    INITIALIZING = "initializing"
    PREPARING = "preparing"
    PROCESSING = "processing"
    FINALIZING = "finalizing"
    COMPLETED = "completed"


class TaskPriority(int, Enum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


class TaskDefinition(BaseModel):
    task_id: Optional[str] = None
    name: str
    type: str
    priority: TaskPriority = TaskPriority.MEDIUM
    payload: Dict[str, Any] = Field(default_factory=dict)
    callback_url: Optional[str] = None
    timeout_seconds: int = 3600
    max_retries: int = 3
    dependencies: List[str] = Field(default_factory=list)
    scheduled_at: Optional[datetime] = None
    status: TaskStatus = TaskStatus.PENDING
    created_at: datetime = Field(default_factory=datetime.utcnow)


class TaskExecution(BaseModel):
    execution_id: str
    task_id: str
    status: TaskStatus = TaskStatus.PENDING
    phase: TaskPhase = TaskPhase.INITIALIZING
    progress: float = 0.0
    current_step: str = ""
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    worker_id: Optional[str] = None
    retry_count: int = 0
    error_detail: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    logs: List[Dict[str, Any]] = Field(default_factory=list)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class TaskCreateRequest(BaseModel):
    name: str
    type: str
    priority: TaskPriority = TaskPriority.MEDIUM
    payload: Dict[str, Any] = Field(default_factory=dict)
    callback_url: Optional[str] = None
    timeout_seconds: int = 3600
    max_retries: int = 3
    dependencies: List[str] = Field(default_factory=list)
    scheduled_at: Optional[datetime] = None


class TaskUpdateRequest(BaseModel):
    status: Optional[TaskStatus] = None
    phase: Optional[TaskPhase] = None
    progress: Optional[float] = None
    current_step: Optional[str] = None
    error_detail: Optional[str] = None
    result: Optional[Dict[str, Any]] = None


class TaskLogEntry(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    level: str = "INFO"
    message: str
    details: Optional[Dict[str, Any]] = None


class TaskSummary(BaseModel):
    total: int = 0
    pending: int = 0
    running: int = 0
    completed: int = 0
    failed: int = 0
    cancelled: int = 0
