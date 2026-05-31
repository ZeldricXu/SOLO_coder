from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import Column, String, Integer, Float, JSON, DateTime, Enum as SQLEnum, Boolean, Boolean, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class SkillCategory(str, Enum):
    TECHNICAL = "technical"
    SOFT = "soft"
    DOMAIN = "domain"
    TOOL = "tool"
    CERTIFICATION = "certification"


class ProficiencyLevel(str, Enum):
    BEGINNER = "beginner"
    INTERMEDIATE = "intermediate"
    ADVANCED = "advanced"
    EXPERT = "expert"
    MASTER = "master"


class AssessmentStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    EXPIRED = "expired"


class LearningStatus(str, Enum):
    NOT_STARTED = "not_started"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    PAUSED = "paused"


class SkillNode(Base):
    __tablename__ = "skill_nodes"

    skill_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("skl")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    category: Mapped[SkillCategory] = mapped_column(SQLEnum(SkillCategory), nullable=False)
    parent_skill_id: Mapped[Optional[str]] = mapped_column(
        String(64), ForeignKey("skill_nodes.skill_id"), nullable=True
    )
    prerequisites: Mapped[List[str]] = mapped_column(JSON, default=list)
    weight: Mapped[float] = mapped_column(Float, default=1.0)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    parent = relationship("SkillNode", remote_side=[skill_id], backref="children")


class SkillAssessment(Base):
    __tablename__ = "skill_assessments"

    assessment_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("asm")
    )
    user_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    skill_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    proficiency_level: Mapped[ProficiencyLevel] = mapped_column(
        SQLEnum(ProficiencyLevel), nullable=False
    )
    score: Mapped[float] = mapped_column(Float, default=0.0)
    max_score: Mapped[float] = mapped_column(Float, default=100.0)
    status: Mapped[AssessmentStatus] = mapped_column(
        SQLEnum(AssessmentStatus), default=AssessmentStatus.PENDING
    )
    assessed_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    assessed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    valid_until: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    evidence: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    notes: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class UserSkillProfile(Base):
    __tablename__ = "user_skill_profiles"

    profile_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("usp")
    )
    user_id: Mapped[str] = mapped_column(String(64), nullable=False, unique=True, index=True)
    skills: Mapped[Dict[str, float]] = mapped_column(JSON, default=dict)
    overall_score: Mapped[float] = mapped_column(Float, default=0.0)
    skill_gaps: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    career_path: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    last_updated: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class LearningPath(Base):
    __tablename__ = "learning_paths"

    path_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("lpt")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    target_skill_id: Mapped[str] = mapped_column(String(64), nullable=False)
    target_proficiency: Mapped[ProficiencyLevel] = mapped_column(SQLEnum(ProficiencyLevel))
    estimated_duration_hours: Mapped[int] = mapped_column(Integer, default=0)
    steps: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    prerequisites: Mapped[List[str]] = mapped_column(JSON, default=list)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class UserLearningProgress(Base):
    __tablename__ = "user_learning_progress"

    progress_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("ulp")
    )
    user_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    path_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    status: Mapped[LearningStatus] = mapped_column(
        SQLEnum(LearningStatus), default=LearningStatus.NOT_STARTED
    )
    current_step: Mapped[int] = mapped_column(Integer, default=0)
    total_steps: Mapped[int] = mapped_column(Integer, default=0)
    progress_percent: Mapped[float] = mapped_column(Float, default=0.0)
    completed_steps: Mapped[List[int]] = mapped_column(JSON, default=list)
    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    time_spent_hours: Mapped[float] = mapped_column(Float, default=0.0)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    last_updated: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class SkillNodeCreate(BaseModel):
    name: str
    description: Optional[str] = None
    category: SkillCategory
    parent_skill_id: Optional[str] = None
    prerequisites: List[str] = []
    weight: float = 1.0
    is_active: bool = True
    tenant_id: Optional[str] = None
    meta_data: Dict[str, Any] = {}


class SkillNodeResponse(BaseModel):
    skill_id: str
    name: str
    description: Optional[str]
    category: SkillCategory
    parent_skill_id: Optional[str]
    prerequisites: List[str]
    weight: float
    is_active: bool
    tenant_id: Optional[str]
    created_at: datetime
    children: List["SkillNodeResponse"] = []


class SkillAssessmentCreate(BaseModel):
    user_id: str
    skill_id: str
    proficiency_level: ProficiencyLevel
    score: float
    max_score: float = 100.0
    assessed_by: Optional[str] = None
    valid_until: Optional[datetime] = None
    evidence: List[Dict[str, Any]] = []
    tenant_id: Optional[str] = None
    notes: Optional[str] = None


class SkillAssessmentResponse(BaseModel):
    assessment_id: str
    user_id: str
    skill_id: str
    proficiency_level: ProficiencyLevel
    score: float
    max_score: float
    status: AssessmentStatus
    assessed_by: Optional[str]
    assessed_at: Optional[datetime]
    valid_until: Optional[datetime]
    tenant_id: Optional[str]
    notes: Optional[str]
    created_at: datetime


class UserSkillProfileResponse(BaseModel):
    profile_id: str
    user_id: str
    skills: Dict[str, float]
    overall_score: float
    skill_gaps: List[Dict[str, Any]]
    career_path: List[Dict[str, Any]]
    tenant_id: Optional[str]
    last_updated: datetime


class LearningPathCreate(BaseModel):
    name: str
    description: Optional[str] = None
    target_skill_id: str
    target_proficiency: ProficiencyLevel
    estimated_duration_hours: int = 0
    steps: List[Dict[str, Any]] = []
    prerequisites: List[str] = []
    is_active: bool = True
    tenant_id: Optional[str] = None


class LearningPathResponse(BaseModel):
    path_id: str
    name: str
    description: Optional[str]
    target_skill_id: str
    target_proficiency: ProficiencyLevel
    estimated_duration_hours: int
    steps: List[Dict[str, Any]]
    prerequisites: List[str]
    is_active: bool
    tenant_id: Optional[str]
    created_at: datetime


class LearningProgressCreate(BaseModel):
    user_id: str
    path_id: str
    tenant_id: Optional[str] = None


class LearningProgressUpdate(BaseModel):
    status: Optional[LearningStatus] = None
    completed_step: Optional[int] = None
    time_spent_hours: Optional[float] = None


class LearningProgressResponse(BaseModel):
    progress_id: str
    user_id: str
    path_id: str
    status: LearningStatus
    current_step: int
    total_steps: int
    progress_percent: float
    started_at: Optional[datetime]
    completed_at: Optional[datetime]
    time_spent_hours: float
    tenant_id: Optional[str]
