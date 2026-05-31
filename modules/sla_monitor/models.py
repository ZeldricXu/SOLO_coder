from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import Column, String, Integer, Float, JSON, DateTime, Enum as SQLEnum, Boolean, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class SLASeverity(str, Enum):
    WARNING = "warning"
    CRITICAL = "critical"
    BREACHED = "breached"
    OK = "ok"


class SLATargetType(str, Enum):
    RESPONSE_TIME = "response_time"
    RESOLUTION_TIME = "resolution_time"
    ACKNOWLEDGMENT_TIME = "acknowledgment_time"
    FIRST_RESPONSE_TIME = "first_response_time"


class EscalationLevel(str, Enum):
    LEVEL_1 = "level_1"
    LEVEL_2 = "level_2"
    LEVEL_3 = "level_3"
    LEVEL_4 = "level_4"


class NotificationChannel(str, Enum):
    EMAIL = "email"
    SMS = "sms"
    IN_APP = "in_app"
    WEBHOOK = "webhook"
    SLACK = "slack"


class SLAPolicy(Base):
    __tablename__ = "sla_policies"

    policy_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("sla")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    target_type: Mapped[SLATargetType] = mapped_column(SQLEnum(SLATargetType), nullable=False)
    target_duration_seconds: Mapped[int] = mapped_column(Integer, nullable=False)
    warning_threshold_percent: Mapped[float] = mapped_column(Float, default=75.0)
    critical_threshold_percent: Mapped[float] = mapped_column(Float, default=90.0)
    priority: Mapped[str] = mapped_column(String(32), default="medium")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    escalation_rules: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    notification_channels: Mapped[List[str]] = mapped_column(JSON, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class SLATracker(Base):
    __tablename__ = "sla_trackers"

    tracker_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("trk")
    )
    entity_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    entity_type: Mapped[str] = mapped_column(String(64), nullable=False)
    policy_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    start_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    deadline: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    paused_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    resumed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    paused_duration_seconds: Mapped[int] = mapped_column(Integer, default=0)
    current_status: Mapped[SLASeverity] = mapped_column(
        SQLEnum(SLASeverity), default=SLASeverity.OK, index=True
    )
    current_escalation_level: Mapped[EscalationLevel] = mapped_column(
        SQLEnum(EscalationLevel), default=EscalationLevel.LEVEL_1
    )
    is_paused: Mapped[bool] = mapped_column(Boolean, default=False)
    is_completed: Mapped[bool] = mapped_column(Boolean, default=False)
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    breach_count: Mapped[int] = mapped_column(Integer, default=0)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class SLAEvent(Base):
    __tablename__ = "sla_events"

    event_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("evt")
    )
    tracker_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    severity: Mapped[SLASeverity] = mapped_column(SQLEnum(SLASeverity), nullable=False)
    timestamp: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    description: Mapped[str] = mapped_column(String(1024), nullable=False)
    elapsed_seconds: Mapped[int] = mapped_column(Integer, default=0)
    remaining_seconds: Mapped[int] = mapped_column(Integer, default=0)
    escalation_level: Mapped[Optional[EscalationLevel]] = mapped_column(
        SQLEnum(EscalationLevel), nullable=True
    )
    notified: Mapped[bool] = mapped_column(Boolean, default=False)
    notification_sent_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    notification_recipients: Mapped[List[str]] = mapped_column(JSON, default=list)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)


class SLAPolicyCreate(BaseModel):
    name: str
    description: Optional[str] = None
    target_type: SLATargetType
    target_duration_seconds: int
    warning_threshold_percent: float = 75.0
    critical_threshold_percent: float = 90.0
    priority: str = "medium"
    is_active: bool = True
    tenant_id: Optional[str] = None
    escalation_rules: List[Dict[str, Any]] = []
    notification_channels: List[str] = []


class SLAPolicyResponse(BaseModel):
    policy_id: str
    name: str
    description: Optional[str]
    target_type: SLATargetType
    target_duration_seconds: int
    warning_threshold_percent: float
    critical_threshold_percent: float
    priority: str
    is_active: bool
    tenant_id: Optional[str]
    escalation_rules: List[Dict[str, Any]]
    notification_channels: List[str]
    created_at: datetime


class SLATrackerCreate(BaseModel):
    entity_id: str
    entity_type: str
    policy_id: str
    start_time: Optional[datetime] = None
    tenant_id: Optional[str] = None
    meta_data: Dict[str, Any] = {}


class SLATrackerResponse(BaseModel):
    tracker_id: str
    entity_id: str
    entity_type: str
    policy_id: str
    start_time: datetime
    deadline: datetime
    current_status: SLASeverity
    current_escalation_level: EscalationLevel
    is_paused: bool
    is_completed: bool
    completed_at: Optional[datetime]
    breach_count: int
    tenant_id: Optional[str]
    elapsed_seconds: int
    remaining_seconds: int
    progress_percent: float
    meta_data: Dict[str, Any]


class SLAEventResponse(BaseModel):
    event_id: str
    tracker_id: str
    event_type: str
    severity: SLASeverity
    timestamp: datetime
    description: str
    elapsed_seconds: int
    remaining_seconds: int
    escalation_level: Optional[EscalationLevel]
    notified: bool
