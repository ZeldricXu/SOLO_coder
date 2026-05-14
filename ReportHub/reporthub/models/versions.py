from sqlalchemy import Column, String, Integer, DateTime, Text, JSON, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from reporthub.models.base import Base


class ReportVersion(Base):
    __tablename__ = "report_versions"

    id = Column(Integer, primary_key=True, index=True)
    version_id = Column(String(100), unique=True, index=True, nullable=False)
    report_id = Column(String(100), ForeignKey("reports.report_id"), nullable=False)
    version = Column(String(20), nullable=False)
    report_file = Column(String(500), nullable=True)
    report_data = Column(JSON, nullable=True)
    generated_at = Column(DateTime, default=datetime.utcnow)
    change_desc = Column(String(500), nullable=True)

    report = relationship("Report", back_populates="versions")
