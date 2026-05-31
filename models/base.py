import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional
from sqlalchemy import Column, DateTime, JSON, String
from sqlalchemy.dialects.sqlite import JSON as SQLiteJSON

from core import Base


def generate_uuid() -> str:
    return str(uuid.uuid4())


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class BaseModel(Base):
    __abstract__ = True

    id = Column(String, primary_key=True, default=generate_uuid, index=True)
    created_at = Column(DateTime, default=utc_now, nullable=False)
    updated_at = Column(DateTime, default=utc_now, onupdate=utc_now, nullable=False)
    metadata_ = Column("metadata", JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            c.name: getattr(self, c.name) for c in self.__table__.columns
        }


class EntityModel(BaseModel):
    __abstract__ = True

    type = Column(String, nullable=False)
    status = Column(String, nullable=False, default="pending")
    attributes = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    labels = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)


class ConfigModel(BaseModel):
    __abstract__ = True

    config_id = Column(String, nullable=False, index=True)
    namespace = Column(String, nullable=False, default="default")
    version = Column(String, nullable=False, default="1.0.0")
    parameters = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    enabled = Column(String, default="true")
    applied_at = Column(DateTime, nullable=True)


class RunInstanceModel(BaseModel):
    __abstract__ = True

    run_id = Column(String, nullable=False, index=True)
    entity_id = Column(String, nullable=False, index=True)
    phase = Column(String, nullable=False, default="pending")
    progress = Column(String, default="0.0")
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)
    error_detail = Column(JSON().with_variant(SQLiteJSON, "sqlite"), nullable=True)


class SnapshotModel(BaseModel):
    __abstract__ = True

    snapshot_id = Column(String, nullable=False, index=True)
    timestamp = Column(DateTime, nullable=False, default=utc_now)
    metrics = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    dimensions = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
