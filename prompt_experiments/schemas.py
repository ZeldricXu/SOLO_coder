from datetime import datetime
from typing import List, Optional, Dict, Any
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class PromptStatus(str, Enum):
    DRAFT = "draft"
    TESTING = "testing"
    PRODUCTION = "production"
    ARCHIVED = "archived"
    DEPRECATED = "deprecated"


class ExperimentStatus(str, Enum):
    CREATED = "created"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    ABORTED = "aborted"


class TrafficAllocationMode(str, Enum):
    RANDOM = "random"
    USER_ID = "user_id"
    SESSION_ID = "session_id"
    ATTRIBUTE_BASED = "attribute_based"


class MetricType(str, Enum):
    ACCURACY = "accuracy"
    COMPLETION_RATE = "completion_rate"
    LATENCY = "latency"
    COST = "cost"
    USER_SATISFACTION = "user_satisfaction"
    HALLUCINATION_RATE = "hallucination_rate"
    RELEVANCE = "relevance"
    COHERENCE = "coherence"


class PromptVersion(BaseModel):
    version_id: str
    prompt_id: str
    version: int
    content: str
    description: Optional[str] = None
    variables: Optional[List[str]] = None
    system_prompt: Optional[str] = None
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    max_tokens: int = Field(default=2048, ge=1)
    top_p: float = Field(default=1.0, ge=0.0, le=1.0)
    frequency_penalty: float = Field(default=0.0, ge=-2.0, le=2.0)
    presence_penalty: float = Field(default=0.0, ge=-2.0, le=2.0)
    stop_sequences: Optional[List[str]] = None
    status: PromptStatus = Field(default=PromptStatus.DRAFT)
    tags: Optional[Dict[str, str]] = None
    created_by: Optional[str] = None
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class PromptCreateRequest(BaseModel):
    prompt_id: str = Field(..., description="Prompt唯一标识")
    content: str = Field(..., min_length=1, description="Prompt内容")
    description: Optional[str] = None
    variables: Optional[List[str]] = None
    system_prompt: Optional[str] = None
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    max_tokens: int = Field(default=2048, ge=1)
    top_p: float = Field(default=1.0, ge=0.0, le=1.0)
    frequency_penalty: float = Field(default=0.0, ge=-2.0, le=2.0)
    presence_penalty: float = Field(default=0.0, ge=-2.0, le=2.0)
    stop_sequences: Optional[List[str]] = None
    tags: Optional[Dict[str, str]] = None
    created_by: Optional[str] = None


class PromptUpdateRequest(BaseModel):
    content: Optional[str] = None
    description: Optional[str] = None
    variables: Optional[List[str]] = None
    system_prompt: Optional[str] = None
    temperature: Optional[float] = Field(default=None, ge=0.0, le=2.0)
    max_tokens: Optional[int] = Field(default=None, ge=1)
    top_p: Optional[float] = Field(default=None, ge=0.0, le=1.0)
    frequency_penalty: Optional[float] = Field(default=None, ge=-2.0, le=2.0)
    presence_penalty: Optional[float] = Field(default=None, ge=-2.0, le=2.0)
    stop_sequences: Optional[List[str]] = None
    status: Optional[PromptStatus] = None
    tags: Optional[Dict[str, str]] = None
    updated_by: Optional[str] = None


class PromptVersionResponse(BaseModel):
    prompt_id: str
    latest_version: int
    versions: List[PromptVersion]


class ABVariant(BaseModel):
    variant_id: str
    name: str
    prompt_version_id: str
    traffic_percentage: float = Field(ge=0.0, le=100.0)
    is_control: bool = False
    description: Optional[str] = None


class ABExperimentCreateRequest(BaseModel):
    experiment_id: str
    name: str
    description: Optional[str] = None
    variants: List[ABVariant]
    traffic_allocation_mode: TrafficAllocationMode = Field(default=TrafficAllocationMode.RANDOM)
    target_sample_size: Optional[int] = None
    metrics: List[MetricType]
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    created_by: Optional[str] = None


class ABExperimentUpdateRequest(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    status: Optional[ExperimentStatus] = None
    variants: Optional[List[ABVariant]] = None
    end_time: Optional[datetime] = None


class ExperimentMetrics(BaseModel):
    metric_type: MetricType
    control_value: float
    variant_value: float
    difference: float
    confidence_interval: Optional[List[float]] = None
    is_statistically_significant: bool = False
    p_value: Optional[float] = None


class ExperimentResult(BaseModel):
    experiment_id: str
    variant_id: str
    total_samples: int
    metrics: List[ExperimentMetrics]
    started_at: datetime
    updated_at: datetime


class ABExperimentResponse(BaseModel):
    experiment_id: str
    name: str
    description: Optional[str]
    status: ExperimentStatus
    variants: List[ABVariant]
    traffic_allocation_mode: TrafficAllocationMode
    target_sample_size: Optional[int]
    metrics: List[MetricType]
    results: Optional[List[ExperimentResult]] = None
    start_time: Optional[datetime]
    end_time: Optional[datetime]
    created_by: Optional[str]
    created_at: datetime
    updated_at: datetime


class PromptComparisonRequest(BaseModel):
    prompt_version_ids: List[str]
    test_cases: List[Dict[str, Any]]
    evaluation_metrics: List[MetricType]


class PromptComparisonResponse(BaseModel):
    comparison_id: str
    prompt_version_ids: List[str]
    results: List[Dict[str, Any]]
    overall_ranking: List[str]
    total_test_cases: int
    completed_at: datetime


class ExperimentQueryParams(BaseModel):
    status: Optional[ExperimentStatus] = None
    created_by: Optional[str] = None
    start_date: Optional[datetime] = None
    end_date: Optional[datetime] = None
