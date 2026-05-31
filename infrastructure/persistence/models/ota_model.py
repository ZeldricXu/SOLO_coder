from sqlalchemy import Column, String, DateTime, JSON, Integer, Boolean, Text, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from infrastructure.persistence.database import Base


class OTAPackageModel(Base):
    __tablename__ = "ota_packages"

    package_id = Column(String, primary_key=True, index=True)
    package_name = Column(String, nullable=False)
    version = Column(String, nullable=False)
    firmware_version = Column(String, nullable=False)

    file_path = Column(String, nullable=False)
    file_size = Column(Integer, nullable=False)
    checksum = Column(String, nullable=False)
    checksum_algorithm = Column(String, default="sha256")

    min_hardware_version = Column(String)
    max_hardware_version = Column(String)
    min_firmware_version = Column(String)

    release_notes = Column(Text)
    changelog = Column(JSON, default=dict)

    is_delta = Column(Boolean, default=False)
    base_version = Column(String)
    diff_file_path = Column(String)

    enabled = Column(Boolean, default=True)
    force_upgrade = Column(Boolean, default=False)
    auto_apply = Column(Boolean, default=False)

    model_metadata = Column("metadata", JSON, default=dict)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    upgrade_tasks = relationship("UpgradeTaskModel", back_populates="package")


class UpgradeTaskModel(Base):
    __tablename__ = "upgrade_tasks"

    task_id = Column(String, primary_key=True, index=True)
    package_id = Column(String, ForeignKey("ota_packages.package_id"), index=True)
    device_id = Column(String, ForeignKey("devices.device_id"), index=True)

    status = Column(String, default="pending")
    strategy = Column(String, default="sequential")

    scheduled_at = Column(DateTime)
    started_at = Column(DateTime)
    completed_at = Column(DateTime)

    download_progress = Column(Integer, default=0)
    install_progress = Column(Integer, default=0)

    current_version = Column(String)
    target_version = Column(String)

    error_message = Column(Text)
    error_code = Column(Integer)
    retry_count = Column(Integer, default=0)
    max_retries = Column(Integer, default=3)

    rollback_on_failure = Column(Boolean, default=True)
    rollback_version = Column(String)
    rolled_back_at = Column(DateTime)

    batch_id = Column(String, index=True)
    batch_number = Column(Integer, default=0)
    total_batches = Column(Integer, default=1)

    model_metadata = Column("metadata", JSON, default=dict)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    device = relationship("DeviceModel", back_populates="upgrade_tasks")
    package = relationship("OTAPackageModel", back_populates="upgrade_tasks")
