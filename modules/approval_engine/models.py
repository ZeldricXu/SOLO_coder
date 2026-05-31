from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import Column, String, Integer, Float, JSON, DateTime, Enum as SQLEnum, Boolean, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class ApprovalType(str, Enum):
    ALL = "all"
    ANY = "any"
    SEQUENTIAL = "sequential"
    PERCENTAGE = "percentage"


class ApprovalStatus(str, Enum):
    PENDING = "pending"
    APPROVED = "approved"
    REJECTED = "rejected"
    ESCALATED = "escalated"
    TIMEOUT = "timeout"
    CANCELLED = "cancelled"


class ApprovalAction(str, Enum):
    APPROVE = "approve"
    REJECT = "reject"
    DELEGATE = "delegate"
    ESCALATE = "escalate"
    COMMENT = "comment"


class RuleConditionOperator(str, Enum):
    EQUALS = "equals"
    NOT_EQUALS = "not_equals"
    GREATER_THAN = "greater_than"
    LESS_THAN = "less_than"
    CONTAINS = "contains"
    NOT_CONTAINS = "not_contains"
    IN = "in"
    NOT_IN = "not_in"
    STARTS_WITH = "starts_with"
    ENDS_WITH = "ends_with"
    REGEX = "regex"


class RuleCombinationOperator(str, Enum):
    AND = "and"
    OR = "or"


class DynamicApproverType(str, Enum):
    USER = "user"
    ROLE = "role"
    DEPARTMENT = "department"
    MANAGER = "manager"
    FORMULA = "formula"
    SCRIPT = "script"


class ApprovalRule(Base):
    __tablename__ = "approval_rules"

    rule_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("aprl")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    rule_type: Mapped[str] = mapped_column(String(64), index=True)
    conditions: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    condition_operator: Mapped[RuleCombinationOperator] = mapped_column(
        SQLEnum(RuleCombinationOperator), default=RuleCombinationOperator.AND
    )
    approval_type: Mapped[ApprovalType] = mapped_column(
        SQLEnum(ApprovalType), default=ApprovalType.ALL
    )
    approval_percentage: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    approvers: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    dynamic_approvers: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    allow_delegate: Mapped[bool] = mapped_column(Boolean, default=True)
    allow_escalate: Mapped[bool] = mapped_column(Boolean, default=True)
    timeout_seconds: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    escalation_rules: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    priority: Mapped[int] = mapped_column(Integer, default=0)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    created_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class ApprovalProcess(Base):
    __tablename__ = "approval_processes"

    process_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("appr")
    )
    rule_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True, index=True)
    entity_type: Mapped[str] = mapped_column(String(64), index=True)
    entity_id: Mapped[str] = mapped_column(String(64), index=True)
    title: Mapped[str] = mapped_column(String(512), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    approval_type: Mapped[ApprovalType] = mapped_column(
        SQLEnum(ApprovalType), default=ApprovalType.ALL
    )
    approval_percentage: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    status: Mapped[ApprovalStatus] = mapped_column(
        SQLEnum(ApprovalStatus), default=ApprovalStatus.PENDING, index=True
    )
    current_step: Mapped[int] = mapped_column(Integer, default=0)
    total_steps: Mapped[int] = mapped_column(Integer, default=1)
    context: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    form_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    approvers: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    approval_steps: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    timeout_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    reject_reason: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    started_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class ApprovalRecord(Base):
    __tablename__ = "approval_records"

    record_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("aprc")
    )
    process_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    step_index: Mapped[int] = mapped_column(Integer, default=0)
    approver_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    approver_name: Mapped[Optional[str]] = mapped_column(String(256), nullable=True)
    action: Mapped[ApprovalAction] = mapped_column(SQLEnum(ApprovalAction))
    status: Mapped[ApprovalStatus] = mapped_column(
        SQLEnum(ApprovalStatus), default=ApprovalStatus.PENDING
    )
    comment: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    delegated_to: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    signature: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    ip_address: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    user_agent: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)


class ApprovalRuleCreate(BaseModel):
    name: str
    description: Optional[str] = None
    rule_type: str
    conditions: List[Dict[str, Any]] = []
    condition_operator: RuleCombinationOperator = RuleCombinationOperator.AND
    approval_type: ApprovalType = ApprovalType.ALL
    approval_percentage: Optional[float] = None
    approvers: List[Dict[str, Any]] = []
    dynamic_approvers: List[Dict[str, Any]] = []
    allow_delegate: bool = True
    allow_escalate: bool = True
    timeout_seconds: Optional[int] = None
    escalation_rules: List[Dict[str, Any]] = []
    priority: int = 0
    tenant_id: Optional[str] = None
    created_by: Optional[str] = None


class ApprovalRuleResponse(BaseModel):
    rule_id: str
    name: str
    description: Optional[str]
    rule_type: str
    conditions: List[Dict[str, Any]]
    condition_operator: RuleCombinationOperator
    approval_type: ApprovalType
    approval_percentage: Optional[float]
    approvers: List[Dict[str, Any]]
    dynamic_approvers: List[Dict[str, Any]]
    allow_delegate: bool
    allow_escalate: bool
    timeout_seconds: Optional[int]
    escalation_rules: List[Dict[str, Any]]
    priority: int
    is_active: bool
    tenant_id: Optional[str]
    created_by: Optional[str]
    created_at: datetime
    updated_at: datetime


class ApprovalProcessCreate(BaseModel):
    rule_id: Optional[str] = None
    entity_type: str
    entity_id: str
    title: str
    description: Optional[str] = None
    approval_type: Optional[ApprovalType] = None
    approval_percentage: Optional[float] = None
    context: Dict[str, Any] = {}
    form_data: Dict[str, Any] = {}
    approvers: List[Dict[str, Any]] = []
    timeout_seconds: Optional[int] = None
    tenant_id: Optional[str] = None
    started_by: Optional[str] = None


class ApprovalProcessResponse(BaseModel):
    process_id: str
    rule_id: Optional[str]
    entity_type: str
    entity_id: str
    title: str
    description: Optional[str]
    approval_type: ApprovalType
    approval_percentage: Optional[float]
    status: ApprovalStatus
    current_step: int
    total_steps: int
    context: Dict[str, Any]
    form_data: Dict[str, Any]
    approvers: List[Dict[str, Any]]
    approval_steps: List[Dict[str, Any]]
    timeout_at: Optional[datetime]
    completed_at: Optional[datetime]
    reject_reason: Optional[str]
    tenant_id: Optional[str]
    started_by: Optional[str]
    created_at: datetime
    updated_at: datetime


class ApprovalActionRequest(BaseModel):
    process_id: str
    action: ApprovalAction
    comment: Optional[str] = None
    delegated_to: Optional[str] = None
    signature: Optional[str] = None
    step_index: Optional[int] = None
    approver_id: str
    approver_name: Optional[str] = None
    ip_address: Optional[str] = None
    user_agent: Optional[str] = None
    tenant_id: Optional[str] = None


class ApprovalRecordResponse(BaseModel):
    record_id: str
    process_id: str
    step_index: int
    approver_id: str
    approver_name: Optional[str]
    action: ApprovalAction
    status: ApprovalStatus
    comment: Optional[str]
    delegated_to: Optional[str]
    created_at: datetime


class ConditionEvaluationResult(BaseModel):
    condition_id: str
    field: str
    operator: str
    expected_value: Any
    actual_value: Any
    result: bool
