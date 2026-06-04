from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime

from app.database import get_db
from app.config import settings
from app.templates_shared import templates
from app.services import LogService
from app.schemas import LogSearchRequest, LogTemplateCreate

router = APIRouter(prefix="/api/logs", tags=["logs"])


@router.get("/search")
async def search_logs(
    keyword: Optional[str] = None,
    service_name: Optional[str] = None,
    start_time: Optional[datetime] = None,
    end_time: Optional[datetime] = None,
    level: Optional[str] = None,
    page: int = Query(1),
    page_size: int = Query(50),
    db: Session = Depends(get_db),
):
    log_service = LogService(db)

    request = LogSearchRequest(
        keyword=keyword,
        service_name=service_name,
        start_time=start_time,
        end_time=end_time,
        level=level,
        page=page,
        page_size=page_size,
    )

    result = await log_service.search(request)
    return {
        "success": True,
        **result,
    }


@router.post("/search")
async def search_logs_post(
    request: LogSearchRequest,
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    result = await log_service.search(request)
    return {
        "success": True,
        **result,
    }


@router.get("/templates")
async def get_templates(
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    templates = log_service.get_templates(user_id)
    return {
        "success": True,
        "templates": templates,
    }


@router.post("/templates")
async def create_template(
    data: LogTemplateCreate,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    template = log_service.create_template(user_id, data)
    return {
        "success": True,
        "template": template,
    }


@router.get("/templates/{template_id}")
async def get_template(
    template_id: int,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    template = log_service.get_template_by_id(template_id, user_id)
    if not template:
        raise HTTPException(status_code=404, detail="Template not found")
    return {
        "success": True,
        "template": template,
    }


@router.delete("/templates/{template_id}")
async def delete_template(
    template_id: int,
    user_id: int = Query(settings.default_user_id),
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    success = log_service.delete_template(template_id, user_id)
    if not success:
        raise HTTPException(status_code=404, detail="Template not found")
    return {
        "success": True,
        "message": "Template deleted",
    }


@router.get("/services")
async def get_services(
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    services = log_service.get_available_services()
    return {
        "success": True,
        "services": services,
    }


@router.get("/levels")
async def get_levels(
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    levels = log_service.get_available_levels()
    return {
        "success": True,
        "levels": levels,
    }


@router.get("/partial/search-results")
async def get_search_results_partial(
    keyword: Optional[str] = None,
    service_name: Optional[str] = None,
    start_time: Optional[datetime] = None,
    end_time: Optional[datetime] = None,
    level: Optional[str] = None,
    page: int = Query(1),
    page_size: int = Query(50),
    db: Session = Depends(get_db),
):
    log_service = LogService(db)
    request = LogSearchRequest(
        keyword=keyword,
        service_name=service_name,
        start_time=start_time,
        end_time=end_time,
        level=level,
        page=page,
        page_size=page_size,
    )
    from starlette.requests import Request as StarletteRequest

    result = await log_service.search(request)

    scope = {"type": "http", "method": "GET", "path": "/api/logs/partial/search-results", "headers": []}
    request_obj = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/log_results.html",
        {
            "request": request_obj,
            **result,
        },
    )
