from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import Column, String, Integer, Float, JSON, DateTime, Enum as SQLEnum, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class ResourceType(str, Enum):
    STORAGE = "storage"
    COMPUTE = "compute"
    NETWORK = "network"
    API_CALLS = "api_calls"
    TICKETS = "tickets"
    WORKFLOWS = "workflows"
    USERS = "users"
    DATABASE = "database"


class BillingCycle(str, Enum):
    HOURLY = "hourly"
    DAILY = "daily"
    WEEKLY = "weekly"
    MONTHLY = "monthly"
    QUARTERLY = "quarterly"
    YEARLY = "yearly"


class BillingStatus(str, Enum):
    PENDING = "pending"
    GENERATED = "generated"
    ISSUED = "issued"
    PAID = "paid"
    OVERDUE = "overdue"
    CANCELLED = "cancelled"


class CollectionStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"


class ResourceUsageRecord(Base):
    __tablename__ = "resource_usage_records"

    record_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("usr")
    )
    tenant_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    resource_type: Mapped[ResourceType] = mapped_column(
        SQLEnum(ResourceType), nullable=False, index=True
    )
    quantity: Mapped[float] = mapped_column(Float, default=0.0)
    unit: Mapped[str] = mapped_column(String(32), nullable=False)
    collected_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, index=True
    )
    period_start: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    period_end: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    source: Mapped[str] = mapped_column(String(128), nullable=False)
    status: Mapped[CollectionStatus] = mapped_column(
        SQLEnum(CollectionStatus), default=CollectionStatus.COMPLETED
    )
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class PricingPlan(Base):
    __tablename__ = "pricing_plans"

    plan_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("pln")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    resource_type: Mapped[ResourceType] = mapped_column(
        SQLEnum(ResourceType), nullable=False, index=True
    )
    unit_price: Mapped[float] = mapped_column(Float, default=0.0)
    currency: Mapped[str] = mapped_column(String(16), default="CNY")
    unit: Mapped[str] = mapped_column(String(32), nullable=False)
    billing_cycle: Mapped[BillingCycle] = mapped_column(
        SQLEnum(BillingCycle), default=BillingCycle.MONTHLY
    )
    tiered_pricing: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class BillingItem(Base):
    __tablename__ = "billing_items"

    item_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("itm")
    )
    bill_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    resource_type: Mapped[ResourceType] = mapped_column(SQLEnum(ResourceType), nullable=False)
    description: Mapped[str] = mapped_column(String(512), nullable=False)
    quantity: Mapped[float] = mapped_column(Float, default=0.0)
    unit_price: Mapped[float] = mapped_column(Float, default=0.0)
    subtotal: Mapped[float] = mapped_column(Float, default=0.0)
    unit: Mapped[str] = mapped_column(String(32), nullable=False)
    pricing_plan_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    usage_record_ids: Mapped[List[str]] = mapped_column(JSON, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class Bill(Base):
    __tablename__ = "bills"

    bill_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("bil")
    )
    tenant_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    invoice_number: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)
    billing_cycle: Mapped[BillingCycle] = mapped_column(SQLEnum(BillingCycle), nullable=False)
    period_start: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    period_end: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    total_amount: Mapped[float] = mapped_column(Float, default=0.0)
    currency: Mapped[str] = mapped_column(String(16), default="CNY")
    status: Mapped[BillingStatus] = mapped_column(
        SQLEnum(BillingStatus), default=BillingStatus.PENDING, index=True
    )
    discount_amount: Mapped[float] = mapped_column(Float, default=0.0)
    tax_amount: Mapped[float] = mapped_column(Float, default=0.0)
    paid_amount: Mapped[float] = mapped_column(Float, default=0.0)
    issued_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    paid_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    due_date: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)


class UsageRecordCreate(BaseModel):
    tenant_id: str
    resource_type: ResourceType
    quantity: float
    unit: str
    period_start: datetime
    period_end: datetime
    source: str
    meta_data: Dict[str, Any] = {}


class UsageRecordResponse(BaseModel):
    record_id: str
    tenant_id: str
    resource_type: ResourceType
    quantity: float
    unit: str
    collected_at: datetime
    period_start: datetime
    period_end: datetime
    source: str
    status: CollectionStatus


class PricingPlanCreate(BaseModel):
    name: str
    description: Optional[str] = None
    resource_type: ResourceType
    unit_price: float
    unit: str
    currency: str = "CNY"
    billing_cycle: BillingCycle = BillingCycle.MONTHLY
    tiered_pricing: Dict[str, Any] = {}
    is_active: bool = True


class PricingPlanResponse(BaseModel):
    plan_id: str
    name: str
    description: Optional[str]
    resource_type: ResourceType
    unit_price: float
    currency: str
    unit: str
    billing_cycle: BillingCycle
    tiered_pricing: Dict[str, Any]
    is_active: bool
    created_at: datetime


class BillingItemResponse(BaseModel):
    item_id: str
    bill_id: str
    resource_type: ResourceType
    description: str
    quantity: float
    unit_price: float
    subtotal: float
    unit: str


class BillCreate(BaseModel):
    tenant_id: str
    billing_cycle: BillingCycle
    period_start: datetime
    period_end: datetime
    due_date: Optional[datetime] = None


class BillResponse(BaseModel):
    bill_id: str
    tenant_id: str
    invoice_number: str
    billing_cycle: BillingCycle
    period_start: datetime
    period_end: datetime
    total_amount: float
    currency: str
    status: BillingStatus
    discount_amount: float
    tax_amount: float
    paid_amount: float
    issued_at: Optional[datetime]
    paid_at: Optional[datetime]
    due_date: Optional[datetime]
    created_at: datetime


class BillDetailResponse(BillResponse):
    items: List[BillingItemResponse] = []
