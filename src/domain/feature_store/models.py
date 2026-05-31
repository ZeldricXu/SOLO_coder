from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional
from enum import Enum
from datetime import datetime


class FeatureType(str, Enum):
    SCALAR = "scalar"
    VECTOR = "vector"
    MAP = "map"
    LIST = "list"
    TIMESTAMP = "timestamp"


class FeatureValueType(str, Enum):
    INT = "int"
    FLOAT = "float"
    STRING = "string"
    BOOL = "bool"
    BYTES = "bytes"
    DATETIME = "datetime"


class FeatureEntity(BaseModel):
    entity_id: Optional[str] = None
    name: str
    description: str = ""
    join_keys: List[str] = Field(default_factory=list)
    labels: Dict[str, str] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)


class FeatureDefinition(BaseModel):
    feature_id: Optional[str] = None
    entity_id: str
    name: str
    description: Optional[str] = None
    type: FeatureType = FeatureType.SCALAR
    value_type: FeatureValueType = FeatureValueType.FLOAT
    dimensions: Optional[List[int]] = None
    ttl_seconds: Optional[int] = None
    tags: List[str] = Field(default_factory=list)
    created_at: datetime = Field(default_factory=datetime.utcnow)


class FeatureValue(BaseModel):
    feature_name: str
    value: Any
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class FeatureLookupRequest(BaseModel):
    entity_id: str
    entity_key: Dict[str, Any]
    feature_names: List[str]


class FeatureStoreRequest(BaseModel):
    entity_id: str
    entity_key: Dict[str, Any]
    features: List[FeatureValue]


class HistoricalLookupRequest(BaseModel):
    entity_id: str
    entity_key: Dict[str, Any]
    feature_names: List[str]
    start_time: datetime
    end_time: datetime


class FeatureOnlineStats(BaseModel):
    feature_name: str
    read_count: int = 0
    write_count: int = 0
    last_read_at: Optional[datetime] = None
    last_write_at: Optional[datetime] = None
    avg_latency_ms: float = 0.0


class ConsistencyCheckResult(BaseModel):
    feature_name: str
    is_consistent: bool
    online_value: Any
    offline_value: Any
    diff_score: float
    checked_at: datetime = Field(default_factory=datetime.utcnow)


class FeatureSet(BaseModel):
    set_id: str
    name: str
    feature_ids: List[str]
    created_at: datetime = Field(default_factory=datetime.utcnow)
