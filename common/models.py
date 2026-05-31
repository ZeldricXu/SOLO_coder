from datetime import datetime, timezone
from sqlalchemy import Column, String, DateTime, JSON, Integer, Boolean, Float
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import declared_attr
import uuid


Base = declarative_base()


def generate_uuid() -> str:
    return str(uuid.uuid4())


class TimestampMixin:
    created_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
        index=True,
    )
    updated_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class EntityMixin(TimestampMixin):
    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    type = Column(String, nullable=False, default="entity")
    status = Column(String, nullable=False, default="active")
    attributes = Column(JSON, default=dict)


class CoreEntity(Base, EntityMixin):
    __tablename__ = "core_entities"


class ConfigDefinition(Base, EntityMixin):
    __tablename__ = "config_definitions"

    config_id = Column(String, nullable=False, index=True)
    namespace = Column(String, nullable=False, index=True)
    version = Column(Integer, nullable=False, default=1)
    parameters = Column(JSON, default=dict)
    enabled = Column(Boolean, default=True)
    applied_at = Column(DateTime(timezone=True))


class RunInstance(Base, EntityMixin):
    __tablename__ = "run_instances"

    run_id = Column(String, nullable=False, index=True, default=generate_uuid)
    entity_id = Column(String, nullable=False, index=True)
    phase = Column(String, nullable=False, default="initializing")
    progress = Column(Float, default=0.0)
    started_at = Column(DateTime(timezone=True))
    completed_at = Column(DateTime(timezone=True))
    error_detail = Column(JSON)


class StatsSnapshot(Base, EntityMixin):
    __tablename__ = "stats_snapshots"

    snapshot_id = Column(String, nullable=False, index=True, default=generate_uuid)
    timestamp = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    metrics = Column(JSON, default=dict)
    dimensions = Column(JSON, default=dict)
