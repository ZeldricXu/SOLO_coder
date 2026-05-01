from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime
from uuid import UUID

from app.schemas.chapter import ChapterResponse


class VolumeBase(BaseModel):
    title: str = Field(..., min_length=1, max_length=100, description="卷名")
    description: Optional[str] = Field(None, max_length=500, description="卷描述")


class VolumeCreate(VolumeBase):
    pass


class VolumeUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=100)
    description: Optional[str] = Field(None, max_length=500)


class VolumeResponse(VolumeBase):
    id: UUID
    order: int
    chapters: List[ChapterResponse] = []
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True
