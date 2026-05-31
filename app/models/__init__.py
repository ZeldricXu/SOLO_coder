"""
Data models for the platform.
"""

from datetime import datetime
from typing import Any, Dict, List, Optional
from enum import Enum
from pydantic import BaseModel, Field


class EntityType(str, Enum):
    TASK = "task"
    WORKFLOW = "workflow"
    RESOURCE = "resource"
    JOB = "job"


class EntityStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    SUSPENDED = "suspended"
    DELETED = "deleted"


class RunPhase(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    EXECUTING = "executing"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class NotificationPriority(str, Enum):
    CRITICAL = "critical"
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class AlertSeverity(str, Enum):
    CRITICAL = "critical"
    WARNING = "warning"
    INFO = "info"


class CoreEntity(BaseModel):
    id: str = Field(..., description="Unique entity identifier")
    type: EntityType = Field(..., description="Entity type")
    status: EntityStatus = Field(default=EntityStatus.ACTIVE, description="Entity status")
    attributes: Dict[str, Any] = Field(default_factory=dict, description="Entity attributes")
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class ConfigDefinition(BaseModel):
    config_id: str = Field(..., description="Configuration identifier")
    namespace: str = Field(default="default", description="Namespace")
    version: int = Field(default=1, description="Configuration version")
    parameters: Dict[str, Any] = Field(default_factory=dict, description="Configuration parameters")
    enabled: bool = Field(default=True, description="Enabled flag")
    applied_at: Optional[datetime] = Field(default=None, description="Applied timestamp")


class RunInstance(BaseModel):
    run_id: str = Field(..., description="Run instance identifier")
    entity_id: str = Field(..., description="Associated entity ID")
    phase: RunPhase = Field(default=RunPhase.PENDING, description="Execution phase")
    progress: float = Field(default=0.0, ge=0.0, le=1.0, description="Progress percentage")
    started_at: Optional[datetime] = Field(default=None, description="Start timestamp")
    completed_at: Optional[datetime] = Field(default=None, description="Completion timestamp")
    error_detail: Optional[str] = Field(default=None, description="Error details if failed")


class StatsSnapshot(BaseModel):
    snapshot_id: str = Field(..., description="Snapshot identifier")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="Snapshot timestamp")
    metrics: Dict[str, float] = Field(default_factory=dict, description="Collected metrics")
    dimensions: Dict[str, str] = Field(default_factory=dict, description="Metric dimensions")


class LogEntry(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    level: str
    message: str
    trace_id: Optional[str] = None
    module: str
    extra: Dict[str, Any] = Field(default_factory=dict)


class LineageEdge(BaseModel):
    source: str
    target: str
    edge_type: str = Field(default="depends_on")
    attributes: Dict[str, Any] = Field(default_factory=dict)


class LineageNode(BaseModel):
    id: str
    node_type: str
    name: str
    attributes: Dict[str, Any] = Field(default_factory=dict)


class LineageGraph(BaseModel):
    nodes: List[LineageNode] = Field(default_factory=list)
    edges: List[LineageEdge] = Field(default_factory=list)
