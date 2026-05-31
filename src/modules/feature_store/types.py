from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional
from enum import Enum
from datetime import datetime


class FeatureType(str, Enum):
    SCALAR = "scalar"
    VECTOR = "vector"
    CATEGORICAL = "categorical"
    SEQUENCE = "sequence"
    MAP = "map"


class FeatureValueType(str, Enum):
    INT = "int"
    FLOAT = "float"
    STRING = "string"
    BOOL = "bool"
    DATETIME = "datetime"


class FeatureEntity(BaseModel):
    name: str
    description: str = ""
    join_keys: List[str] = Field(default_factory=list)
    labels: Dict[str, str] = Field(default_factory=dict)


class FeatureDefinition(BaseModel):
    feature_id: Optional[str] = None
    name: str
    entity: str
    value_type: FeatureValueType
    feature_type: FeatureType = FeatureType.SCALAR
    description: str = ""
    dimensions: Optional[int] = None
    tags: List[str] = Field(default_factory=list)
    version: int = 1
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class FeatureValue(BaseModel):
    feature_name: str
    entity_id: str
    value: Any
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    event_timestamp: Optional[datetime] = None


class FeatureLookupRequest(BaseModel):
    entity_id: str
    features: List[str]
    feature_ids: Optional[List[str]] = None


class FeatureStoreRequest(BaseModel):
    entity_id: str
    features: List[FeatureValue]
    ttl_seconds: Optional[int] = None


class HistoricalLookupRequest(BaseModel):
    entity_ids: List[str]
    features: List[str]
    start_time: datetime
    end_time: Optional[datetime] = None


class FeatureOnlineStats(BaseModel):
    feature_name: str
    last_updated: datetime
    access_count: int = 0
    average_latency_ms: float = 0.0
    hit_rate: float = 0.0


class ConsistencyCheckResult(BaseModel):
    feature_name: str
    entity_id: str
    online_value: Any
    offline_value: Any
    is_consistent: bool
    diff: Optional[Dict[str, Any]] = None


class FeatureSet(BaseModel):
    set_id: Optional[str] = None
    name: str
    features: List[str]
    description: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)
