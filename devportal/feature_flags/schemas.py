from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field, ConfigDict

from ..core.schemas import EntityResponse, ConfigResponse, RunInstanceResponse, APIResponse


class Condition(BaseModel):
    field: str
    operator: str
    value: Any

    model_config = ConfigDict(extra="allow")


class Rule(BaseModel):
    name: str
    conditions: List[Condition]
    action: str
    variant: Optional[str] = None
    enabled: bool = True


class Variant(BaseModel):
    value: Any
    weight: float = 1.0
    description: Optional[str] = None


class FeatureFlagBase(BaseModel):
    name: str
    key: str
    description: Optional[str] = None
    enabled: bool = False
    namespace: str = "default"
    rollout_percent: float = Field(default=0.0, ge=0.0, le=100.0)
    rollout_strategy: str = "incremental"
    target_segments: List[str] = Field(default_factory=list)
    rules: List[Rule] = Field(default_factory=list)
    variants: Dict[str, Variant] = Field(default_factory=dict)
    default_variant: Optional[str] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    attributes: Dict[str, Any] = Field(default_factory=dict)


class FeatureFlagCreate(FeatureFlagBase):
    pass


class FeatureFlagUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    enabled: Optional[bool] = None
    namespace: Optional[str] = None
    rollout_percent: Optional[float] = Field(default=None, ge=0.0, le=100.0)
    rollout_strategy: Optional[str] = None
    target_segments: Optional[List[str]] = None
    rules: Optional[List[Rule]] = None
    variants: Optional[Dict[str, Variant]] = None
    default_variant: Optional[str] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    attributes: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class FeatureFlagResponse(FeatureFlagBase):
    id: str
    status: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class UserSegmentBase(BaseModel):
    name: str
    description: Optional[str] = None
    namespace: str = "default"
    conditions: List[Condition] = Field(default_factory=list)
    user_ids: List[str] = Field(default_factory=list)
    attributes: Dict[str, Any] = Field(default_factory=dict)


class UserSegmentCreate(UserSegmentBase):
    pass


class UserSegmentUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    namespace: Optional[str] = None
    conditions: Optional[List[Condition]] = None
    user_ids: Optional[List[str]] = None
    attributes: Optional[Dict[str, Any]] = None


class UserSegmentResponse(UserSegmentBase):
    id: str
    status: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class RolloutPhaseBase(BaseModel):
    flag_id: str
    name: str
    description: Optional[str] = None
    start_percent: float = Field(default=0.0, ge=0.0, le=100.0)
    end_percent: float = Field(default=100.0, ge=0.0, le=100.0)
    start_time: datetime
    end_time: datetime
    criteria: Dict[str, Any] = Field(default_factory=dict)
    status: str = "scheduled"


class RolloutPhaseCreate(RolloutPhaseBase):
    pass


class RolloutPhaseUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    start_percent: Optional[float] = None
    end_percent: Optional[float] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    criteria: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class RolloutPhaseResponse(RolloutPhaseBase):
    id: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class EvaluationRequest(BaseModel):
    flag_key: str
    user_id: Optional[str] = None
    context: Dict[str, Any] = Field(default_factory=dict)
    default_value: Any = False


class EvaluationResponse(BaseModel):
    flag_key: str
    enabled: bool
    value: Any
    variant: Optional[str]
    reason: str
    segment_matched: Optional[str] = None
    rollout_percent: Optional[float] = None
    evaluation_id: str
    timestamp: datetime


class BatchEvaluationRequest(BaseModel):
    flag_keys: List[str]
    user_id: Optional[str] = None
    context: Dict[str, Any] = Field(default_factory=dict)


class BatchEvaluationResponse(BaseModel):
    results: Dict[str, EvaluationResponse]
    timestamp: datetime


class FlagStatsResponse(BaseModel):
    flag_id: str
    flag_key: str
    total_evaluations: int
    enabled_count: int
    disabled_count: int
    variant_distribution: Dict[str, int]
    last_24h_count: int
    unique_users: int


class RolloutScheduleRequest(BaseModel):
    flag_id: str
    phases: List[RolloutPhaseBase]
