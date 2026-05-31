from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime
from enum import Enum


class PromptStatus(str, Enum):
    DRAFT = "draft"
    REVIEW = "review"
    APPROVED = "approved"
    DEPRECATED = "deprecated"
    ARCHIVED = "archived"


class ExperimentStatus(str, Enum):
    CREATED = "created"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    CANCELLED = "cancelled"


class PromptCreateRequest(BaseModel):
    name: str = Field(..., max_length=200)
    content: str
    description: Optional[str] = None
    tags: List[str] = Field(default_factory=list)
    variables: Dict[str, str] = Field(default_factory=dict)
    metadata: Dict[str, Any] = Field(default_factory=dict)


class PromptVersion(BaseModel):
    version_id: str
    name: str
    content: str
    version: int
    status: PromptStatus = PromptStatus.DRAFT
    description: Optional[str] = None
    tags: List[str] = Field(default_factory=list)
    variables: Dict[str, str] = Field(default_factory=dict)
    parent_version_id: Optional[str] = None
    created_by: Optional[str] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)
    metadata: Dict[str, Any] = Field(default_factory=dict)


class VariantConfig(BaseModel):
    variant_id: str
    prompt_version_id: str
    traffic_weight: float = Field(default=0.5, ge=0.0, le=1.0)
    variables: Dict[str, Any] = Field(default_factory=dict)


class ExperimentConfig(BaseModel):
    name: str
    description: Optional[str] = None
    variants: List[VariantConfig]
    traffic_allocation: float = Field(default=1.0, ge=0.0, le=1.0)
    primary_metric: str = Field(default="completion_quality")
    secondary_metrics: List[str] = Field(default_factory=list)
    minimum_samples: int = Field(default=100, ge=10)
    confidence_level: float = Field(default=0.95, ge=0.8, le=0.99)
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None


class ABExperiment(BaseModel):
    experiment_id: str
    name: str
    status: ExperimentStatus = ExperimentStatus.CREATED
    config: ExperimentConfig
    created_at: datetime = Field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    ended_at: Optional[datetime] = None
    created_by: Optional[str] = None


class VariantMetrics(BaseModel):
    variant_id: str
    prompt_version_id: str
    total_samples: int = 0
    metrics: Dict[str, float] = Field(default_factory=dict)
    metric_stats: Dict[str, Dict[str, float]] = Field(default_factory=dict)
    conversion_rate: Optional[float] = None


class ExperimentResult(BaseModel):
    experiment_id: str
    status: ExperimentStatus
    variants: List[VariantMetrics]
    winner: Optional[str] = None
    is_statistically_significant: bool = False
    p_value: Optional[float] = None
    effect_size: Optional[float] = None
    generated_at: datetime = Field(default_factory=datetime.utcnow)


class ComparisonReport(BaseModel):
    report_id: str
    base_version_id: str
    comparison_version_ids: List[str]
    metrics: Dict[str, Dict[str, Any]]
    recommendations: List[str]
    generated_at: datetime = Field(default_factory=datetime.utcnow)
