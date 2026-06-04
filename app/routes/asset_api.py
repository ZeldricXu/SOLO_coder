from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional, List

from app.database import get_db
from app.config import settings
from app.templates_shared import templates
from app.services import AssetService
from app.schemas import AssetCreate, AssetUpdate

router = APIRouter(prefix="/api/assets", tags=["assets"])


@router.get("/list")
async def get_assets(
    category: Optional[str] = None,
    status: Optional[str] = None,
    owner: Optional[str] = None,
    keyword: Optional[str] = None,
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    assets = asset_service.get_all_assets(
        category=category,
        status=status,
        owner=owner,
        keyword=keyword,
    )
    return {
        "success": True,
        "count": len(assets),
        "assets": assets,
    }


@router.post("/create")
async def create_asset(
    data: AssetCreate,
    operator_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    asset = asset_service.create_asset(data, operator_id)
    return {
        "success": True,
        "asset": asset,
    }


@router.get("/{asset_id}")
async def get_asset(
    asset_id: int,
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    result = asset_service.get_asset_with_changes(asset_id)
    if not result:
        raise HTTPException(status_code=404, detail="Asset not found")
    return {
        "success": True,
        **result,
    }


@router.put("/{asset_id}")
async def update_asset(
    asset_id: int,
    data: AssetUpdate,
    operator_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    asset = asset_service.update_asset(asset_id, data, operator_id)
    if not asset:
        raise HTTPException(status_code=404, detail="Asset not found")
    return {
        "success": True,
        "asset": asset,
    }


@router.delete("/{asset_id}")
async def delete_asset(
    asset_id: int,
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    success = asset_service.delete_asset(asset_id)
    if not success:
        raise HTTPException(status_code=404, detail="Asset not found")
    return {
        "success": True,
        "message": "Asset deleted",
    }


@router.get("/change-log/{asset_id}")
async def get_change_log(
    asset_id: int,
    limit: int = Query(50),
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    logs = asset_service.get_change_log(asset_id=asset_id, limit=limit)
    return {
        "success": True,
        "count": len(logs),
        "logs": logs,
    }


@router.get("/categories")
async def get_categories(
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    categories = asset_service.get_categories()
    return {
        "success": True,
        "categories": categories,
    }


@router.get("/owners")
async def get_owners(
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    owners = asset_service.get_owners()
    return {
        "success": True,
        "owners": owners,
    }


@router.get("/summary")
async def get_summary(
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    summary = asset_service.get_summary()
    return {
        "success": True,
        "summary": summary,
    }


@router.post("/batch-status")
async def batch_update_status(
    asset_ids: List[int],
    status: str,
    operator_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    count = asset_service.batch_update_status(asset_ids, status, operator_id)
    return {
        "success": True,
        "updated_count": count,
    }


@router.get("/search/ip")
async def search_by_ip(
    ip_pattern: str,
    db: Session = Depends(get_db),
):
    asset_service = AssetService(db)
    assets = asset_service.search_by_ip(ip_pattern)
    return {
        "success": True,
        "count": len(assets),
        "assets": assets,
    }


@router.get("/partial/list")
async def get_assets_partial(
    category: Optional[str] = None,
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    asset_service = AssetService(db)
    assets = asset_service.get_all_assets(category=category)
    categories = asset_service.get_categories()

    scope = {"type": "http", "method": "GET", "path": "/api/assets/partial/list", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/asset_list.html",
        {
            "request": request,
            "assets": assets,
            "categories": categories,
            "current_category": category,
        },
    )


@router.get("/partial/changes/{asset_id}")
async def get_changes_partial(
    asset_id: int,
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    asset_service = AssetService(db)
    asset = asset_service.get_asset_by_id(asset_id)
    if not asset:
        raise HTTPException(status_code=404, detail="Asset not found")

    changes = asset_service.get_change_log(asset_id=asset_id, limit=50)

    scope = {"type": "http", "method": "GET", "path": "/api/assets/partial/changes", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/asset_changes.html",
        {
            "request": request,
            "asset": asset,
            "changes": changes,
        },
    )
