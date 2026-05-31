from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4

from pydantic import BaseModel, Field, field_validator


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:8]}"


class EntityType(str, Enum):
    RESOURCE = "resource"
    JOB = "job"
    TASK = "task"
    SERVICE = "service"
    LIBRARY = "library"


class EntityStatus(str, Enum):
    PENDING = "pending"
    PROVISIONING = "provisioning"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    STOPPED = "stopped"


class RunPhase(str, Enum):
    INITIALIZING = "initializing"
    VALIDATING = "validating"
    EXECUTING = "executing"
    FINALIZING = "finalizing"
    COMPLETED = "completed"
    FAILED = "failed"


class NotificationChannel(str, Enum):
    EMAIL = "email"
    SMS = "sms"
    WEBHOOK = "webhook"
    SLACK = "slack"
    DINGTALK = "dingtalk"


class NotificationStatus(str, Enum):
    PENDING = "pending"
    SENT = "sent"
    DELIVERED = "delivered"
    FAILED = "failed"
    RETRYING = "retrying"


class QualityGateStatus(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    WARNING = "warning"


class BaseEntity(BaseModel):
    id: str = Field(default_factory=lambda: generate_id("ent"))
    type: EntityType = EntityType.RESOURCE
    status: EntityStatus = EntityStatus.PENDING
    attributes: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    @field_validator("updated_at")
    @classmethod
    def set_updated_at(cls, v: datetime) -> datetime:
        return v or datetime.utcnow()


class ConfigDefinition(BaseModel):
    config_id: str = Field(default_factory=lambda: generate_id("cfg"))
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: datetime = Field(default_factory=datetime.utcnow)


class RunInstance(BaseModel):
    run_id: str = Field(default_factory=lambda: generate_id("run"))
    entity_id: str
    phase: RunPhase = RunPhase.INITIALIZING
    progress: float = 0.0
    started_at: datetime = Field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None

    @field_validator("progress")
    @classmethod
    def check_progress_range(cls, v: float) -> float:
        if not 0.0 <= v <= 1.0:
            raise ValueError("Progress must be between 0 and 1")
        return v


class MetricsSnapshot(BaseModel):
    snapshot_id: str = Field(default_factory=lambda: generate_id("snap"))
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    metrics: Dict[str, float] = Field(default_factory=dict)
    dimensions: Dict[str, str] = Field(default_factory=dict)


class Task(BaseModel):
    task_id: str = Field(default_factory=lambda: generate_id("task"))
    name: str
    description: Optional[str] = None
    dependencies: List[str] = Field(default_factory=list)
    parameters: Dict[str, Any] = Field(default_factory=dict)
    timeout: int = 3600
    retries: int = 3
    entity_id: Optional[str] = None


class TaskGraph(BaseModel):
    graph_id: str = Field(default_factory=lambda: generate_id("graph"))
    name: str
    tasks: List[Task] = Field(default_factory=list)
    parameters: Dict[str, Any] = Field(default_factory=dict)

    def get_tasks_in_order(self) -> List[Task]:
        visited = set()
        result = []

        def visit(task_id: str) -> None:
            if task_id in visited:
                return
            visited.add(task_id)
            task = next((t for t in self.tasks if t.task_id == task_id), None)
            if task:
                for dep in task.dependencies:
                    visit(dep)
                result.append(task)

        for task in self.tasks:
            visit(task.task_id)
        return result


class Notification(BaseModel):
    notification_id: str = Field(default_factory=lambda: generate_id("notif"))
    channel: NotificationChannel
    recipient: str
    subject: Optional[str] = None
    content: str
    status: NotificationStatus = NotificationStatus.PENDING
    retry_count: int = 0
    max_retries: int = 3
    sent_at: Optional[datetime] = None
    delivered_at: Optional[datetime] = None
    error_message: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class LogEntry(BaseModel):
    log_id: str = Field(default_factory=lambda: generate_id("log"))
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    level: str
    module: str
    message: str
    trace_id: Optional[str] = None
    extra: Dict[str, Any] = Field(default_factory=dict)


class QualityGateRule(BaseModel):
    rule_id: str = Field(default_factory=lambda: generate_id("rule"))
    name: str
    language: str
    severity: str = "warning"
    enabled: bool = True
    parameters: Dict[str, Any] = Field(default_factory=dict)


class QualityGateReport(BaseModel):
    report_id: str = Field(default_factory=lambda: generate_id("report"))
    project_name: str
    status: QualityGateStatus = QualityGateStatus.WARNING
    language: str
    complexity_score: float = 0.0
    coverage: float = 0.0
    duplication_rate: float = 0.0
    issues: List[Dict[str, Any]] = Field(default_factory=list)
    generated_at: datetime = Field(default_factory=datetime.utcnow)


class ServiceMetadata(BaseModel):
    service_id: str = Field(default_factory=lambda: generate_id("svc"))
    name: str
    version: str = "1.0.0"
    description: Optional[str] = None
    type: str = "service"
    language: str = "python"
    dependencies: List[str] = Field(default_factory=list)
    endpoints: List[Dict[str, Any]] = Field(default_factory=list)
    tags: List[str] = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)
    registered_at: datetime = Field(default_factory=datetime.utcnow)


class APIResponse(BaseModel):
    code: int
    data: Optional[Dict[str, Any]] = None
    message: Optional[str] = None


class CreateResourceRequest(BaseModel):
    type: str = "job"
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class BatchOperation(BaseModel):
    action: str
    id: str
    parameters: Dict[str, Any] = Field(default_factory=dict)


class BatchOperationRequest(BaseModel):
    operations: List[BatchOperation]
