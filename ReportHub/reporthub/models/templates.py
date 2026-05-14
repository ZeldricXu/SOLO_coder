from sqlalchemy import Column, String, Integer, DateTime, Text, JSON
from sqlalchemy.orm import relationship
from datetime import datetime

from reporthub.models.base import Base


class ReportTemplate(Base):
    __tablename__ = "report_templates"

    id = Column(Integer, primary_key=True, index=True)
    template_id = Column(String(100), unique=True, index=True, nullable=False)
    template_name = Column(String(200), nullable=False)
    template_type = Column(String(50), nullable=False, default="table")
    data_source = Column(JSON, nullable=False)
    fields = Column(JSON, nullable=False)
    filters = Column(JSON, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    reports = relationship("Report", back_populates="template")
    schedules = relationship("Schedule", back_populates="template")
    stats = relationship("ReportStat", back_populates="template")
    permissions = relationship("ReportPermission", back_populates="template")
