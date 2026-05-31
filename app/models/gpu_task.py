from sqlalchemy import Column, String, Integer, Float, ForeignKey, Index, Enum, DateTime, Boolean
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import relationship
import uuid
from datetime import datetime, timezone

from app.models.base import Base, TimestampMixin
import enum


class TaskStatus(str, enum.Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    PREEMPTED = "preempted"


class TaskPriority(int, enum.Enum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


class GPUResource(Base, TimestampMixin):
    __tablename__ = "gpu_resources"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    node_id = Column(String(255), nullable=False, index=True)
    gpu_index = Column(Integer, nullable=False)
    total_memory_gb = Column(Float, nullable=False)
    used_memory_gb = Column(Float, default=0, nullable=False)
    utilization = Column(Float, default=0, nullable=False)
    temperature = Column(Float)
    is_healthy = Column(String(50), default="healthy", nullable=False)
    current_task_id = Column(UUID(as_uuid=True), ForeignKey("gpu_tasks.id"))
    meta_data = Column(JSONB, default=lambda: {})

    __table_args__ = (
        Index("ix_gpu_resource_node_gpu", "node_id", "gpu_index", unique=True),
    )


class GPUTask(Base, TimestampMixin):
    __tablename__ = "gpu_tasks"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False)
    user_id = Column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=False, index=True)
    priority = Column(Integer, default=TaskPriority.MEDIUM.value, nullable=False)
    status = Column(String(50), default=TaskStatus.PENDING.value, nullable=False, index=True)
    required_memory_gb = Column(Float, nullable=False)
    allocated_memory_gb = Column(Float, default=0)
    gpu_resource_id = Column(UUID(as_uuid=True), ForeignKey("gpu_resources.id"))
    command = Column(JSONB, nullable=False)
    container_image = Column(String(500))
    started_at = Column(DateTime(timezone=True))
    completed_at = Column(DateTime(timezone=True))
    queued_at = Column(DateTime(timezone=True))
    duration_seconds = Column(Float, default=0)
    progress = Column(Float, default=0)
    error_message = Column(String(5000))
    exit_code = Column(Integer)
    is_preemptible = Column(Boolean, default=True, nullable=False)
    preemption_count = Column(Integer, default=0)
    checkpoint_path = Column(String(1000))
    meta_data = Column(JSONB, default=lambda: {})

    __table_args__ = (
        Index("ix_gpu_task_status_priority", "status", "priority"),
        Index("ix_gpu_task_user_id", "user_id"),
    )
