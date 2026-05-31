from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import Column, String, Integer, Float, JSON, DateTime, Enum as SQLEnum, Boolean, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class TenantStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    SUSPENDED = "suspended"
    TRIAL = "trial"
    EXPIRED = "expired"


class TenantTier(str, Enum):
    FREE = "free"
    BASIC = "basic"
    PROFESSIONAL = "professional"
    ENTERPRISE = "enterprise"
    CUSTOM = "custom"


class Tenant(Base):
    __tablename__ = "tenants"

    tenant_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("tnt")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    display_name: Mapped[Optional[str]] = mapped_column(String(256), nullable=True)
    status: Mapped[TenantStatus] = mapped_column(
        SQLEnum(TenantStatus), default=TenantStatus.ACTIVE, index=True
    )
    tier: Mapped[TenantTier] = mapped_column(SQLEnum(TenantTier), default=TenantTier.BASIC)
    contact_email: Mapped[str] = mapped_column(String(256), nullable=False)
    contact_phone: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    address: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    industry: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    size: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    is_deleted: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )
    deleted_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)


class TenantConfig(Base):
    __tablename__ = "tenant_configs"

    config_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("tcfg")
    )
    tenant_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    namespace: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    key: Mapped[str] = mapped_column(String(128), nullable=False)
    value: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    value_type: Mapped[str] = mapped_column(String(32), default="json")
    is_encrypted: Mapped[bool] = mapped_column(Boolean, default=False)
    is_system: Mapped[bool] = mapped_column(Boolean, default=False)
    is_overridable: Mapped[bool] = mapped_column(Boolean, default=True)
    description: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class TenantQuota(Base):
    __tablename__ = "tenant_quotas"

    quota_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("tqt")
    )
    tenant_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    resource_type: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    limit: Mapped[float] = mapped_column(Float, default=0.0)
    used: Mapped[float] = mapped_column(Float, default=0.0)
    warning_threshold: Mapped[float] = mapped_column(Float, default=80.0)
    unit: Mapped[str] = mapped_column(String(32), nullable=False)
    reset_period: Mapped[str] = mapped_column(String(32), default="monthly")
    last_reset_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    is_hard_limit: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class TenantMember(Base):
    __tablename__ = "tenant_members"

    member_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("tmb")
    )
    tenant_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    user_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    role: Mapped[str] = mapped_column(String(64), default="member")
    permissions: Mapped[List[str]] = mapped_column(JSON, default=list)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    joined_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    invited_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class TenantCreate(BaseModel):
    name: str
    display_name: Optional[str] = None
    tier: TenantTier = TenantTier.BASIC
    contact_email: str
    contact_phone: Optional[str] = None
    address: Optional[str] = None
    industry: Optional[str] = None
    size: Optional[str] = None
    meta_data: Dict[str, Any] = {}


class TenantResponse(BaseModel):
    tenant_id: str
    name: str
    display_name: Optional[str]
    status: TenantStatus
    tier: TenantTier
    contact_email: str
    contact_phone: Optional[str]
    address: Optional[str]
    industry: Optional[str]
    size: Optional[str]
    created_at: datetime
    updated_at: datetime
    meta_data: Dict[str, Any]


class TenantConfigCreate(BaseModel):
    tenant_id: str
    namespace: str
    key: str
    value: Dict[str, Any]
    value_type: str = "json"
    description: Optional[str] = None
    is_encrypted: bool = False
    is_overridable: bool = True


class TenantConfigResponse(BaseModel):
    config_id: str
    tenant_id: str
    namespace: str
    key: str
    value: Dict[str, Any]
    value_type: str
    is_encrypted: bool
    is_system: bool
    description: Optional[str]
    created_at: datetime


class TenantQuotaCreate(BaseModel):
    tenant_id: str
    resource_type: str
    limit: float
    unit: str
    warning_threshold: float = 80.0
    reset_period: str = "monthly"
    is_hard_limit: bool = True


class TenantQuotaResponse(BaseModel):
    quota_id: str
    tenant_id: str
    resource_type: str
    limit: float
    used: float
    remaining: float
    usage_percent: float
    warning_threshold: float
    unit: str
    reset_period: str
    is_hard_limit: bool
    last_reset_at: Optional[datetime]


class TenantMemberCreate(BaseModel):
    tenant_id: str
    user_id: str
    role: str = "member"
    permissions: List[str] = []
    invited_by: Optional[str] = None


class TenantMemberResponse(BaseModel):
    member_id: str
    tenant_id: str
    user_id: str
    role: str
    permissions: List[str]
    is_active: bool
    joined_at: datetime
