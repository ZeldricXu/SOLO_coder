from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Optional
from uuid import uuid4

from pydantic import BaseModel, Field, field_validator


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:10]}"


class EntityStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class EntityType(str, Enum):
    EVENT = "event"
    TABLE = "table"
    PIPELINE = "pipeline"
    JOB = "job"


class BaseEntity(BaseModel):
    id: str = Field(default_factory=lambda: generate_id("ent"))
    type: EntityType = EntityType.EVENT
    status: EntityStatus = EntityStatus.PENDING
    attributes: dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class ConfigModel(BaseModel):
    config_id: str = Field(default_factory=lambda: generate_id("cfg"))
    namespace: str = "default"
    version: int = 1
    parameters: dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: datetime = Field(default_factory=datetime.utcnow)


class RunInstance(BaseModel):
    run_id: str = Field(default_factory=lambda: generate_id("run"))
    entity_id: str
    phase: str = "initializing"
    progress: float = 0.0
    started_at: datetime = Field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None

    @field_validator("progress")
    def validate_progress(cls, v: float) -> float:
        return max(0.0, min(1.0, v))


class StatsSnapshot(BaseModel):
    snapshot_id: str = Field(default_factory=lambda: generate_id("snap"))
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    metrics: dict[str, float] = Field(default_factory=dict)
    dimensions: dict[str, str] = Field(default_factory=dict)


class ColumnType(str, Enum):
    INTEGER = "integer"
    BIGINT = "bigint"
    FLOAT = "float"
    DOUBLE = "double"
    STRING = "string"
    BOOLEAN = "boolean"
    DATE = "date"
    DATETIME = "datetime"
    TIMESTAMP = "timestamp"
    BINARY = "binary"
    JSON = "json"
    ARRAY = "array"
    UNKNOWN = "unknown"


class ColumnInfo(BaseModel):
    name: str
    type: ColumnType = ColumnType.UNKNOWN
    nullable: bool = True
    primary_key: bool = False
    unique: bool = False
    default_value: Optional[Any] = None
    comment: Optional[str] = None
    sample_values: list[Any] = Field(default_factory=list)
    stats: dict[str, Any] = Field(default_factory=dict)


class TableSchema(BaseModel):
    database: str
    table: str
    columns: list[ColumnInfo] = Field(default_factory=list)
    primary_key: list[str] = Field(default_factory=list)
    row_count: Optional[int] = None
    size_bytes: Optional[int] = None
    last_analyzed: Optional[datetime] = None


class SchemaInfo(BaseModel):
    tables: list[TableSchema] = Field(default_factory=list)
    extracted_at: datetime = Field(default_factory=datetime.utcnow)
    datasource: str


class DataSource(BaseModel):
    id: str = Field(default_factory=lambda: generate_id("ds"))
    name: str
    type: str
    connection: dict[str, Any]
    status: str = "active"
    config: ConfigModel
