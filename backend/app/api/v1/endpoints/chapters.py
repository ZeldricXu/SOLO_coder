from fastapi import APIRouter, HTTPException, status
from typing import List
from uuid import UUID

from app.schemas.chapter import ChapterCreate, ChapterUpdate, ChapterResponse

router = APIRouter()


@router.get("/", response_model=List[ChapterResponse])
async def get_chapters(novel_id: UUID, volume_id: UUID):
    """获取章节列表"""
    # TODO: 实现获取章节列表
    return []


@router.post("/", response_model=ChapterResponse, status_code=status.HTTP_201_CREATED)
async def create_chapter(novel_id: UUID, volume_id: UUID, chapter_in: ChapterCreate):
    """创建新章节"""
    # TODO: 实现创建章节
    return ChapterResponse(
        id=UUID("00000000-0000-0000-0000-000000000000"),
        title=chapter_in.title,
        content="",
        order=1,
        created_at="2024-01-01T00:00:00",
        updated_at="2024-01-01T00:00:00",
    )


@router.get("/{chapter_id}", response_model=ChapterResponse)
async def get_chapter(chapter_id: UUID):
    """获取章节详情"""
    # TODO: 实现获取章节详情
    raise HTTPException(status_code=404, detail="Chapter not found")


@router.patch("/{chapter_id}", response_model=ChapterResponse)
async def update_chapter(chapter_id: UUID, chapter_in: ChapterUpdate):
    """更新章节内容（增量保存）"""
    # TODO: 实现更新章节
    raise HTTPException(status_code=404, detail="Chapter not found")


@router.delete("/{chapter_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_chapter(chapter_id: UUID):
    """删除章节"""
    # TODO: 实现删除章节
    raise HTTPException(status_code=404, detail="Chapter not found")
