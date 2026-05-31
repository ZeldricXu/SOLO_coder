from sqlalchemy import Column, String, JSON, Integer, Boolean, DateTime, ForeignKey, Float
from sqlalchemy.dialects.sqlite import JSON as SQLiteJSON
from sqlalchemy.orm import relationship

from models import EntityModel, RunInstanceModel, generate_uuid, utc_now


class FirmwareVersion(EntityModel):
    __tablename__ = "firmware_versions"

    version = Column(String, nullable=False)
    device_model = Column(String, nullable=False)
    file_path = Column(String, nullable=False)
    file_size = Column(Integer, default=0)
    checksum = Column(String, nullable=False)
    signature = Column(String, nullable=True)
    release_notes = Column(String, nullable=True)
    is_deprecated = Column(Boolean, default=False)
    min_version = Column(String, nullable=True)


class DeltaPackage(EntityModel):
    __tablename__ = "delta_packages"

    delta_id = Column(String, default=generate_uuid, index=True)
    from_version = Column(String, nullable=False)
    to_version = Column(String, nullable=False)
    device_model = Column(String, nullable=False)
    file_path = Column(String, nullable=False)
    file_size = Column(Integer, default=0)
    checksum = Column(String, nullable=False)
    compression_method = Column(String, default="bsdiff")


class OTAUpgradeTask(RunInstanceModel):
    __tablename__ = "ota_upgrade_tasks"

    firmware_version_id = Column(String, nullable=False)
    device_ids = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=list)
    success_count = Column(Integer, default=0)
    failed_count = Column(Integer, default=0)
    total_count = Column(Integer, default=0)
    strategy = Column(String, default="instant")
    batch_size = Column(Integer, default=10)
    current_batch = Column(Integer, default=0)
    auto_rollback = Column(Boolean, default=True)
    rollback_threshold = Column(Float, default=0.2)
    started_at = Column(DateTime, default=utc_now)
    completed_at = Column(DateTime, nullable=True)


class DeviceUpgradeRecord(EntityModel):
    __tablename__ = "device_upgrade_records"

    device_id = Column(String, nullable=False, index=True)
    task_id = Column(String, nullable=False, index=True)
    from_version = Column(String, nullable=False)
    to_version = Column(String, nullable=False)
    status = Column(String, default="pending")
    phase = Column(String, default="pending")
    progress = Column(Float, default=0.0)
    download_started_at = Column(DateTime, nullable=True)
    download_completed_at = Column(DateTime, nullable=True)
    upgrade_started_at = Column(DateTime, nullable=True)
    upgrade_completed_at = Column(DateTime, nullable=True)
    error_code = Column(String, nullable=True)
    error_message = Column(String, nullable=True)
    rollback_triggered = Column(Boolean, default=False)
    rollback_completed_at = Column(DateTime, nullable=True)
