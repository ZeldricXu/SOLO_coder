from datetime import datetime
from typing import Any, Dict

from sqlalchemy import JSON, DateTime, Float, Integer, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class BaseORM(DeclarativeBase):
    pass


class EntityORM(BaseORM):
    __tablename__ = "entities"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    type: Mapped[str] = mapped_column(String(64), index=True)
    status: Mapped[str] = mapped_column(String(32), index=True)
    attributes: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )


class ConfigORM(BaseORM):
    __tablename__ = "configs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    config_id: Mapped[str] = mapped_column(String(64), index=True)
    namespace: Mapped[str] = mapped_column(String(64), index=True, default="default")
    version: Mapped[int] = mapped_column(Integer, index=True)
    parameters: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    enabled: Mapped[bool] = mapped_column(default=True)
    applied_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class RunInstanceORM(BaseORM):
    __tablename__ = "run_instances"

    run_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    entity_id: Mapped[str] = mapped_column(String(64), index=True)
    phase: Mapped[str] = mapped_column(String(32), index=True)
    progress: Mapped[float] = mapped_column(Float, default=0.0)
    started_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    completed_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    error_detail: Mapped[str] = mapped_column(Text, nullable=True)


class SnapshotORM(BaseORM):
    __tablename__ = "snapshots"

    snapshot_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    timestamp: Mapped[datetime] = mapped_column(DateTime, index=True, default=datetime.utcnow)
    metrics: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    dimensions: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)


class CommandORM(BaseORM):
    __tablename__ = "commands"

    command_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    command_type: Mapped[str] = mapped_column(String(64), index=True)
    payload: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    issued_by: Mapped[str] = mapped_column(String(64), default="system")
    issued_at: Mapped[datetime] = mapped_column(DateTime, index=True, default=datetime.utcnow)
    correlation_id: Mapped[str] = mapped_column(String(64), nullable=True, index=True)


class AuditLogORM(BaseORM):
    __tablename__ = "audit_logs"

    log_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    timestamp: Mapped[datetime] = mapped_column(DateTime, index=True, default=datetime.utcnow)
    action: Mapped[str] = mapped_column(String(64), index=True)
    actor: Mapped[str] = mapped_column(String(64), index=True)
    resource: Mapped[str] = mapped_column(String(128), index=True)
    details: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    command_id: Mapped[str] = mapped_column(String(64), nullable=True, index=True)
    correlation_id: Mapped[str] = mapped_column(String(64), nullable=True, index=True)
