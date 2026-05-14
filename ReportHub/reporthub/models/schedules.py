from sqlalchemy import Column, String, Integer, DateTime, Text, JSON, ForeignKey, Boolean
from sqlalchemy.orm import relationship
from datetime import datetime

from reporthub.models.base import Base


class Schedule(Base):
    __tablename__ = "schedules"

    id = Column(Integer, primary_key=True, index=True)
    schedule_id = Column(String(100), unique=True, index=True, nullable=False)
    template_id = Column(String(100), ForeignKey("report_templates.template_id"), nullable=False)
    schedule_type = Column(String(50), nullable=False, default="cron")
    schedule_cron = Column(String(100), nullable=True)
    schedule_interval = Column(Integer, nullable=True)
    export_format = Column(String(20), nullable=False, default="xlsx")
    notify_users = Column(JSON, nullable=True)
    enabled = Column(Boolean, default=True, nullable=False)
    last_run_at = Column(DateTime, nullable=True)
    next_run_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    template = relationship("ReportTemplate", back_populates="schedules")
