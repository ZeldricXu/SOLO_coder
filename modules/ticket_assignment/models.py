from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import model_validator
from sqlalchemy import Column, String, Integer, Float, JSON, DateTime, Enum as SQLEnum, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class TicketPriority(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class TicketStatus(str, Enum):
    NEW = "new"
    UNASSIGNED = "unassigned"
    ASSIGNED = "assigned"
    IN_PROGRESS = "in_progress"
    PENDING = "pending"
    RESOLVED = "resolved"
    CLOSED = "closed"
    CANCELLED = "cancelled"


class TicketChannel(str, Enum):
    EMAIL = "email"
    PHONE = "phone"
    WEB = "web"
    API = "api"
    CHAT = "chat"


class AssignmentStrategy(str, Enum):
    SKILL_MATCH = "skill_match"
    ROUND_ROBIN = "round_robin"
    LEAST_LOADED = "least_loaded"
    HYBRID = "hybrid"


class AgentStatus(str, Enum):
    AVAILABLE = "available"
    BUSY = "busy"
    OFFLINE = "offline"
    ON_LEAVE = "on_leave"


class Agent(Base):
    __tablename__ = "agents"

    agent_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("agt")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    email: Mapped[str] = mapped_column(String(256), nullable=False, unique=True)
    department: Mapped[str] = mapped_column(String(128), nullable=True)
    status: Mapped[AgentStatus] = mapped_column(
        SQLEnum(AgentStatus), default=AgentStatus.AVAILABLE, index=True
    )
    skills: Mapped[Dict[str, float]] = mapped_column(JSON, default=dict)
    current_tickets: Mapped[int] = mapped_column(Integer, default=0)
    max_concurrent_tickets: Mapped[int] = mapped_column(Integer, default=10)
    efficiency_score: Mapped[float] = mapped_column(Float, default=1.0)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class Ticket(Base):
    __tablename__ = "tickets"

    ticket_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("tkt")
    )
    title: Mapped[str] = mapped_column(String(512), nullable=False)
    description: Mapped[str] = mapped_column(String(4096), nullable=True)
    priority: Mapped[TicketPriority] = mapped_column(
        SQLEnum(TicketPriority), default=TicketPriority.MEDIUM, index=True
    )
    status: Mapped[TicketStatus] = mapped_column(
        SQLEnum(TicketStatus), default=TicketStatus.NEW, index=True
    )
    channel: Mapped[TicketChannel] = mapped_column(
        SQLEnum(TicketChannel), default=TicketChannel.WEB
    )
    required_skills: Mapped[Dict[str, float]] = mapped_column(JSON, default=dict)
    assigned_agent_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    requester_id: Mapped[str] = mapped_column(String(64), nullable=False)
    created_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    sla_deadline: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )
    custom_fields: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    assignment_strategy: Mapped[AssignmentStrategy] = mapped_column(
        SQLEnum(AssignmentStrategy), default=AssignmentStrategy.HYBRID
    )
    assignment_score: Mapped[Optional[float]] = mapped_column(Float, nullable=True)


class AssignmentResult(Base):
    __tablename__ = "assignment_results"

    assignment_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("asm")
    )
    ticket_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    agent_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    skill_match_score: Mapped[float] = mapped_column(Float, default=0.0)
    load_score: Mapped[float] = mapped_column(Float, default=0.0)
    final_score: Mapped[float] = mapped_column(Float, default=0.0)

    @property
    def match_score(self) -> float:
        return self.final_score
    strategy: Mapped[AssignmentStrategy] = mapped_column(SQLEnum(AssignmentStrategy))
    assigned_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)


class TicketCreate(BaseModel):
    title: str
    description: Optional[str] = None
    priority: TicketPriority = TicketPriority.MEDIUM
    channel: TicketChannel = TicketChannel.WEB
    required_skills: Dict[str, float] = {}
    requester_id: Optional[str] = None
    created_by: Optional[str] = None
    tenant_id: Optional[str] = None
    custom_fields: Dict[str, Any] = {}
    assignment_strategy: AssignmentStrategy = AssignmentStrategy.HYBRID

    @model_validator(mode="after")
    def set_requester_id(self) -> "TicketCreate":
        if self.requester_id is None and self.created_by is not None:
            self.requester_id = self.created_by
        return self


class TicketResponse(BaseModel):
    ticket_id: str
    title: str
    description: Optional[str]
    priority: TicketPriority
    status: TicketStatus
    channel: TicketChannel
    required_skills: Dict[str, float]
    assigned_agent_id: Optional[str]
    requester_id: str
    created_by: Optional[str]
    tenant_id: Optional[str]
    sla_deadline: Optional[datetime]
    created_at: datetime
    updated_at: datetime
    assignment_score: Optional[float]


class AgentCreate(BaseModel):
    name: str
    email: str
    department: Optional[str] = None
    skills: Dict[str, float] = {}
    current_tickets: int = 0
    max_concurrent_tickets: int = 10
    status: str = "available"
    tenant_id: Optional[str] = None


class AgentResponse(BaseModel):
    agent_id: str
    name: str
    email: str
    department: Optional[str]
    status: str
    skills: Dict[str, float]
    current_tickets: int
    max_concurrent_tickets: int
    efficiency_score: float
    tenant_id: Optional[str]
    created_at: datetime


class AssignmentResultResponse(BaseModel):
    assignment_id: str
    ticket_id: str
    agent_id: str
    skill_match_score: float
    load_score: float
    final_score: float
    strategy: AssignmentStrategy
    assigned_at: datetime
    agent_name: Optional[str] = None
    match_score: Optional[float] = None

    @model_validator(mode="after")
    def set_match_score(self) -> "AssignmentResultResponse":
        if self.match_score is None:
            self.match_score = self.final_score
        return self
