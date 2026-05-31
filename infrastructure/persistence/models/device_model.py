from sqlalchemy import Column, String, DateTime, JSON, Integer
from sqlalchemy.orm import relationship
from datetime import datetime

from infrastructure.persistence.database import Base


class DeviceModel(Base):
    __tablename__ = "devices"

    device_id = Column(String, primary_key=True, index=True)
    device_name = Column(String, nullable=False)
    device_type = Column(String, nullable=False)
    protocol = Column(String, nullable=False)
    status = Column(String, default="unregistered")

    manufacturer = Column(String)
    model = Column(String)
    firmware_version = Column(String)
    hardware_version = Column(String)

    protocol_config = Column(JSON, default=dict)
    device_metadata = Column("metadata", JSON, default=dict)
    tags = Column(JSON, default=list)

    last_seen = Column(DateTime)
    last_ip = Column(String)

    registered_at = Column(DateTime)
    activated_at = Column(DateTime)
    deactivated_at = Column(DateTime)

    auth_token = Column(String)
    certificate = Column(String)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    shadow = relationship("DeviceShadowModel", back_populates="device", uselist=False, cascade="all, delete-orphan")
    telemetry_data = relationship("TelemetryDataModel", back_populates="device", cascade="all, delete-orphan")
    upgrade_tasks = relationship("UpgradeTaskModel", back_populates="device", cascade="all, delete-orphan")
    events = relationship("EventModel", back_populates="device")
