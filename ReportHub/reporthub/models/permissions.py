from sqlalchemy import Column, String, Integer, DateTime, Text, JSON, ForeignKey, Boolean
from sqlalchemy.orm import relationship
from datetime import datetime

from reporthub.models.base import Base


class ReportPermission(Base):
    __tablename__ = "report_permissions"

    id = Column(Integer, primary_key=True, index=True)
    permission_id = Column(String(100), unique=True, index=True, nullable=False)
    template_id = Column(String(100), ForeignKey("report_templates.template_id"), nullable=False)
    user_id = Column(String(100), nullable=False)
    role = Column(String(50), nullable=False, default="viewer")
    can_view = Column(Boolean, default=True, nullable=False)
    can_generate = Column(Boolean, default=False, nullable=False)
    can_export = Column(Boolean, default=False, nullable=False)
    can_manage = Column(Boolean, default=False, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    template = relationship("ReportTemplate", back_populates="permissions")
