from sqlalchemy import Column, String, Integer, BigInteger, Boolean, ForeignKey, Index, DateTime
from sqlalchemy.dialects.postgresql import UUID, JSONB
import uuid

from app.models.base import Base, TimestampMixin


class StorageObject(Base, TimestampMixin):
    __tablename__ = "storage_objects"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    bucket = Column(String(255), nullable=False, index=True)
    key = Column(String(1000), nullable=False, index=True)
    version_id = Column(String(255))
    size_bytes = Column(BigInteger, nullable=False)
    content_type = Column(String(255))
    checksum = Column(String(64))
    storage_class = Column(String(50), default="standard")
    is_archived = Column(Boolean, default=False, nullable=False)
    last_accessed_at = Column(DateTime(timezone=True))
    access_count = Column(Integer, default=0)
    meta_data = Column(JSONB, default=lambda: {})
    tags = Column(JSONB, default=lambda: {})

    __table_args__ = (
        Index("ix_storage_object_bucket_key", "bucket", "key", unique=True),
    )


class StorageMetadata(Base, TimestampMixin):
    __tablename__ = "storage_metadata"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    storage_object_id = Column(
        UUID(as_uuid=True), ForeignKey("storage_objects.id"), nullable=False, index=True
    )
    key = Column(String(255), nullable=False)
    value = Column(JSONB)
    data_type = Column(String(50), default="string")
    is_searchable = Column(Boolean, default=True, nullable=False)
