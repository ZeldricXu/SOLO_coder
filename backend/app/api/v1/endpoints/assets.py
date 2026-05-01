from fastapi import APIRouter, HTTPException, status, Query
from typing import List, Optional
from uuid import UUID

from app.schemas.asset import AssetCreate, AssetUpdate, AssetResponse, AssetSearchResult

router = APIRouter()


@router.get("/search", response_model=List[AssetSearchResult])
async def search_assets(
    novel_id: UUID,
    q: str = Query(..., description="搜索关键词"),
    limit: int = Query(5, ge=1, le=20, description="返回结果数量"),
):
    """搜索设定（语义搜索）"""
    # TODO: 实现语义搜索
    return []


@router.get("/", response_model=List[AssetResponse])
async def get_assets(novel_id: UUID, asset_type: Optional[str] = None):
    """获取设定列表"""
    # TODO: 实现获取设定列表
    return []


@router.post("/", response_model=AssetResponse, status_code=status.HTTP_201_CREATED)
async def create_asset(novel_id: UUID, asset_in: AssetCreate):
    """创建新设定"""
    # TODO: 实现创建设定
    return AssetResponse(
        id=UUID("00000000-0000-0000-0000-000000000000"),
        type=asset_in.type,
        name=asset_in.name,
        content=asset_in.content,
        vector_id=None,
        created_at="2024-01-01T00:00:00",
        updated_at="2024-01-01T00:00:00",
    )


@router.get("/{asset_id}", response_model=AssetResponse)
async def get_asset(asset_id: UUID):
    """获取设定详情"""
    # TODO: 实现获取设定详情
    raise HTTPException(status_code=404, detail="Asset not found")


@router.put("/{asset_id}", response_model=AssetResponse)
async def update_asset(asset_id: UUID, asset_in: AssetUpdate):
    """更新设定"""
    # TODO: 实现更新设定
    raise HTTPException(status_code=404, detail="Asset not found")


@router.delete("/{asset_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_asset(asset_id: UUID):
    """删除设定"""
    # TODO: 实现删除设定
    raise HTTPException(status_code=404, detail="Asset not found")
