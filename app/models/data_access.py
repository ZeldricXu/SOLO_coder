from sqlalchemy import Column, String, Integer, Text, ForeignKey, Index, Boolean, DateTime
from sqlalchemy.dialects.postgresql import UUID, JSONB
import uuid

from app.models.base import Base, TimestampMixin


class SchemaVersion(Base, TimestampMixin):
    __tablename__ = "schema_versions"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    schema_name = Column(String(255), nullable=False, index=True)
    version = Column(Integer, nullable=False)
    definition = Column(JSONB, nullable=False)
    description = Column(String(1000))
    is_current = Column(Boolean, default=False, nullable=False)
    migration_script = Column(Text)
    rollback_script = Column(Text)
    created_by = Column(UUID(as_uuid=True), ForeignKey("users.id"))
    meta_data = Column(JSONB, default=lambda: {})

    __table_args__ = (
        Index("ix_schema_version_name_version", "schema_name", "version", unique=True),
    )


class DataMigration(Base, TimestampMixin):
    __tablename__ = "data_migrations"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False, index=True)
    source_schema_version_id = Column(
        UUID(as_uuid=True), ForeignKey("schema_versions.id"), nullable=False
    )
    target_schema_version_id = Column(
        UUID(as_uuid=True), ForeignKey("schema_versions.id"), nullable=False
    )
    status = Column(String(50), default="pending", nullable=False)
    script = Column(Text, nullable=False)
    rollback_script = Column(Text)
    started_at = Column(DateTime(timezone=True))
    completed_at = Column(DateTime(timezone=True))
    rows_processed = Column(Integer, default=0)
    rows_failed = Column(Integer, default=0)
    error_message = Column(String(5000))
    is_auto_recoverable = Column(Boolean, default=True, nullable=False)
    retry_count = Column(Integer, default=0)
    meta_data = Column(JSONB, default=lambda: {})
