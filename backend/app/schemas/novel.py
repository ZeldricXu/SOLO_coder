from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime
from uuid import UUID

from app.schemas.volume import VolumeResponse


class NovelBase(BaseModel):
    title: str = Field(..., min_length=1, max_length=100, description="小说标题")
    description: Optional[str] = Field(None, max_length=1000, description="小说描述")


class NovelCreate(NovelBase):
    pass


class NovelUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=100)
    description: Optional[str] = Field(None, max_length=1000)


class NovelResponse(NovelBase):
    id: UUID
    volumes: List[VolumeResponse] = []
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class NovelList(BaseModel):
    id: UUID
    title: str
    description: Optional[str]
    volume_count: int
    chapter_count: int
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True
