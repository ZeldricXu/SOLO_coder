from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional
import json

from app.database import get_db
from app.config import settings
from app.templates_shared import templates
from app.services import PreferenceService
from app.schemas import PreferenceUpdate, PinnedComponentRequest, LayoutConfig

router = APIRouter(prefix="/api/preferences", tags=["preferences"])


@router.get("/layout")
async def get_layout(
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    layout = pref_service.get_layout_config(user_id)
    return {
        "success": True,
        "layout": layout,
    }


@router.post("/layout")
async def save_layout(
    config: LayoutConfig,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    layout = pref_service.save_layout_config(user_id, config)
    return {
        "success": True,
        "layout": layout,
    }


@router.get("")
async def get_preference(
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    pref = pref_service.get_or_create_preference(user_id)
    return {
        "success": True,
        "preference": pref,
    }


@router.put("")
async def update_preference(
    data: PreferenceUpdate,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    pref = pref_service.update_preference(user_id, data)
    return {
        "success": True,
        "preference": pref,
    }


@router.get("/pinned")
async def get_pinned_components(
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    components = pref_service.get_pinned_components(user_id)
    return {
        "success": True,
        "components": components,
    }


@router.post("/pinned")
async def pin_component(
    data: PinnedComponentRequest,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    component = pref_service.pin_component(user_id, data)
    return {
        "success": True,
        "component": component,
    }


@router.delete("/pinned/{component_id}")
async def unpin_component(
    component_id: int,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    success = pref_service.unpin_component(user_id, component_id)
    if not success:
        raise HTTPException(status_code=404, detail="Component not found")
    return {
        "success": True,
        "message": "Component unpinned",
    }


@router.post("/pinned/{component_id}/position")
async def update_component_position(
    component_id: int,
    new_position: int,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    success = pref_service.update_component_position(user_id, component_id, new_position)
    if not success:
        raise HTTPException(status_code=404, detail="Component not found")
    return {
        "success": True,
        "message": "Position updated",
    }


@router.get("/available-components")
async def get_available_components(
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    components = pref_service.get_available_components()
    return {
        "success": True,
        "components": components,
    }


@router.get("/dashboard")
async def get_dashboard_data(
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    data = pref_service.get_user_dashboard_data(user_id)
    return {
        "success": True,
        **data,
    }


@router.delete("/reset")
async def reset_preference(
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    pref_service = PreferenceService(db)
    success = pref_service.reset_preference(user_id)
    if not success:
        raise HTTPException(status_code=404, detail="Preference not found")
    return {
        "success": True,
        "message": "Preference reset",
    }


@router.get("/partial/pinned-list")
async def get_pinned_list_partial(
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    pref_service = PreferenceService(db)
    components = pref_service.get_pinned_components(user_id)
    available = pref_service.get_available_components()

    scope = {"type": "http", "method": "GET", "path": "/api/preferences/partial/pinned-list", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/pinned_components.html",
        {
            "request": request,
            "pinned_components": components,
            "available_components": available,
        },
    )
