from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks
from sqlalchemy.orm import Session
from typing import List
import json

from app.database import get_db
from app.templates_shared import templates
from app.services import HealthService
from app.schemas import ServiceCreate, ServiceUpdate, HealthCheckResult

router = APIRouter(prefix="/api/health", tags=["health"])


@router.get("/check")
async def check_all(
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    results = await health_service.check_all_services()
    return {
        "success": True,
        "count": len(results),
        "results": results,
    }


@router.get("/status")
async def get_statuses(
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    statuses = health_service.get_all_statuses()
    return {
        "success": True,
        "statuses": statuses,
    }


@router.get("/status/{service_id}")
async def get_service_status(
    service_id: int,
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    status = health_service.get_service_status(service_id)
    if not status:
        raise HTTPException(status_code=404, detail="Service not found")
    return {
        "success": True,
        "service": status["service"],
        "last_check": status["last_check"],
        "details": status["details"],
    }


@router.get("/service/{service_id}")
async def get_service_detail(
    service_id: int,
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    service = health_service.get_service_by_id(service_id)
    if not service:
        raise HTTPException(status_code=404, detail="Service not found")

    recent_checks = health_service.get_recent_checks(service_id, limit=20)

    return {
        "success": True,
        "service": service,
        "recent_checks": recent_checks,
    }


@router.post("/service")
async def create_service(
    data: ServiceCreate,
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    service = health_service.create_service(data)
    return {
        "success": True,
        "service": service,
    }


@router.put("/service/{service_id}")
async def update_service(
    service_id: int,
    data: ServiceUpdate,
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    service = health_service.update_service(service_id, data)
    if not service:
        raise HTTPException(status_code=404, detail="Service not found")
    return {
        "success": True,
        "service": service,
    }


@router.delete("/service/{service_id}")
async def delete_service(
    service_id: int,
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    success = health_service.delete_service(service_id)
    if not success:
        raise HTTPException(status_code=404, detail="Service not found")
    return {
        "success": True,
        "message": "Service deleted",
    }


@router.get("/summary")
async def get_summary(
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    summary = health_service.get_summary()
    return {
        "success": True,
        "summary": summary,
    }


@router.get("/partial/cards")
async def get_health_cards_partial(
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    health_service = HealthService(db)
    services = health_service.get_all_services()
    summary = health_service.get_summary()

    scope = {"type": "http", "method": "GET", "path": "/api/health/partial/cards", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/health_cards.html",
        {
            "request": request,
            "services": services,
            "summary": summary,
        },
    )


@router.get("/partial/service/{service_id}")
async def get_service_detail_partial(
    service_id: int,
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    health_service = HealthService(db)
    status = health_service.get_service_status(service_id)
    if not status:
        raise HTTPException(status_code=404, detail="Service not found")

    recent_checks = health_service.get_recent_checks(service_id, limit=10)

    scope = {"type": "http", "method": "GET", "path": "/api/health/partial/service", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/service_detail.html",
        {
            "request": request,
            "service": status["service"],
            "last_check": status["last_check"],
            "details": status["details"],
            "recent_checks": recent_checks,
        },
    )


@router.get("/partial/timeline/{service_id}")
async def get_service_timeline_partial(
    service_id: int,
    hours: int = 1,
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    health_service = HealthService(db)
    service = health_service.get_service_by_id(service_id)
    if not service:
        raise HTTPException(status_code=404, detail="Service not found")

    checks = health_service.get_check_history(service_id, hours=hours)

    scope = {"type": "http", "method": "GET", "path": "/api/health/partial/timeline", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/health_timeline.html",
        {
            "request": request,
            "service": service,
            "checks": checks,
        },
    )
