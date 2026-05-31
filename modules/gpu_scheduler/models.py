from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional

from sqlalchemy import JSON, DateTime, Integer, String
from sqlalchemy import Enum as SQLEnum
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id
from models.base import BaseModel, TimestampMixin


class GpuNodeStatus(str, Enum):
    ONLINE = "online"
    OFFLINE = "offline"
    BUSY = "busy"
    MAINTENANCE = "maintenance"
    ERROR = "error"


class GpuTaskStatus(str, Enum):
    PENDING = "pending"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class TaskPriority(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class GpuNode(Base, TimestampMixin):
    __tablename__ = "gpu_nodes"

    node_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("gnode")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    hostname: Mapped[str] = mapped_column(String(256), nullable=False)
    ip_address: Mapped[str] = mapped_column(String(64), nullable=False)
    gpu_count: Mapped[int] = mapped_column(Integer, default=1)
    available_gpus: Mapped[int] = mapped_column(Integer, default=1)
    gpu_model: Mapped[str] = mapped_column(String(128), nullable=False)
    total_memory_gb: Mapped[int] = mapped_column(Integer, nullable=False)
    available_memory_gb: Mapped[int] = mapped_column(Integer, nullable=False)
    total_gpu_memory_gb: Mapped[int] = mapped_column(Integer, nullable=False)
    available_gpu_memory_gb: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[GpuNodeStatus] = mapped_column(
        SQLEnum(GpuNodeStatus), default=GpuNodeStatus.ONLINE, index=True
    )
    total_tasks: Mapped[int] = mapped_column(Integer, default=0)
    running_tasks: Mapped[int] = mapped_column(Integer, default=0)
    completed_tasks: Mapped[int] = mapped_column(Integer, default=0)
    failed_tasks: Mapped[int] = mapped_column(Integer, default=0)
    created_by: Mapped[str] = mapped_column(String(64), nullable=False)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    labels: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    api_key: Mapped[Optional[str]] = mapped_column(String(256), nullable=True)


class GpuTask(Base, TimestampMixin):
    __tablename__ = "gpu_tasks"

    task_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("gtask")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    node_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    priority: Mapped[TaskPriority] = mapped_column(
        SQLEnum(TaskPriority), default=TaskPriority.MEDIUM, index=True
    )
    status: Mapped[GpuTaskStatus] = mapped_column(
        SQLEnum(GpuTaskStatus), default=GpuTaskStatus.PENDING, index=True
    )
    required_gpus: Mapped[int] = mapped_column(Integer, default=1)
    required_memory_gb: Mapped[int] = mapped_column(Integer, default=8)
    estimated_runtime_ms: Mapped[int] = mapped_column(Integer, default=3600000)
    actual_runtime_ms: Mapped[int] = mapped_column(Integer, default=0)
    progress: Mapped[int] = mapped_column(Integer, default=0)
    created_by: Mapped[str] = mapped_column(String(64), nullable=False)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    error_message: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    meta_data: Mapped[Dict[str, Any]] = mapped_column("metadata", JSON, default=dict)
    auth_token: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)


class GpuNodeCreate(BaseModel):
    name: str
    hostname: str
    ip_address: str
    gpu_count: int = 1
    available_gpus: Optional[int] = None
    gpu_model: str
    total_memory_gb: int
    available_memory_gb: Optional[int] = None
    total_gpu_memory_gb: int
    available_gpu_memory_gb: Optional[int] = None
    created_by: str
    tenant_id: Optional[str] = None
    labels: Dict[str, Any] = {}
    api_key: Optional[str] = None


class GpuNodeResponse(BaseModel):
    node_id: str
    name: str
    hostname: str
    ip_address: str
    gpu_count: int
    available_gpus: int
    gpu_model: str
    total_memory_gb: int
    available_memory_gb: int
    total_gpu_memory_gb: int
    available_gpu_memory_gb: int
    status: GpuNodeStatus
    utilization_rate: float = 0.0
    memory_usage_rate: float = 0.0
    total_tasks: int
    running_tasks: int
    completed_tasks: int
    failed_tasks: int
    created_by: str
    tenant_id: Optional[str]
    created_at: datetime
    updated_at: datetime
    labels: Dict[str, Any]
    api_key: Optional[str] = None
    mobile_layout: Dict[str, Any] = {}


class GpuTaskCreate(BaseModel):
    name: str
    description: Optional[str] = None
    priority: TaskPriority = TaskPriority.MEDIUM
    required_gpus: int = 1
    required_memory_gb: int = 8
    estimated_runtime_ms: int = 3600000
    created_by: str
    tenant_id: Optional[str] = None
    metadata: Dict[str, Any] = {}
    auth_token: Optional[str] = None


class GpuTaskResponse(BaseModel):
    task_id: str
    name: str
    description: Optional[str]
    node_id: Optional[str]
    priority: TaskPriority
    status: GpuTaskStatus
    required_gpus: int
    required_memory_gb: int
    estimated_runtime_ms: int
    actual_runtime_ms: int
    progress: int
    progress_percentage: float = 0.0
    created_by: str
    tenant_id: Optional[str]
    error_message: Optional[str]
    started_at: Optional[datetime]
    completed_at: Optional[datetime]
    created_at: datetime
    updated_at: datetime
    metadata: Dict[str, Any]
    auth_token: Optional[str] = None


class ClusterStatsResponse(BaseModel):
    total_nodes: int
    online_nodes: int
    offline_nodes: int
    total_gpus: int
    total_memory_gb: int
    available_memory_gb: int
    total_gpu_memory_gb: int
    available_gpu_memory_gb: int
    cluster_utilization_rate: float
    pending_tasks: int
    running_tasks: int
    completed_tasks: int
    failed_tasks: int
    task_success_rate: float
    avg_wait_time_ms: float
    mobile_compatible: bool = False
