from datetime import datetime, timezone
from typing import Any, Dict, Optional
from uuid import uuid4
from sqlalchemy import Column, String, Integer, DateTime, JSON, Float, Boolean, ForeignKey
from sqlalchemy.orm import relationship

from .database import Base


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class CoreEntity(Base):
    __abstract__ = True

    id = Column(String, primary_key=True, default=lambda: generate_id("ent"))
    type = Column(String, nullable=False, default="entity")
    status = Column(String, nullable=False, default="pending")
    attributes = Column(JSON, default=dict)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    updated_at = Column(
        DateTime, default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc)
    )


class ConfigModel(Base):
    __abstract__ = True

    config_id = Column(String, primary_key=True, default=lambda: generate_id("cfg"))
    namespace = Column(String, nullable=False, default="default")
    version = Column(Integer, nullable=False, default=1)
    parameters = Column(JSON, default=dict)
    enabled = Column(Boolean, default=True)
    applied_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))


class RunInstance(Base):
    __abstract__ = True

    run_id = Column(String, primary_key=True, default=lambda: generate_id("run"))
    entity_id = Column(String, nullable=False)
    phase = Column(String, nullable=False, default="initializing")
    progress = Column(Float, default=0.0)
    started_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    completed_at = Column(DateTime, nullable=True)
    error_detail = Column(JSON, nullable=True)


class SnapshotModel(Base):
    __abstract__ = True

    snapshot_id = Column(String, primary_key=True, default=lambda: generate_id("snap"))
    timestamp = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    metrics = Column(JSON, default=dict)
    dimensions = Column(JSON, default=dict)


class User(CoreEntity):
    __tablename__ = "users"

    type = Column(String, nullable=False, default="user")
    username = Column(String, unique=True, nullable=False)
    email = Column(String, unique=True, nullable=False)
    hashed_password = Column(String, nullable=False)
    roles = Column(JSON, default=list)
    permissions = Column(JSON, default=list)
