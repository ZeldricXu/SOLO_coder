import uuid
from datetime import datetime, timezone
from typing import Dict, List, Optional
from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    ForeignKey,
    Integer,
    JSON,
    String,
    Text,
    UniqueConstraint,
    Index,
)
from sqlalchemy.dialects.postgresql import UUID, ENUM
from sqlalchemy.orm import relationship

from gateway.db.database import Base


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Route(Base):
    __tablename__ = "routes"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False, unique=True, index=True)
    description = Column(Text, nullable=True)

    path = Column(String(500), nullable=False, index=True)
    match_type = Column(
        ENUM("prefix", "regex", "weighted", name="route_match_type"),
        nullable=False,
        default="prefix",
    )
    path_pattern = Column(String(500), nullable=True)

    targets = Column(JSON, nullable=False, default=list)

    weight_rules = Column(JSON, nullable=True)

    methods = Column(JSON, nullable=False, default=list)

    auth_required = Column(Boolean, nullable=False, default=True)
    auth_strategy = Column(String(50), nullable=True)

    rate_limit_enabled = Column(Boolean, nullable=False, default=True)
    rate_limit_per_user = Column(Integer, nullable=True)
    rate_limit_per_api = Column(Integer, nullable=True)

    circuit_breaker_enabled = Column(Boolean, nullable=False, default=True)
    circuit_breaker_config = Column(JSON, nullable=True)

    transform_request = Column(JSON, nullable=True)
    transform_response = Column(JSON, nullable=True)

    timeout = Column(Integer, nullable=False, default=30)
    retry_count = Column(Integer, nullable=False, default=0)

    is_active = Column(Boolean, nullable=False, default=True, index=True)
    version = Column(Integer, nullable=False, default=1)

    created_at = Column(DateTime(timezone=True), nullable=False, default=utc_now)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utc_now, onupdate=utc_now)

    __table_args__ = (
        Index("idx_routes_active_path", "is_active", "path"),
    )


class APIKey(Base):
    __tablename__ = "api_keys"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    key = Column(String(64), nullable=False, unique=True, index=True)
    name = Column(String(255), nullable=False)
    description = Column(Text, nullable=True)

    user_id = Column(String(100), nullable=False, index=True)
    tenant_id = Column(String(100), nullable=True, index=True)

    scopes = Column(JSON, nullable=False, default=list)
    allowed_paths = Column(JSON, nullable=True)
    rate_limit_quota = Column(Integer, nullable=True)

    status = Column(
        ENUM("pending", "approved", "rejected", "revoked", name="api_key_status"),
        nullable=False,
        default="pending",
        index=True,
    )

    expires_at = Column(DateTime(timezone=True), nullable=True)
    last_used_at = Column(DateTime(timezone=True), nullable=True)

    created_by = Column(String(100), nullable=False)
    approved_by = Column(String(100), nullable=True)
    approved_at = Column(DateTime(timezone=True), nullable=True)

    created_at = Column(DateTime(timezone=True), nullable=False, default=utc_now)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utc_now, onupdate=utc_now)

    usage = relationship("APIKeyUsage", back_populates="api_key", cascade="all, delete-orphan")


class APIKeyUsage(Base):
    __tablename__ = "api_key_usage"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    api_key_id = Column(UUID(as_uuid=True), ForeignKey("api_keys.id", ondelete="CASCADE"), nullable=False)
    date = Column(DateTime(timezone=True), nullable=False, index=True)
    request_count = Column(Integer, nullable=False, default=0)
    error_count = Column(Integer, nullable=False, default=0)
    total_latency_ms = Column(Integer, nullable=False, default=0)

    api_key = relationship("APIKey", back_populates="usage")

    __table_args__ = (
        UniqueConstraint("api_key_id", "date", name="uix_api_key_usage_date"),
    )


class IdPConfig(Base):
    __tablename__ = "idp_configs"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(100), nullable=False, unique=True)
    provider = Column(
        ENUM("keycloak", "auth0", "custom", name="idp_provider"),
        nullable=False,
    )

    config = Column(JSON, nullable=False)

    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utc_now)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utc_now, onupdate=utc_now)


class TransformRule(Base):
    __tablename__ = "transform_rules"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False)
    description = Column(Text, nullable=True)

    rule_type = Column(
        ENUM("request_header", "request_query", "request_body",
             "response_header", "response_body", "response_status",
             name="transform_rule_type"),
        nullable=False,
    )

    path_pattern = Column(String(500), nullable=True)
    priority = Column(Integer, nullable=False, default=0)

    config = Column(JSON, nullable=False)

    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), nullable=False, default=utc_now)
    updated_at = Column(DateTime(timezone=True), nullable=False, default=utc_now, onupdate=utc_now)
