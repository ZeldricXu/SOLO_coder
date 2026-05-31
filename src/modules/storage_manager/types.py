from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional
from enum import Enum
from datetime import datetime


class StorageType(str, Enum):
    LOCAL = "local"
    S3 = "s3"
    GCS = "gcs"
    AZURE = "azure"
    FTP = "ftp"


class BackupStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    VERIFIED = "verified"


class RestoreStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"


class BackupPolicy(BaseModel):
    policy_id: Optional[str] = None
    name: str
    source_path: str
    destination: str
    storage_type: StorageType = StorageType.LOCAL
    schedule: str = "0 2 * * *"
    retention_days: int = 30
    compression: bool = True
    encryption: bool = False
    enabled: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)


class BackupRecord(BaseModel):
    backup_id: Optional[str] = None
    policy_id: str
    source_path: str
    destination: str
    storage_type: StorageType
    size_bytes: int = 0
    file_count: int = 0
    status: BackupStatus = BackupStatus.PENDING
    compression_ratio: float = 0.0
    checksum: Optional[str] = None
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class RestoreRequest(BaseModel):
    backup_id: str
    target_path: str
    overwrite: bool = False


class RestoreRecord(BaseModel):
    restore_id: Optional[str] = None
    backup_id: str
    target_path: str
    status: RestoreStatus = RestoreStatus.PENDING
    file_count: int = 0
    size_bytes: int = 0
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class StorageConfig(BaseModel):
    config_id: Optional[str] = None
    name: str
    storage_type: StorageType
    base_path: str
    credentials: Dict[str, Any] = Field(default_factory=dict)
    max_connections: int = 10
    created_at: datetime = Field(default_factory=datetime.utcnow)


class StorageUsage(BaseModel):
    total_bytes: int = 0
    used_bytes: int = 0
    free_bytes: int = 0
    file_count: int = 0
    last_updated: datetime = Field(default_factory=datetime.utcnow)
