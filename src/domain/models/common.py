"""Common domain models for the file storage system."""
from __future__ import annotations

from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Dict, List, Optional, Union
from uuid import UUID, uuid4

from pydantic import BaseModel, Field, field_validator


class StorageTier(str, Enum):
    HOT = "hot"
    COLD = "cold"
    ARCHIVE = "archive"


class FileStatus(str, Enum):
    UPLOADING = "uploading"
    ACTIVE = "active"
    ARCHIVING = "archiving"
    ARCHIVED = "archived"
    RESTORING = "restoring"
    DELETING = "deleting"
    DELETED = "deleted"
    ERROR = "error"


class LifecycleAction(str, Enum):
    MOVE_TO_COLD = "move_to_cold"
    MOVE_TO_ARCHIVE = "move_to_archive"
    DELETE = "delete"
    RESTORE = "restore"


class ProcessingStatus(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    SUCCESS = "success"
    FAILED = "failed"
    PARTIAL = "partial"


class QualitySeverity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class TaskStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    CANCELLED = "cancelled"
    SKIPPED = "skipped"


class QueryPlanType(str, Enum):
    LOGICAL = "logical"
    OPTIMIZED = "optimized"
    PHYSICAL = "physical"


class BaseDomainModel(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        from_attributes = True


class LifecyclePolicy(BaseDomainModel):
    name: str
    description: Optional[str] = None
    hot_to_cold_days: int = Field(default=30, ge=1)
    cold_to_archive_days: int = Field(default=90, ge=1)
    archive_retention_days: int = Field(default=365, ge=1)
    enabled: bool = True
    tags: Dict[str, str] = Field(default_factory=dict)


class FileMetadata(BaseDomainModel):
    file_name: str
    file_path: str
    file_size: int = Field(ge=0)
    content_type: str
    storage_tier: StorageTier = StorageTier.HOT
    status: FileStatus = FileStatus.ACTIVE
    checksum: str
    lifecycle_policy_id: Optional[UUID] = None
    last_accessed_at: datetime = Field(default_factory=datetime.utcnow)
    access_count: int = Field(default=0, ge=0)
    tags: Dict[str, str] = Field(default_factory=dict)
    custom_metadata: Dict[str, Any] = Field(default_factory=dict)
    schema_version: str = "1.0"

    def should_move_to_cold(self, policy: LifecyclePolicy) -> bool:
        if self.storage_tier != StorageTier.HOT:
            return False
        age = datetime.utcnow() - self.last_accessed_at
        return age > timedelta(days=policy.hot_to_cold_days)

    def should_move_to_archive(self, policy: LifecyclePolicy) -> bool:
        if self.storage_tier != StorageTier.COLD:
            return False
        age = datetime.utcnow() - self.last_accessed_at
        return age > timedelta(days=policy.cold_to_archive_days)

    def should_delete(self, policy: LifecyclePolicy) -> bool:
        if self.storage_tier != StorageTier.ARCHIVE:
            return False
        age = datetime.utcnow() - self.last_accessed_at
        return age > timedelta(days=policy.archive_retention_days)


class EventMessage(BaseModel):
    event_id: UUID = Field(default_factory=uuid4)
    event_type: str
    source: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    payload: Dict[str, Any] = Field(default_factory=dict)
    correlation_id: Optional[str] = None
    version: str = "1.0"

    @field_validator("event_type")
    @classmethod
    def event_type_not_empty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("event_type cannot be empty")
        return v


class ProcessingResult(BaseModel):
    request_id: UUID = Field(default_factory=uuid4)
    status: ProcessingStatus = ProcessingStatus.PENDING
    message: str = ""
    results: List[Dict[str, Any]] = Field(default_factory=list)
    errors: List[Dict[str, Any]] = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: Optional[int] = None

    def calculate_duration(self) -> None:
        if self.started_at and self.completed_at:
            self.duration_ms = int((self.completed_at - self.started_at).total_seconds() * 1000)


class SchemaField(BaseModel):
    name: str
    data_type: str
    nullable: bool = True
    description: Optional[str] = None
    sample_values: List[Any] = Field(default_factory=list)


class SchemaInfo(BaseDomainModel):
    table_name: str
    fields: List[SchemaField] = Field(default_factory=list)
    row_count: int = Field(default=0, ge=0)
    size_bytes: int = Field(default=0, ge=0)
    sample_data: List[Dict[str, Any]] = Field(default_factory=list)
    statistics: Dict[str, Any] = Field(default_factory=dict)
    data_source: str
    version: str = "1.0"


class QualityRule(BaseDomainModel):
    name: str
    description: Optional[str] = None
    rule_type: str
    expression: str
    severity: QualitySeverity = QualitySeverity.MEDIUM
    enabled: bool = True
    target_tables: List[str] = Field(default_factory=list)
    parameters: Dict[str, Any] = Field(default_factory=dict)
    schedule: Optional[str] = None


class DataQualityIssue(BaseModel):
    rule_id: UUID
    rule_name: str
    severity: QualitySeverity
    message: str
    affected_rows: int = 0
    sample_data: List[Dict[str, Any]] = Field(default_factory=list)
    detected_at: datetime = Field(default_factory=datetime.utcnow)


class DataQualityReport(BaseDomainModel):
    table_name: str
    check_time: datetime = Field(default_factory=datetime.utcnow)
    total_rows_checked: int = 0
    issues: List[DataQualityIssue] = Field(default_factory=list)
    passed: bool = True
    score: float = Field(default=100.0, ge=0, le=100)


class ScheduledTask(BaseDomainModel):
    name: str
    description: Optional[str] = None
    task_type: str
    cron_expression: Optional[str] = None
    dependencies: List[UUID] = Field(default_factory=list)
    parameters: Dict[str, Any] = Field(default_factory=dict)
    status: TaskStatus = TaskStatus.PENDING
    last_run_at: Optional[datetime] = None
    next_run_at: Optional[datetime] = None
    retry_count: int = Field(default=0, ge=0)
    max_retries: int = Field(default=3, ge=0)
    timeout_seconds: int = Field(default=3600, ge=1)
    enabled: bool = True


class TimeSeriesDataPoint(BaseModel):
    timestamp: datetime
    value: Union[float, int, str]
    tags: Dict[str, str] = Field(default_factory=dict)


class TimeSeriesData(BaseDomainModel):
    metric_name: str
    data_points: List[TimeSeriesDataPoint] = Field(default_factory=list)
    resolution: str = "raw"
    compression_algorithm: Optional[str] = None
    original_size: int = 0
    compressed_size: int = 0


class VectorEmbedding(BaseDomainModel):
    document_id: str
    text: str
    vector: List[float]
    model_name: str
    dimension: int
    metadata: Dict[str, Any] = Field(default_factory=dict)


class QueryExecutionPlan(BaseDomainModel):
    plan_type: QueryPlanType
    original_query: str
    parsed_ast: Dict[str, Any] = Field(default_factory=dict)
    logical_plan: Dict[str, Any] = Field(default_factory=dict)
    optimized_plan: Dict[str, Any] = Field(default_factory=dict)
    physical_plan: Dict[str, Any] = Field(default_factory=dict)
    estimated_cost: float = 0.0
    execution_time_ms: Optional[float] = None
