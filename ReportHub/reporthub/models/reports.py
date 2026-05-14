from sqlalchemy import Column, String, Integer, DateTime, Text, JSON, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from reporthub.models.base import Base


class Report(Base):
    __tablename__ = "reports"

    id = Column(Integer, primary_key=True, index=True)
    report_id = Column(String(100), unique=True, index=True, nullable=False)
    template_id = Column(String(100), ForeignKey("report_templates.template_id"), nullable=False)
    report_name = Column(String(200), nullable=False)
    report_data = Column(JSON, nullable=False)
    report_file = Column(String(500), nullable=True)
    report_format = Column(String(20), nullable=False, default="xlsx")
    generated_at = Column(DateTime, default=datetime.utcnow)
    generator = Column(String(100), nullable=True)
    status = Column(String(20), nullable=False, default="completed")
    report_params = Column(JSON, nullable=True)

    template = relationship("ReportTemplate", back_populates="reports")
    versions = relationship("ReportVersion", back_populates="report")
