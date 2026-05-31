from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class FirmwareVersionCreate(BaseModel):
    version: str
    device_model: str
    file_path: str
    file_size: int = 0
    checksum: str
    signature: Optional[str] = None
    release_notes: Optional[str] = None
    min_version: Optional[str] = None
    labels: Dict[str, Any] = Field(default_factory=dict)


class FirmwareVersionUpdate(BaseModel):
    release_notes: Optional[str] = None
    is_deprecated: Optional[bool] = None
    status: Optional[str] = None


class FirmwareVersionResponse(BaseModel):
    id: str
    version: str
    device_model: str
    file_path: str
    file_size: int
    checksum: str
    signature: Optional[str]
    release_notes: Optional[str]
    is_deprecated: bool
    min_version: Optional[str]
    status: str
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class DeltaPackageCreate(BaseModel):
    from_version: str
    to_version: str
    device_model: str
    file_path: str
    file_size: int = 0
    checksum: str
    compression_method: str = "bsdiff"


class DeltaPackageResponse(BaseModel):
    id: str
    delta_id: str
    from_version: str
    to_version: str
    device_model: str
    file_path: str
    file_size: int
    checksum: str
    compression_method: str
    status: str
    created_at: datetime

    class Config:
        from_attributes = True


class OTAUpgradeTaskCreate(BaseModel):
    firmware_version_id: str
    device_ids: List[str] = Field(default_factory=list)
    strategy: str = "instant"
    batch_size: int = 10
    auto_rollback: bool = True
    rollback_threshold: float = 0.2
    labels: Dict[str, Any] = Field(default_factory=dict)


class OTAUpgradeTaskUpdate(BaseModel):
    status: Optional[str] = None
    phase: Optional[str] = None
    progress: Optional[float] = None
    success_count: Optional[int] = None
    failed_count: Optional[int] = None
    current_batch: Optional[int] = None
    completed_at: Optional[datetime] = None
    error_detail: Optional[Dict[str, Any]] = None


class OTAUpgradeTaskResponse(BaseModel):
    id: str
    run_id: str
    firmware_version_id: str
    device_ids: List[str]
    status: str
    phase: str
    progress: float
    success_count: int
    failed_count: int
    total_count: int
    strategy: str
    batch_size: int
    current_batch: int
    auto_rollback: bool
    rollback_threshold: float
    started_at: Optional[datetime]
    completed_at: Optional[datetime]
    error_detail: Optional[Dict[str, Any]]
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class DeviceUpgradeRecordResponse(BaseModel):
    id: str
    device_id: str
    task_id: str
    from_version: str
    to_version: str
    status: str
    phase: str
    progress: float
    error_code: Optional[str]
    error_message: Optional[str]
    rollback_triggered: bool
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class UpgradeProgressUpdate(BaseModel):
    device_id: str
    task_id: str
    phase: str
    progress: float
    error_code: Optional[str] = None
    error_message: Optional[str] = None


class DeltaGenerationRequest(BaseModel):
    from_version: str
    to_version: str
    device_model: str
    old_file_path: str
    new_file_path: str


class RollbackRequest(BaseModel):
    task_id: str
    device_ids: Optional[List[str]] = None
    reason: Optional[str] = None
