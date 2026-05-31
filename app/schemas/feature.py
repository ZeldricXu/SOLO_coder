from datetime import datetime
from typing import Optional, Dict, Any, List
from uuid import UUID
from pydantic import BaseModel, Field, ConfigDict


class FeatureCreate(BaseModel):
    name: str = Field(..., max_length=255, description="特征名称")
    namespace: str = Field(..., max_length=255, description="命名空间")
    description: Optional[str] = Field(None, max_length=1000, description="描述")
    entity_type: str = Field(..., max_length=100, description="实体类型")
    value_type: str = Field(..., max_length=50, description="值类型")
    is_online: bool = Field(True, description="是否在线可用")
    is_offline: bool = Field(True, description="是否离线可用")
    ttl_seconds: int = Field(86400, description="TTL(秒)")
    schema_definition: Dict[str, Any] = Field(..., description="Schema定义")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class FeatureUpdate(BaseModel):
    description: Optional[str] = Field(None, max_length=1000, description="描述")
    is_online: Optional[bool] = Field(None, description="是否在线可用")
    is_offline: Optional[bool] = Field(None, description="是否离线可用")
    ttl_seconds: Optional[int] = Field(None, description="TTL(秒)")
    schema_definition: Optional[Dict[str, Any]] = Field(None, description="Schema定义")
    metadata: Optional[Dict[str, Any]] = Field(None, description="元数据")


class FeatureVersionCreate(BaseModel):
    feature_id: UUID = Field(..., description="特征ID")
    data_source: Optional[str] = Field(None, description="数据源")
    transformation_logic: Optional[Dict[str, Any]] = Field(None, description="转换逻辑")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class FeatureVersionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="版本ID")
    feature_id: UUID = Field(..., description="特征ID")
    version: int = Field(..., description="版本号")
    data_source: Optional[str] = Field(None, description="数据源")
    transformation_logic: Optional[Dict[str, Any]] = Field(None, description="转换逻辑")
    checksum: Optional[str] = Field(None, description="校验和")
    is_active: bool = Field(..., description="是否激活")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class FeatureResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="特征ID")
    name: str = Field(..., description="特征名称")
    namespace: str = Field(..., description="命名空间")
    description: Optional[str] = Field(None, description="描述")
    entity_type: str = Field(..., description="实体类型")
    value_type: str = Field(..., description="值类型")
    is_online: bool = Field(..., description="是否在线可用")
    is_offline: bool = Field(..., description="是否离线可用")
    ttl_seconds: int = Field(..., description="TTL(秒)")
    schema_definition: Dict[str, Any] = Field(..., description="Schema定义")
    created_at: datetime = Field(..., description="创建时间")
    updated_at: datetime = Field(..., description="更新时间")
    versions: List[FeatureVersionResponse] = Field(default_factory=list, description="版本列表")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class FeatureQuery(BaseModel):
    namespace: Optional[str] = Field(None, description="命名空间")
    entity_type: Optional[str] = Field(None, description="实体类型")
    name_pattern: Optional[str] = Field(None, description="名称模式")
    is_online: Optional[bool] = Field(None, description="是否在线可用")


class FeatureDataBatch(BaseModel):
    entity_ids: List[str] = Field(..., description="实体ID列表")
    feature_names: List[str] = Field(..., description="特征名称列表")
    namespace: str = Field(..., description="命名空间")
