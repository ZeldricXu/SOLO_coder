from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional
from enum import Enum
from datetime import datetime


class SchemaStatus(str, Enum):
    DRAFT = "draft"
    ACTIVE = "active"
    DEPRECATED = "deprecated"
    ARCHIVED = "archived"


class MigrationStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    ROLLBACK = "rollback"


class FieldDefinition(BaseModel):
    name: str
    type: str
    nullable: bool = False
    default: Optional[Any] = None
    description: str = ""
    constraints: Dict[str, Any] = Field(default_factory=dict)


class SchemaVersion(BaseModel):
    schema_id: Optional[str] = None
    name: str
    version: int
    fields: List[FieldDefinition] = Field(default_factory=list)
    primary_key: List[str] = Field(default_factory=list)
    indexes: List[List[str]] = Field(default_factory=list)
    status: SchemaStatus = SchemaStatus.DRAFT
    description: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)


class MigrationDefinition(BaseModel):
    migration_id: Optional[str] = None
    name: str
    description: str = ""
    from_version: int
    to_version: int
    up_sql: str = ""
    down_sql: str = ""
    python_up: Optional[Dict[str, Any]] = None
    python_down: Optional[Dict[str, Any]] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)


class MigrationExecution(BaseModel):
    execution_id: str
    migration_id: str
    schema_name: str
    from_version: int
    to_version: int
    status: MigrationStatus = MigrationStatus.PENDING
    records_processed: int = 0
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class DataSourceConfig(BaseModel):
    source_id: Optional[str] = None
    name: str
    source_type: str
    connection_params: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)


class DataTransferRequest(BaseModel):
    source_id: str
    target_id: str
    source_table: str
    target_table: str
    filter_condition: Optional[str] = None
    batch_size: int = 1000
    transform_rules: Optional[Dict[str, Any]] = None


class DataTransferResult(BaseModel):
    transfer_id: str
    records_transferred: int
    failed_records: int
    total_bytes: int
    started_at: datetime
    completed_at: datetime
