from sqlalchemy import Column, String, DateTime, JSON, Integer, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from infrastructure.persistence.database import Base


class DeviceShadowModel(Base):
    __tablename__ = "device_shadows"

    id = Column(Integer, primary_key=True, autoincrement=True)
    device_id = Column(String, ForeignKey("devices.device_id"), unique=True, index=True)
    version = Column(Integer, default=1)

    desired = Column(JSON, default=dict)
    reported = Column(JSON, default=dict)
    delta = Column(JSON, default=dict)

    state = Column(String, default="synced")
    last_sync_time = Column(DateTime)
    last_cloud_sync_time = Column(DateTime)

    shadow_metadata = Column("metadata", JSON, default=dict)
    error_message = Column(String)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    device = relationship("DeviceModel", back_populates="shadow")
