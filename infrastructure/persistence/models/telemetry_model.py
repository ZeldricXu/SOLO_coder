from sqlalchemy import Column, String, DateTime, JSON, Integer, Float, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from infrastructure.persistence.database import Base


class TelemetryDataModel(Base):
    __tablename__ = "telemetry_data"

    id = Column(Integer, primary_key=True, autoincrement=True)
    device_id = Column(String, ForeignKey("devices.device_id"), index=True)
    timestamp = Column(DateTime, default=datetime.utcnow, index=True)
    data = Column(JSON, default=dict)
    quality = Column(Integer, default=100)
    model_metadata = Column("metadata", JSON, default=dict)

    created_at = Column(DateTime, default=datetime.utcnow)

    device = relationship("DeviceModel", back_populates="telemetry_data")


class AggregatedDataModel(Base):
    __tablename__ = "aggregated_data"

    id = Column(Integer, primary_key=True, autoincrement=True)
    device_id = Column(String, ForeignKey("devices.device_id"), index=True)
    metric = Column(String, index=True)
    aggregation_type = Column(String, index=True)
    period_start = Column(DateTime, index=True)
    period_end = Column(DateTime, index=True)
    value = Column(Float)
    count = Column(Integer)
    min_value = Column(Float)
    max_value = Column(Float)
    sum_value = Column(Float)
    avg_value = Column(Float)
    std_dev = Column(Float)
    model_metadata = Column("metadata", JSON, default=dict)
    created_at = Column(DateTime, default=datetime.utcnow)
