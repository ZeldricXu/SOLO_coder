from sqlalchemy import Column, Integer, String, DateTime, Text, ForeignKey
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from app.database import Base


class Service(Base):
    __tablename__ = "services"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    name = Column(String(100), nullable=False)
    service_type = Column(String(50), nullable=False)
    health_endpoint = Column(String(255), nullable=False)
    check_interval = Column(Integer, default=30)
    status = Column(String(20), default="unknown")
    last_check = Column(DateTime(timezone=True))
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    health_checks = relationship("HealthCheck", back_populates="service", cascade="all, delete-orphan")


class HealthCheck(Base):
    __tablename__ = "health_checks"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    service_id = Column(Integer, ForeignKey("services.id"), nullable=False, index=True)
    status = Column(String(20), nullable=False)
    response_time_ms = Column(Integer)
    details = Column(Text)
    checked_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)

    service = relationship("Service", back_populates="health_checks")
