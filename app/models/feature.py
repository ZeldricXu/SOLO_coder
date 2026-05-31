from sqlalchemy import Column, String, Boolean, Integer, ForeignKey, Index
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import relationship
import uuid

from app.models.base import Base, TimestampMixin


class Feature(Base, TimestampMixin):
    __tablename__ = "features"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False, index=True)
    namespace = Column(String(255), nullable=False, index=True)
    description = Column(String(1000))
    entity_type = Column(String(100), nullable=False)
    value_type = Column(String(50), nullable=False)
    is_online = Column(Boolean, default=True, nullable=False)
    is_offline = Column(Boolean, default=True, nullable=False)
    ttl_seconds = Column(Integer, default=86400)
    schema_definition = Column(JSONB, nullable=False)
    meta_data = Column(JSONB, default=lambda: {})
    versions = relationship("FeatureVersion", back_populates="feature", cascade="all, delete-orphan")

    __table_args__ = (
        Index("ix_feature_namespace_name", "namespace", "name", unique=True),
    )


class FeatureVersion(Base, TimestampMixin):
    __tablename__ = "feature_versions"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    feature_id = Column(UUID(as_uuid=True), ForeignKey("features.id"), nullable=False, index=True)
    version = Column(Integer, nullable=False)
    data_source = Column(String(255))
    transformation_logic = Column(JSONB)
    checksum = Column(String(64))
    is_active = Column(Boolean, default=True, nullable=False)
    meta_data = Column(JSONB, default=lambda: {})
    feature = relationship("Feature", back_populates="versions")

    __table_args__ = (
        Index("ix_feature_version_feature_id_version", "feature_id", "version", unique=True),
    )
