from sqlalchemy import Column, String, Integer, DateTime, JSON, Boolean, Float, ForeignKey, Text, Index
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
from app.database import Base
import uuid


def generate_uuid():
    return str(uuid.uuid4())


class User(Base):
    __tablename__ = "users"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    username = Column(String(100), unique=True, nullable=False, index=True)
    email = Column(String(255), unique=True, nullable=False)
    password_hash = Column(String(255), nullable=False)
    is_active = Column(Boolean, default=True)
    is_admin = Column(Boolean, default=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class Entity(Base):
    __tablename__ = "entities"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    type = Column(String(50), nullable=False, index=True)
    status = Column(String(50), default="pending", index=True)
    attributes = Column(JSON, default=dict)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
    
    __table_args__ = (
        Index('idx_entity_type_status', 'type', 'status'),
    )


class Config(Base):
    __tablename__ = "configs"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    config_id = Column(String(100), nullable=False, index=True)
    namespace = Column(String(100), nullable=False, index=True)
    version = Column(Integer, nullable=False, default=1)
    parameters = Column(JSON, nullable=False, default=dict)
    enabled = Column(Boolean, default=True)
    applied_at = Column(DateTime(timezone=True), server_default=func.now())
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    
    __table_args__ = (
        Index('idx_config_namespace_version', 'namespace', 'version'),
        Index('idx_config_config_id', 'config_id'),
    )


class DeviceShadow(Base):
    __tablename__ = "device_shadows"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    device_id = Column(String(100), unique=True, nullable=False, index=True)
    desired = Column(JSON, default=dict)
    reported = Column(JSON, default=dict)
    delta = Column(JSON, default=dict)
    version = Column(Integer, default=1)
    last_sync_at = Column(DateTime(timezone=True))
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class Firmware(Base):
    __tablename__ = "firmwares"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    version = Column(String(50), nullable=False, index=True)
    device_model = Column(String(100), nullable=False, index=True)
    file_path = Column(String(500), nullable=False)
    file_size = Column(Integer)
    checksum = Column(String(64))
    diff_from_version = Column(String(50), nullable=True)
    diff_file_path = Column(String(500), nullable=True)
    is_enabled = Column(Boolean, default=True)
    release_notes = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    
    __table_args__ = (
        Index('idx_firmware_model_version', 'device_model', 'version'),
    )


class OTACampaign(Base):
    __tablename__ = "ota_campaigns"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    firmware_id = Column(String, ForeignKey("firmwares.id"), nullable=False, index=True)
    name = Column(String(200), nullable=False)
    status = Column(String(50), default="pending", index=True)
    grayscale_percent = Column(Integer, default=100)
    current_batch = Column(Integer, default=0)
    total_devices = Column(Integer, default=0)
    success_count = Column(Integer, default=0)
    failed_count = Column(Integer, default=0)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    auto_rollback = Column(Boolean, default=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class OTAStatus(Base):
    __tablename__ = "ota_status"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    campaign_id = Column(String, ForeignKey("ota_campaigns.id"), nullable=False, index=True)
    device_id = Column(String(100), nullable=False, index=True)
    status = Column(String(50), default="pending", index=True)
    current_version = Column(String(50))
    target_version = Column(String(50))
    retry_count = Column(Integer, default=0)
    last_error = Column(Text, nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class ScheduledTask(Base):
    __tablename__ = "scheduled_tasks"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    name = Column(String(200), nullable=False, index=True)
    task_type = Column(String(50), nullable=False, index=True)
    payload = Column(JSON, default=dict)
    dependencies = Column(JSON, default=list)
    status = Column(String(50), default="pending", index=True)
    priority = Column(Integer, default=0)
    scheduled_at = Column(DateTime(timezone=True), nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    result = Column(JSON, nullable=True)
    error_message = Column(Text, nullable=True)
    run_id = Column(String(100), nullable=True, index=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    
    __table_args__ = (
        Index('idx_task_status_priority', 'status', 'priority'),
    )


class EdgeModel(Base):
    __tablename__ = "edge_models"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    model_id = Column(String(100), unique=True, nullable=False, index=True)
    name = Column(String(200), nullable=False)
    version = Column(String(50), nullable=False)
    model_path = Column(String(500), nullable=False)
    model_type = Column(String(50), nullable=False)
    input_spec = Column(JSON, default=dict)
    output_spec = Column(JSON, default=dict)
    requirements = Column(JSON, default=dict)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class InferenceJob(Base):
    __tablename__ = "inference_jobs"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    model_id = Column(String, ForeignKey("edge_models.id"), nullable=False, index=True)
    device_id = Column(String(100), nullable=False, index=True)
    input_data = Column(JSON, nullable=False)
    status = Column(String(50), default="pending", index=True)
    result = Column(JSON, nullable=True)
    error_message = Column(Text, nullable=True)
    latency_ms = Column(Integer, nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class Notification(Base):
    __tablename__ = "notifications"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    user_id = Column(String, ForeignKey("users.id"), nullable=True, index=True)
    title = Column(String(200), nullable=False)
    content = Column(Text, nullable=False)
    priority = Column(Integer, default=0, index=True)
    category = Column(String(50), nullable=True, index=True)
    is_read = Column(Boolean, default=False)
    suppressed_until = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)
    
    __table_args__ = (
        Index('idx_notification_user_read', 'user_id', 'is_read'),
    )


class DataSnapshot(Base):
    __tablename__ = "data_snapshots"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    snapshot_id = Column(String(100), unique=True, nullable=False, index=True)
    timestamp = Column(DateTime(timezone=True), server_default=func.now())
    metrics = Column(JSON, default=dict)
    dimensions = Column(JSON, default=dict)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class RunInstance(Base):
    __tablename__ = "run_instances"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    run_id = Column(String(100), unique=True, nullable=False, index=True)
    entity_id = Column(String, ForeignKey("entities.id"), nullable=True, index=True)
    phase = Column(String(50), default="initializing", index=True)
    progress = Column(Float, default=0.0)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    error_detail = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class SchemaVersion(Base):
    __tablename__ = "schema_versions"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    version = Column(Integer, nullable=False, unique=True, index=True)
    description = Column(Text, nullable=True)
    migration_script = Column(Text, nullable=True)
    applied_at = Column(DateTime(timezone=True), server_default=func.now())


class BackupRecord(Base):
    __tablename__ = "backup_records"
    
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    backup_id = Column(String(100), unique=True, nullable=False, index=True)
    backup_type = Column(String(50), nullable=False)
    file_path = Column(String(500), nullable=False)
    file_size = Column(Integer)
    checksum = Column(String(64))
    status = Column(String(50), default="completed", index=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)
