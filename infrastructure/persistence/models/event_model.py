from sqlalchemy import Column, String, DateTime, JSON, Integer, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from infrastructure.persistence.database import Base


class EventModel(Base):
    __tablename__ = "events"

    event_id = Column(String, primary_key=True, index=True)
    event_type = Column(String, nullable=False, index=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    source = Column(String, default="edge-node")

    device_id = Column(String, ForeignKey("devices.device_id"), index=True)
    data = Column(JSON, default=dict)

    correlation_id = Column(String, index=True)
    causation_id = Column(String)

    model_metadata = Column("metadata", JSON, default=dict)

    device = relationship("DeviceModel", back_populates="events")
