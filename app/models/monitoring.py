from sqlalchemy import Column, String, Float, ForeignKey, Index, DateTime
from sqlalchemy.dialects.postgresql import UUID, JSONB
import uuid

from app.models.base import Base, TimestampMixin


class MetricSnapshot(Base, TimestampMixin):
    __tablename__ = "metric_snapshots"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)
    metrics = Column(JSONB, nullable=False)
    dimensions = Column(JSONB, default=lambda: {})
    host = Column(String(255), index=True)
    region = Column(String(255), index=True)
    service = Column(String(255), index=True)
    meta_data = Column(JSONB, default=lambda: {})


class AuditLog(Base, TimestampMixin):
    __tablename__ = "audit_logs"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)
    user_id = Column(UUID(as_uuid=True), ForeignKey("users.id"), index=True)
    action = Column(String(255), nullable=False, index=True)
    resource_type = Column(String(255), index=True)
    resource_id = Column(String(255), index=True)
    status = Column(String(50), nullable=False)
    request_details = Column(JSONB, default=lambda: {})
    response_details = Column(JSONB, default=lambda: {})
    ip_address = Column(String(50))
    user_agent = Column(String(1000))
    meta_data = Column(JSONB, default=lambda: {})

    __table_args__ = (
        Index("ix_audit_log_action_resource", "action", "resource_type", "timestamp"),
    )
