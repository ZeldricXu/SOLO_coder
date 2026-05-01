from pydantic import BaseModel, Field
from typing import Optional, Literal
from datetime import datetime
from uuid import UUID

AssetType = Literal["character", "world", "item"]


class AssetBase(BaseModel):
    type: AssetType = Field(..., description="设定类型：角色、世界、物品")
    name: str = Field(..., min_length=1, max_length=100, description="设定名称")
    content: str = Field(..., min_length=1, description="设定内容")


class AssetCreate(AssetBase):
    pass


class AssetUpdate(BaseModel):
    type: Optional[AssetType] = None
    name: Optional[str] = Field(None, min_length=1, max_length=100)
    content: Optional[str] = Field(None, min_length=1)


class AssetResponse(AssetBase):
    id: UUID
    vector_id: Optional[str] = None
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class AssetSearchResult(AssetResponse):
    similarity: float = Field(..., description="相似度分数")
