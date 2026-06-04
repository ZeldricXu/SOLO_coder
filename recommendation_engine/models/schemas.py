from pydantic import BaseModel, Field, field_validator
from typing import List, Dict, Optional, Any
from datetime import datetime, timezone
import uuid
import re


def _get_utc_now() -> datetime:
    return datetime.now(timezone.utc)


class UserBehaviorEvent(BaseModel):
    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    user_id: str
    content_id: str
    event_type: str = Field(pattern=r"^(click|stay|purchase|share|expose|collect)$")
    timestamp: datetime = Field(default_factory=_get_utc_now)
    duration_seconds: Optional[float] = Field(default=None, ge=0)
    page: Optional[str] = None
    position: Optional[int] = None
    device_type: Optional[str] = None
    extra: Optional[Dict[str, Any]] = None

    @field_validator("duration_seconds")
    @classmethod
    def check_duration(cls, v: Optional[float], info) -> Optional[float]:
        if info.data["event_type"] == "stay" and (v is None or v <= 0):
            raise ValueError("duration_seconds must be positive for 'stay' events")
        return v


class ExclusionPolicy(BaseModel):
    user_tags_whitelist: List[str] = Field(default_factory=list)
    user_tags_blacklist: List[str] = Field(default_factory=list)
    user_id_pattern: Optional[str] = None
    user_id_whitelist: List[str] = Field(default_factory=list)
    user_id_blacklist: List[str] = Field(default_factory=list)

    def check_user_tags(self, user_tags: List[str]) -> bool:
        tag_set = set(user_tags)
        if self.user_tags_whitelist:
            if not tag_set.issuperset(set(self.user_tags_whitelist)):
                return False
        if self.user_tags_blacklist:
            if tag_set.intersection(set(self.user_tags_blacklist)):
                return False
        return True

    def check_user_id(self, user_id: str) -> bool:
        if self.user_id_whitelist:
            if user_id not in self.user_id_whitelist:
                return False
        if self.user_id_blacklist:
            if user_id in self.user_id_blacklist:
                return False
        if self.user_id_pattern:
            try:
                if re.match(self.user_id_pattern, user_id):
                    return False
            except re.error:
                return False
        return True

    def is_excluded(self, user_id: str, user_tags: Optional[List[str]] = None) -> bool:
        user_tags = user_tags or []
        if not self.check_user_tags(user_tags):
            return True
        if not self.check_user_id(user_id):
            return True
        return False


class ColdStartInitEvent(BaseModel):
    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    content_id: str
    content_embedding: List[float]
    content_info: Optional[Dict[str, Any]] = None
    seed_user_ids: List[str] = Field(default_factory=list)
    timestamp: datetime = Field(default_factory=_get_utc_now)


class OnlineCFUpdateEvent(BaseModel):
    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    user_id: str
    content_id: str
    event_type: str
    weight: float = 1.0
    timestamp: datetime = Field(default_factory=_get_utc_now)


class InterestTag(BaseModel):
    tag_id: str
    tag_name: str
    weight: float = Field(ge=0.0, le=1.0)
    version: str = "v1"
    updated_at: datetime = Field(default_factory=_get_utc_now)


class UserProfile(BaseModel):
    user_id: str
    version: str = "v1"
    profile_version: int = 1
    user_vector: List[float] = Field(default_factory=list)
    interest_tags: List[InterestTag] = Field(default_factory=list)
    offline_tags: List[InterestTag] = Field(default_factory=list)
    realtime_behavior_stats: Dict[str, float] = Field(default_factory=dict)
    demographics: Optional[Dict[str, Any]] = None
    created_at: datetime = Field(default_factory=_get_utc_now)
    updated_at: datetime = Field(default_factory=_get_utc_now)
    experiment_group: Optional[str] = None

    def merge_tags(self) -> List[InterestTag]:
        tag_map: Dict[str, InterestTag] = {}
        for tag in self.offline_tags:
            tag_map[tag.tag_id] = tag
        for tag in self.interest_tags:
            if tag.tag_id in tag_map:
                existing = tag_map[tag.tag_id]
                merged_weight = min(1.0, existing.weight * 0.6 + tag.weight * 0.4)
                tag_map[tag.tag_id] = InterestTag(
                    tag_id=tag.tag_id,
                    tag_name=tag.tag_name,
                    weight=merged_weight,
                    version=f"{existing.version}_{tag.version}",
                    updated_at=datetime.now(timezone.utc),
                )
            else:
                tag_map[tag.tag_id] = tag
        return sorted(tag_map.values(), key=lambda t: t.weight, reverse=True)


class ContentItem(BaseModel):
    content_id: str
    title: Optional[str] = None
    content_type: str = Field(pattern=r"^(article|video|image|product)$")
    categories: List[str] = Field(default_factory=list)
    tags: List[str] = Field(default_factory=list)
    author: Optional[str] = None
    publish_time: datetime = Field(default_factory=_get_utc_now)
    popularity_score: float = Field(default=0.0, ge=0.0)
    metadata: Optional[Dict[str, Any]] = None
    embedding: Optional[List[float]] = None


class ContentEmbedding(BaseModel):
    content_id: str
    embedding: List[float]
    embedding_type: str = "text"
    model_version: str = "v1"
    created_at: datetime = Field(default_factory=_get_utc_now)


class RecallResultItem(BaseModel):
    content_id: str
    score: float
    recall_source: str
    rank: Optional[int] = None


class RankResultItem(BaseModel):
    content_id: str
    final_score: float
    features: Dict[str, float] = Field(default_factory=dict)
    rank: Optional[int] = None


class RerankResultItem(BaseModel):
    content_id: str
    final_score: float
    diversity_penalty: float = 0.0
    rule_adjustment: float = 0.0
    rank: Optional[int] = None


class RecommendRequest(BaseModel):
    user_id: str
    scene: str = "home"
    request_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    top_n: int = Field(default=20, ge=1, le=200)
    context: Optional[Dict[str, Any]] = None
    exclude_content_ids: List[str] = Field(default_factory=list)


class RecommendResponse(BaseModel):
    request_id: str
    user_id: str
    scene: str
    results: List[RerankResultItem]
    experiment_info: Optional[Dict[str, Any]] = None
    processing_time_ms: float = 0.0
    timestamp: datetime = Field(default_factory=_get_utc_now)


class BusinessRuleFilter(BaseModel):
    content_ids: List[str] = Field(default_factory=list)
    content_type: Optional[str] = None
    categories: List[str] = Field(default_factory=list)
    tags: List[str] = Field(default_factory=list)
    min_popularity: Optional[float] = None
    max_publish_age_hours: Optional[int] = None
    min_publish_age_hours: Optional[int] = None


class BusinessRule(BaseModel):
    rule_id: str
    name: str
    type: str = Field(pattern=r"^(boost|penalize|pin|exclude|category_ratio|cold_start_boost)$")
    priority: int = Field(default=5, ge=1, le=10)
    filter: BusinessRuleFilter = Field(default_factory=BusinessRuleFilter)
    params: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    created_at: datetime = Field(default_factory=_get_utc_now)
    updated_at: datetime = Field(default_factory=_get_utc_now)


class BusinessRuleSet(BaseModel):
    scene: str = "home"
    rules: List[BusinessRule] = Field(default_factory=list)
    version: str = "v1"
    updated_at: datetime = Field(default_factory=_get_utc_now)

    def sorted_rules(self) -> List[BusinessRule]:
        return sorted(
            [r for r in self.rules if r.enabled],
            key=lambda r: (-r.priority, r.created_at),
        )


class ABTestExperiment(BaseModel):
    experiment_id: str
    name: str
    layer: str
    version: str = "v1"
    status: str = Field(pattern=r"^(active|paused|ended)$")
    traffic_percentage: int = Field(ge=0, le=100)
    control_group: str
    experiment_groups: List[str]
    config: Dict[str, Any] = Field(default_factory=dict)
    exclusion_policy: Optional[ExclusionPolicy] = None
    created_at: datetime = Field(default_factory=_get_utc_now)
    updated_at: datetime = Field(default_factory=_get_utc_now)


class ABTestAssignment(BaseModel):
    user_id: str
    layer: str
    experiment_id: str
    group: str
    assigned_at: datetime = Field(default_factory=_get_utc_now)
    hash_value: int


class FeedbackEvent(BaseModel):
    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    user_id: str
    content_id: str
    event_type: str
    timestamp: datetime = Field(default_factory=_get_utc_now)
    request_id: Optional[str] = None
    scene: Optional[str] = None
    position: Optional[int] = None
    value: Optional[float] = None
    extra: Optional[Dict[str, Any]] = None


class ModelInferenceRequest(BaseModel):
    model_name: str
    model_version: Optional[str] = None
    inputs: Dict[str, Any]
    request_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    timeout_ms: int = Field(default=10000, ge=100)


class ModelInferenceResponse(BaseModel):
    request_id: str
    model_name: str
    model_version: str
    outputs: Dict[str, Any]
    inference_time_ms: float = 0.0
    backend: str


class HealthStatus(BaseModel):
    service: str
    status: str
    timestamp: datetime = Field(default_factory=_get_utc_now)
    version: str
    components: Dict[str, str] = Field(default_factory=dict)
