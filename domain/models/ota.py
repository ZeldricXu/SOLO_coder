from enum import Enum
from datetime import datetime
from typing import Dict, Any, Optional, List
from pydantic import BaseModel, Field


class UpgradeStatus(str, Enum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    DOWNLOADED = "downloaded"
    INSTALLING = "installing"
    REBOOTING = "rebooting"
    VERIFYING = "verifying"
    COMPLETED = "completed"
    FAILED = "failed"
    ROLLING_BACK = "rolling_back"
    ROLLED_BACK = "rolled_back"
    CANCELLED = "cancelled"


class UpgradeStrategy(str, Enum):
    SEQUENTIAL = "sequential"
    PARALLEL = "parallel"
    BATCH = "batch"
    CANARY = "canary"


class OTAPackage(BaseModel):
    package_id: str
    package_name: str
    version: str
    firmware_version: str

    file_path: str
    file_size: int
    checksum: str
    checksum_algorithm: str = "sha256"

    min_hardware_version: Optional[str] = None
    max_hardware_version: Optional[str] = None
    min_firmware_version: Optional[str] = None

    release_notes: Optional[str] = None
    changelog: Dict[str, Any] = Field(default_factory=dict)

    is_delta: bool = False
    base_version: Optional[str] = None
    diff_file_path: Optional[str] = None

    enabled: bool = True
    force_upgrade: bool = False
    auto_apply: bool = False

    metadata: Dict[str, Any] = Field(default_factory=dict)

    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class UpgradeTask(BaseModel):
    task_id: str
    package_id: str
    device_id: str

    status: UpgradeStatus = UpgradeStatus.PENDING
    strategy: UpgradeStrategy = UpgradeStrategy.SEQUENTIAL

    scheduled_at: Optional[datetime] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None

    download_progress: int = 0
    install_progress: int = 0

    current_version: Optional[str] = None
    target_version: Optional[str] = None

    error_message: Optional[str] = None
    error_code: Optional[int] = None
    retry_count: int = 0
    max_retries: int = 3

    rollback_on_failure: bool = True
    rollback_version: Optional[str] = None
    rolled_back_at: Optional[datetime] = None

    batch_id: Optional[str] = None
    batch_number: int = 0
    total_batches: int = 1

    metadata: Dict[str, Any] = Field(default_factory=dict)

    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    def start_download(self) -> None:
        self.status = UpgradeStatus.DOWNLOADING
        self.started_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def download_complete(self) -> None:
        self.status = UpgradeStatus.DOWNLOADED
        self.download_progress = 100
        self.updated_at = datetime.utcnow()

    def start_install(self) -> None:
        self.status = UpgradeStatus.INSTALLING
        self.updated_at = datetime.utcnow()

    def start_reboot(self) -> None:
        self.status = UpgradeStatus.REBOOTING
        self.updated_at = datetime.utcnow()

    def start_verify(self) -> None:
        self.status = UpgradeStatus.VERIFYING
        self.updated_at = datetime.utcnow()

    def complete(self) -> None:
        self.status = UpgradeStatus.COMPLETED
        self.install_progress = 100
        self.completed_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def fail(self, error_message: str, error_code: Optional[int] = None) -> None:
        self.status = UpgradeStatus.FAILED
        self.error_message = error_message
        self.error_code = error_code
        self.completed_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def start_rollback(self) -> None:
        self.status = UpgradeStatus.ROLLING_BACK
        self.updated_at = datetime.utcnow()

    def rollback_complete(self) -> None:
        self.status = UpgradeStatus.ROLLED_BACK
        self.rolled_back_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def cancel(self) -> None:
        self.status = UpgradeStatus.CANCELLED
        self.completed_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def update_progress(self, download: Optional[int] = None, install: Optional[int] = None) -> None:
        if download is not None:
            self.download_progress = min(100, max(0, download))
        if install is not None:
            self.install_progress = min(100, max(0, install))
        self.updated_at = datetime.utcnow()

    def is_complete(self) -> bool:
        return self.status in [UpgradeStatus.COMPLETED, UpgradeStatus.ROLLED_BACK, UpgradeStatus.CANCELLED]

    def should_retry(self) -> bool:
        return self.status == UpgradeStatus.FAILED and self.retry_count < self.max_retries


class UpgradeBatch(BaseModel):
    batch_id: str
    task_ids: List[str] = Field(default_factory=list)
    device_ids: List[str] = Field(default_factory=list)
    batch_number: int
    total_batches: int
    canary: bool = False
    delay_seconds: int = 0
    success_threshold: float = 1.0
