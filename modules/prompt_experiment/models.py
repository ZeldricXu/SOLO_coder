from datetime import datetime
from enum import Enum
from typing import Any, Dict, Optional

from sqlalchemy import JSON, Boolean, DateTime, Float, Integer, String, Text
from sqlalchemy import Enum as SQLEnum
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id
from models.base import BaseModel, TimestampMixin


class ExperimentStatus(str, Enum):
    DRAFT = "draft"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    ARCHIVED = "archived"


class PromptType(str, Enum):
    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"


class PromptVersion(Base, TimestampMixin):
    __tablename__ = "prompt_versions"

    version_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("prv")
    )
    prompt_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=1)
    type: Mapped[PromptType] = mapped_column(SQLEnum(PromptType), default=PromptType.USER)
    variables: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_by: Mapped[str] = mapped_column(String(64), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    api_key: Mapped[Optional[str]] = mapped_column(String(256), nullable=True)


class AbExperiment(Base, TimestampMixin):
    __tablename__ = "ab_experiments"

    experiment_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("exp")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    control_prompt_id: Mapped[str] = mapped_column(String(64), nullable=False)
    treatment_prompt_id: Mapped[str] = mapped_column(String(64), nullable=False)
    control_prompt_version: Mapped[int] = mapped_column(Integer, default=1)
    treatment_prompt_version: Mapped[int] = mapped_column(Integer, default=1)
    traffic_split: Mapped[float] = mapped_column(Float, default=0.5)
    status: Mapped[ExperimentStatus] = mapped_column(
        SQLEnum(ExperimentStatus), default=ExperimentStatus.DRAFT, index=True
    )
    total_samples: Mapped[int] = mapped_column(Integer, default=0)
    control_samples: Mapped[int] = mapped_column(Integer, default=0)
    treatment_samples: Mapped[int] = mapped_column(Integer, default=0)
    control_success: Mapped[int] = mapped_column(Integer, default=0)
    treatment_success: Mapped[int] = mapped_column(Integer, default=0)
    created_by: Mapped[str] = mapped_column(String(64), nullable=False)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    ended_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    meta_data: Mapped[Dict[str, Any]] = mapped_column("metadata", JSON, default=dict)
    api_secret: Mapped[Optional[str]] = mapped_column(String(256), nullable=True)


class ExperimentResult(Base, TimestampMixin):
    __tablename__ = "experiment_results"

    result_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("res")
    )
    experiment_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    group: Mapped[str] = mapped_column(String(32), nullable=False)
    prompt_id: Mapped[str] = mapped_column(String(64), nullable=False)
    input: Mapped[str] = mapped_column(Text, nullable=False)
    output: Mapped[str] = mapped_column(Text, nullable=False)
    is_success: Mapped[bool] = mapped_column(Boolean, default=False)
    latency_ms: Mapped[int] = mapped_column(Integer, default=0)
    tokens_used: Mapped[int] = mapped_column(Integer, default=0)
    user_id: Mapped[str] = mapped_column(String(64), nullable=False)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    meta_data: Mapped[Dict[str, Any]] = mapped_column("metadata", JSON, default=dict)
    auth_token: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)


class PromptVersionCreate(BaseModel):
    prompt_id: Optional[str] = None
    content: str
    type: PromptType = PromptType.USER
    variables: Dict[str, Any] = {}
    created_by: str
    description: Optional[str] = None
    tenant_id: Optional[str] = None
    api_key: Optional[str] = None


class PromptVersionResponse(BaseModel):
    version_id: str
    prompt_id: str
    content: str
    version: int
    type: PromptType
    variables: Dict[str, Any]
    created_by: str
    description: Optional[str]
    is_active: bool
    tenant_id: Optional[str]
    created_at: datetime
    updated_at: datetime
    api_key: Optional[str] = None


class AbExperimentCreate(BaseModel):
    name: str
    description: Optional[str] = None
    control_prompt_id: str
    treatment_prompt_id: str
    control_prompt_version: int = 1
    treatment_prompt_version: int = 1
    traffic_split: float = 0.5
    created_by: str
    tenant_id: Optional[str] = None
    api_secret: Optional[str] = None


class AbExperimentResponse(BaseModel):
    experiment_id: str
    name: str
    description: Optional[str]
    control_prompt_id: str
    treatment_prompt_id: str
    control_prompt_version: int
    treatment_prompt_version: int
    traffic_split: float
    status: ExperimentStatus
    total_samples: int
    control_samples: int
    treatment_samples: int
    control_success: int
    treatment_success: int
    control_conversion_rate: float = 0.0
    treatment_conversion_rate: float = 0.0
    improvement_rate: float = 0.0
    created_by: str
    tenant_id: Optional[str]
    created_at: datetime
    updated_at: datetime
    started_at: Optional[datetime]
    ended_at: Optional[datetime]
    api_secret: Optional[str] = None
    mobile_layout: Dict[str, Any] = {}


class ExperimentResultCreate(BaseModel):
    experiment_id: str
    group: str
    prompt_id: str
    input: str
    output: str
    is_success: bool = False
    latency_ms: int = 0
    tokens_used: int = 0
    user_id: str
    tenant_id: Optional[str] = None
    metadata: Dict[str, Any] = {}
    auth_token: Optional[str] = None


class ExperimentResultResponse(BaseModel):
    result_id: str
    experiment_id: str
    group: str
    prompt_id: str
    input: str
    output: str
    is_success: bool
    latency_ms: int
    tokens_used: int
    user_id: str
    tenant_id: Optional[str]
    created_at: datetime
    auth_token: Optional[str] = None


class ExperimentStatsResponse(BaseModel):
    experiment_id: str
    name: str
    status: ExperimentStatus
    total_samples: int
    control_samples: int
    treatment_samples: int
    control_conversion_rate: float
    treatment_conversion_rate: float
    improvement_percentage: float
    confidence_level: float
    is_statistically_significant: bool
    mobile_compatible: bool = False
