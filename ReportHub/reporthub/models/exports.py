from sqlalchemy import Column, String, Integer, DateTime, Text, JSON, ForeignKey
from datetime import datetime

from reporthub.models.base import Base


class ExportConfig(Base):
    __tablename__ = "export_configs"

    id = Column(Integer, primary_key=True, index=True)
    export_id = Column(String(100), unique=True, index=True, nullable=False)
    template_id = Column(String(100), ForeignKey("report_templates.template_id"), nullable=False)
    export_formats = Column(JSON, nullable=False)
    export_options = Column(JSON, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
