from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime
from uuid import UUID


class ChapterBase(BaseModel):
    title: str = Field(..., min_length=1, max_length=100, description="章节标题")


class ChapterCreate(ChapterBase):
    pass


class ChapterUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=100)
    content: Optional[str] = Field(None, description="章节内容")


class ChapterResponse(ChapterBase):
    id: UUID
    content: str
    order: int
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True
