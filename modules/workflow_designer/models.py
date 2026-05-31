from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import JSON, DateTime, Float, Integer, String
from sqlalchemy import Enum as SQLEnum
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class NodeType(str, Enum):
    START = "start"
    END = "end"
    TASK = "task"
    CONDITION = "condition"
    PARALLEL = "parallel"
    DELAY = "delay"
    APPROVAL = "approval"
    NOTIFICATION = "notification"
    WEBHOOK = "webhook"


class EdgeType(str, Enum):
    SEQUENCE = "sequence"
    CONDITIONAL = "conditional"
    DEFAULT = "default"


class WorkflowStatus(str, Enum):
    DRAFT = "draft"
    ACTIVE = "active"
    INACTIVE = "inactive"
    ARCHIVED = "archived"


class InstanceStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    SUSPENDED = "suspended"


class NodeStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"


class WorkflowDefinition(Base):
    __tablename__ = "workflow_definitions"

    workflow_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("wfd")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    version: Mapped[int] = mapped_column(Integer, default=1)
    status: Mapped[WorkflowStatus] = mapped_column(
        SQLEnum(WorkflowStatus), default=WorkflowStatus.DRAFT
    )
    nodes: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    edges: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    variables: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    triggers: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    created_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)


class WorkflowInstance(Base):
    __tablename__ = "workflow_instances"

    instance_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("wfi")
    )
    workflow_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    workflow_version: Mapped[int] = mapped_column(Integer, default=1)
    status: Mapped[InstanceStatus] = mapped_column(
        SQLEnum(InstanceStatus), default=InstanceStatus.PENDING, index=True
    )
    current_node_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    context: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    variables: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    error_detail: Mapped[Optional[Dict[str, Any]]] = mapped_column(JSON, nullable=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    started_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class WorkflowNodeExecution(Base):
    __tablename__ = "workflow_node_executions"

    execution_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("wne")
    )
    instance_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    node_id: Mapped[str] = mapped_column(String(64), nullable=False)
    node_type: Mapped[NodeType] = mapped_column(SQLEnum(NodeType), nullable=False)
    status: Mapped[NodeStatus] = mapped_column(
        SQLEnum(NodeStatus), default=NodeStatus.PENDING
    )
    input_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    output_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    error_detail: Mapped[Optional[Dict[str, Any]]] = mapped_column(JSON, nullable=True)
    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    duration_seconds: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    retry_count: Mapped[int] = mapped_column(Integer, default=0)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class WorkflowCreate(BaseModel):
    name: str
    description: Optional[str] = None
    nodes: List[Dict[str, Any]] = []
    edges: List[Dict[str, Any]] = []
    variables: Dict[str, Any] = {}
    triggers: List[Dict[str, Any]] = []
    tenant_id: Optional[str] = None
    created_by: Optional[str] = None
    meta_data: Dict[str, Any] = {}


class WorkflowUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    nodes: Optional[List[Dict[str, Any]]] = None
    edges: Optional[List[Dict[str, Any]]] = None
    variables: Optional[Dict[str, Any]] = None
    status: Optional[WorkflowStatus] = None
    meta_data: Optional[Dict[str, Any]] = None


class WorkflowResponse(BaseModel):
    workflow_id: str
    name: str
    description: Optional[str]
    version: int
    status: WorkflowStatus
    nodes: List[Dict[str, Any]]
    edges: List[Dict[str, Any]]
    variables: Dict[str, Any]
    triggers: List[Dict[str, Any]]
    tenant_id: Optional[str]
    created_by: Optional[str]
    created_at: datetime
    updated_at: datetime
    is_valid: bool = True
    validation_errors: List[str] = []


class WorkflowInstanceCreate(BaseModel):
    workflow_id: str
    context: Dict[str, Any] = {}
    variables: Dict[str, Any] = {}
    tenant_id: Optional[str] = None
    started_by: Optional[str] = None


class WorkflowInstanceResponse(BaseModel):
    instance_id: str
    workflow_id: str
    workflow_version: int
    status: InstanceStatus
    current_node_id: Optional[str]
    context: Dict[str, Any]
    started_at: Optional[datetime]
    completed_at: Optional[datetime]
    tenant_id: Optional[str]
    started_by: Optional[str]
    created_at: datetime


class NodeExecutionResponse(BaseModel):
    execution_id: str
    instance_id: str
    node_id: str
    node_type: NodeType
    status: NodeStatus
    input_data: Dict[str, Any]
    output_data: Dict[str, Any]
    started_at: Optional[datetime]
    completed_at: Optional[datetime]
    duration_seconds: Optional[float]


class ValidationError(BaseModel):
    node_id: Optional[str]
    edge_id: Optional[str]
    error_type: str
    message: str
