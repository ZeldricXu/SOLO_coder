from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional

from sqlalchemy import Column, String, JSON, Enum as SQLEnum
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from .base import BaseModel, TimestampMixin, IdMixin


class EntityStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    PENDING = "pending"
    DELETED = "deleted"
    ARCHIVED = "archived"
    PROVISIONING = "provisioning"
    RUNNING = "running"
    STOPPED = "stopped"
    RESTARTING = "restarting"
    FAILED = "failed"
    COMPLETED = "completed"


class EntityType(str, Enum):
    RESOURCE = "resource"
    TASK = "task"
    TICKET = "ticket"
    USER = "user"
    TENANT = "tenant"
    WORKFLOW = "workflow"
    DOCUMENT = "document"
    APPROVAL = "approval"


class Entity(Base, TimestampMixin, IdMixin):
    __tablename__ = "entities"

    type: Mapped[EntityType] = mapped_column(SQLEnum(EntityType), nullable=False, index=True)
    status: Mapped[EntityStatus] = mapped_column(
        SQLEnum(EntityStatus), default=EntityStatus.ACTIVE, index=True
    )
    attributes: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True, nullable=True)
    labels: Mapped[Dict[str, str]] = mapped_column(JSON, default=dict)
    config: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)


class EntityCreate(BaseModel):
    type: EntityType
    attributes: Dict[str, Any] = {}
    tenant_id: Optional[str] = None
    labels: Dict[str, str] = {}
    config: Dict[str, Any] = {}
    status: EntityStatus = EntityStatus.ACTIVE


class EntityResponse(BaseModel):
    id: str
    type: EntityType
    status: EntityStatus
    attributes: Dict[str, Any]
    tenant_id: Optional[str]
    labels: Dict[str, str]
    config: Dict[str, Any]
    created_at: datetime
    updated_at: datetime
