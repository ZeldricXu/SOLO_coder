from datetime import datetime
from typing import Optional, Dict, Any
from uuid import UUID
from pydantic import BaseModel, Field, ConfigDict


class StorageObjectCreate(BaseModel):
    bucket: str = Field(..., description="存储桶")
    key: str = Field(..., description="对象键")
    content_type: Optional[str] = Field(None, description="内容类型")
    storage_class: str = Field("standard", description="存储类型")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")
    tags: Dict[str, str] = Field(default_factory=dict, description="标签")


class StorageObjectResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="对象ID")
    bucket: str = Field(..., description="存储桶")
    key: str = Field(..., description="对象键")
    version_id: Optional[str] = Field(None, description="版本ID")
    size_bytes: int = Field(..., description="大小(字节)")
    content_type: Optional[str] = Field(None, description="内容类型")
    checksum: Optional[str] = Field(None, description="校验和")
    storage_class: str = Field(..., description="存储类型")
    is_archived: bool = Field(..., description="是否归档")
    last_accessed_at: Optional[datetime] = Field(None, description="最后访问时间")
    access_count: int = Field(..., description="访问次数")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")
    tags: Dict[str, Any] = Field(default_factory=dict, description="标签")


class StorageMetadataCreate(BaseModel):
    storage_object_id: UUID = Field(..., description="存储对象ID")
    key: str = Field(..., description="元数据键")
    value: Any = Field(..., description="元数据值")
    data_type: str = Field("string", description="数据类型")
    is_searchable: bool = Field(True, description="是否可搜索")


class PresignedUrlRequest(BaseModel):
    bucket: str = Field(..., description="存储桶")
    key: str = Field(..., description="对象键")
    operation: str = Field("put", description="操作类型: put/get/delete")
    expires_in: int = Field(3600, description="过期时间(秒)")
    version_id: Optional[str] = Field(None, description="版本ID")


class PresignedUrlResponse(BaseModel):
    url: str = Field(..., description="预签名URL")
    expires_in: int = Field(..., description="过期时间(秒)")
    operation: str = Field(..., description="操作类型")
