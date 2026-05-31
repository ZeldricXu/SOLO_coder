from datetime import datetime
from typing import List, Optional, Dict, Any, Union
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class FeatureType(str, Enum):
    INT = "int"
    FLOAT = "float"
    STRING = "string"
    BOOLEAN = "boolean"
    LIST = "list"
    MAP = "map"
    EMBEDDING = "embedding"
    DATETIME = "datetime"


class StorageTier(str, Enum):
    ONLINE = "online"
    OFFLINE = "offline"
    BOTH = "both"


class FeatureSchema(BaseModel):
    name: str = Field(..., description="特征名称")
    type: FeatureType = Field(..., description="特征类型")
    description: Optional[str] = Field(default=None, description="特征描述")
    default_value: Optional[Any] = Field(default=None, description="默认值")
    is_nullable: bool = Field(default=True, description="是否允许为空")
    valid_range: Optional[Dict[str, Any]] = Field(default=None, description="取值范围")
    enum_values: Optional[List[Any]] = Field(default=None, description="枚举值列表")
    embedding_dim: Optional[int] = Field(default=None, description="向量维度（仅embedding类型）")

    model_config = ConfigDict(from_attributes=True)


class FeatureEntity(BaseModel):
    entity_name: str = Field(..., description="实体名称，如 user、item、document")
    entity_id_field: str = Field(default="id", description="实体ID字段名")
    features: List[FeatureSchema] = Field(..., description="特征列表")


class FeatureRegistrationRequest(BaseModel):
    entity: FeatureEntity
    storage_tier: StorageTier = Field(default=StorageTier.BOTH, description="存储层级")
    ttl_seconds: Optional[int] = Field(default=None, description="在线存储TTL（秒）")
    version: str = Field(default="1.0.0", description="特征版本")
    tags: Optional[Dict[str, str]] = Field(default=None, description="标签")
    owner: Optional[str] = Field(default=None, description="负责人")


class FeatureRegistrationResponse(BaseModel):
    feature_group_id: str
    entity_name: str
    version: str
    status: str
    registered_at: datetime
    message: str


class FeatureValue(BaseModel):
    feature_name: str
    value: Any
    timestamp: datetime = Field(default_factory=lambda: datetime.now(datetime.timezone.utc))


class FeatureOnlineGetRequest(BaseModel):
    entity_name: str
    entity_ids: List[str]
    feature_names: Optional[List[str]] = Field(default=None, description="要获取的特征名，None则获取全部")


class FeatureOnlineGetResponse(BaseModel):
    entity_name: str
    results: Dict[str, List[FeatureValue]]
    missing_entity_ids: List[str]


class FeaturePoint(BaseModel):
    entity_id: str
    features: List[FeatureValue]
    event_timestamp: datetime


class FeatureOfflineFetchRequest(BaseModel):
    entity_name: str
    start_time: datetime
    end_time: datetime
    entity_ids: Optional[List[str]] = Field(default=None, description="实体ID列表，None则获取全部")
    feature_names: Optional[List[str]] = Field(default=None, description="特征名列表，None则获取全部")
    limit: int = Field(default=10000, ge=1, le=1000000)


class FeatureOfflineFetchResponse(BaseModel):
    entity_name: str
    start_time: datetime
    end_time: datetime
    points: List[FeaturePoint]
    total_count: int


class FeatureIngestRequest(BaseModel):
    entity_name: str
    points: List[FeaturePoint]
    write_mode: str = Field(default="append", description="写入模式: append, overwrite")


class FeatureIngestResponse(BaseModel):
    entity_name: str
    ingested_count: int
    failed_count: int
    errors: List[str]


class ConsistencyCheckRequest(BaseModel):
    entity_name: str
    entity_id: str
    feature_names: Optional[List[str]] = None
    timestamp: Optional[datetime] = None


class ConsistencyCheckResponse(BaseModel):
    entity_name: str
    entity_id: str
    is_consistent: bool
    online_values: Dict[str, Any]
    offline_values: Dict[str, Any]
    inconsistent_features: List[str]
    check_timestamp: datetime


class FeatureGroupInfo(BaseModel):
    feature_group_id: str
    entity_name: str
    version: str
    storage_tier: StorageTier
    features: List[FeatureSchema]
    ttl_seconds: Optional[int]
    tags: Optional[Dict[str, str]]
    owner: Optional[str]
    registered_at: datetime
    last_updated_at: datetime
    status: str
