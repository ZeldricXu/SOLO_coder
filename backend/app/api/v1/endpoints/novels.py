from fastapi import APIRouter, HTTPException, status
from typing import List
from uuid import UUID

from app.schemas.novel import NovelCreate, NovelUpdate, NovelResponse, NovelList
from app.services.novel_service import NovelService

router = APIRouter()


@router.get("/", response_model=List[NovelList])
async def get_novels():
    """获取所有小说列表"""
    # TODO: 实现获取小说列表
    return []


@router.post("/", response_model=NovelResponse, status_code=status.HTTP_201_CREATED)
async def create_novel(novel_in: NovelCreate):
    """创建新小说"""
    # TODO: 实现创建小说
    return NovelResponse(
        id=UUID("00000000-0000-0000-0000-000000000000"),
        title=novel_in.title,
        description=novel_in.description or "",
        volumes=[],
        created_at="2024-01-01T00:00:00",
        updated_at="2024-01-01T00:00:00",
    )


@router.get("/{novel_id}", response_model=NovelResponse)
async def get_novel(novel_id: UUID):
    """获取单个小说详情"""
    # TODO: 实现获取小说详情
    raise HTTPException(status_code=404, detail="Novel not found")


@router.put("/{novel_id}", response_model=NovelResponse)
async def update_novel(novel_id: UUID, novel_in: NovelUpdate):
    """更新小说信息"""
    # TODO: 实现更新小说
    raise HTTPException(status_code=404, detail="Novel not found")


@router.delete("/{novel_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_novel(novel_id: UUID):
    """删除小说"""
    # TODO: 实现删除小说
    raise HTTPException(status_code=404, detail="Novel not found")
