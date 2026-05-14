from sqlalchemy import Column, String, Integer, DateTime, Text, JSON, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from reporthub.models.base import Base


class ReportStat(Base):
    __tablename__ = "report_stats"

    id = Column(Integer, primary_key=True, index=True)
    stat_id = Column(String(100), unique=True, index=True, nullable=False)
    template_id = Column(String(100), ForeignKey("report_templates.template_id"), nullable=False)
    stat_month = Column(String(20), nullable=False)
    generate_count = Column(Integer, default=0, nullable=False)
    export_count = Column(Integer, default=0, nullable=False)
    total_rows = Column(Integer, default=0, nullable=False)
    avg_generate_time = Column(Integer, default=0, nullable=False)
    total_generate_time = Column(Integer, default=0, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    template = relationship("ReportTemplate", back_populates="stats")
