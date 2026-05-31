from sqlalchemy import Column, String, JSON, Integer, Boolean, DateTime, Float
from sqlalchemy.dialects.sqlite import JSON as SQLiteJSON

from models import BaseModel, utc_now


class Device(BaseModel):
    __tablename__ = "devices"

    device_id = Column(String, unique=True, index=True, nullable=False)
    name = Column(String, nullable=False)
    description = Column(String, nullable=True)
    device_model = Column(String, nullable=False)
    manufacturer = Column(String, nullable=True)
    serial_number = Column(String, nullable=True)
    firmware_version = Column(String, nullable=True)
    hardware_version = Column(String, nullable=True)
    status = Column(String, default="inactive")
    activation_status = Column(String, default="pending")
    activation_code = Column(String, nullable=True)
    activated_at = Column(DateTime, nullable=True)
    last_seen_at = Column(DateTime, nullable=True)
    ip_address = Column(String, nullable=True)
    mac_address = Column(String, nullable=True)
    location = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    tags = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=list)
    config = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    capabilities = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    heartbeat_interval = Column(Integer, default=60)
    heartbeat_timeout = Column(Integer, default=300)
    is_gateway = Column(Boolean, default=False)
    parent_device_id = Column(String, nullable=True)


class DeviceAuth(BaseModel):
    __tablename__ = "device_auth"

    device_id = Column(String, index=True, nullable=False)
    auth_type = Column(String, nullable=False)
    api_key = Column(String, nullable=True)
    api_secret = Column(String, nullable=True)
    certificate = Column(String, nullable=True)
    private_key = Column(String, nullable=True)
    token = Column(String, nullable=True)
    token_expires_at = Column(DateTime, nullable=True)
    last_authenticated_at = Column(DateTime, nullable=True)
    auth_count = Column(Integer, default=0)
    last_failed_auth_at = Column(DateTime, nullable=True)
    failed_auth_count = Column(Integer, default=0)
    is_revoked = Column(Boolean, default=False)
    revoked_at = Column(DateTime, nullable=True)


class DeviceHeartbeat(BaseModel):
    __tablename__ = "device_heartbeats"

    device_id = Column(String, index=True, nullable=False)
    timestamp = Column(DateTime, default=utc_now, nullable=False)
    ip_address = Column(String, nullable=True)
    status = Column(String, nullable=True)
    cpu_usage = Column(Float, nullable=True)
    memory_usage = Column(Float, nullable=True)
    disk_usage = Column(Float, nullable=True)
    network_usage = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    metrics = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
