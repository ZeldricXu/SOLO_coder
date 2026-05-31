from datetime import datetime
from typing import Optional, Dict, Any
from uuid import UUID
from pydantic import BaseModel, Field, ConfigDict
import enum


class TaskPriority(int, enum.Enum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


class TaskStatus(str, enum.Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    PREEMPTED = "preempted"


class GPUTaskCreate(BaseModel):
    name: str = Field(..., max_length=255, description="任务名称")
    priority: TaskPriority = Field(TaskPriority.MEDIUM, description="任务优先级")
    required_memory_gb: float = Field(..., gt=0, description="所需GPU内存(GB)")
    command: Dict[str, Any] = Field(..., description="执行命令")
    container_image: Optional[str] = Field(None, description="容器镜像")
    is_preemptible: bool = Field(True, description="是否可抢占")
    checkpoint_path: Optional[str] = Field(None, description="检查点路径")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class GPUTaskStatusUpdate(BaseModel):
    status: TaskStatus = Field(..., description="任务状态")
    progress: Optional[float] = Field(None, ge=0, le=1, description="进度")
    error_message: Optional[str] = Field(None, description="错误信息")
    exit_code: Optional[int] = Field(None, description="退出码")


class GPUTaskResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="任务ID")
    name: str = Field(..., description="任务名称")
    user_id: UUID = Field(..., description="用户ID")
    priority: int = Field(..., description="任务优先级")
    status: str = Field(..., description="任务状态")
    required_memory_gb: float = Field(..., description="所需GPU内存(GB)")
    allocated_memory_gb: float = Field(..., description="已分配GPU内存(GB)")
    gpu_resource_id: Optional[UUID] = Field(None, description="GPU资源ID")
    command: Dict[str, Any] = Field(..., description="执行命令")
    container_image: Optional[str] = Field(None, description="容器镜像")
    started_at: Optional[datetime] = Field(None, description="开始时间")
    completed_at: Optional[datetime] = Field(None, description="完成时间")
    queued_at: Optional[datetime] = Field(None, description="入队时间")
    duration_seconds: float = Field(..., description="执行时长(秒)")
    progress: float = Field(..., description="进度")
    error_message: Optional[str] = Field(None, description="错误信息")
    exit_code: Optional[int] = Field(None, description="退出码")
    is_preemptible: bool = Field(..., description="是否可抢占")
    preemption_count: int = Field(..., description="抢占次数")
    checkpoint_path: Optional[str] = Field(None, description="检查点路径")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class GPUResourceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="资源ID")
    node_id: str = Field(..., description="节点ID")
    gpu_index: int = Field(..., description="GPU索引")
    total_memory_gb: float = Field(..., description="总内存(GB)")
    used_memory_gb: float = Field(..., description="已用内存(GB)")
    utilization: float = Field(..., description="利用率")
    temperature: Optional[float] = Field(None, description="温度")
    is_healthy: str = Field(..., description="健康状态")
    current_task_id: Optional[UUID] = Field(None, description="当前任务ID")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")
