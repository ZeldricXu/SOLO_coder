from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional

from sqlalchemy import Column, String, Integer, JSON, Boolean, DateTime, Enum as SQLEnum
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from .base import BaseModel


class ConfigStatus(str, Enum):
    DRAFT = "draft"
    ACTIVE = "active"
    DISABLED = "disabled"
    DEPRECATED = "deprecated"


class ConfigDefinition(Base):
    __tablename__ = "config_definitions"

    config_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("cfg")
    )
    namespace: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    parameters: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    status: Mapped[ConfigStatus] = mapped_column(
        SQLEnum(ConfigStatus), default=ConfigStatus.ACTIVE
    )
    applied_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )
    description: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True, nullable=True)


class ConfigCreate(BaseModel):
    namespace: str
    parameters: Dict[str, Any]
    version: int = 1
    enabled: bool = True
    description: Optional[str] = None
    tenant_id: Optional[str] = None
    status: ConfigStatus = ConfigStatus.ACTIVE


class ConfigResponse(BaseModel):
    config_id: str
    namespace: str
    version: int
    parameters: Dict[str, Any]
    enabled: bool
    status: ConfigStatus
    applied_at: Optional[datetime]
    created_at: datetime
    updated_at: datetime
    description: Optional[str]
    tenant_id: Optional[str]
