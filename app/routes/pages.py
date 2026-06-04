from fastapi import APIRouter, Request, Depends, Query
from sqlalchemy.orm import Session
from typing import Optional

from app.database import get_db
from app.config import settings
from app.templates_shared import templates
from app.services import (
    HealthService,
    AlertService,
    MetricsService,
    SlowSQLService,
    AssetService,
    DutyService,
    LogService,
    PreferenceService,
)

router = APIRouter()


@router.get("/")
async def index(
    request: Request,
    db: Session = Depends(get_db),
    user_id: int = Query(settings.default_user_id),
):
    pref_service = PreferenceService(db)
    dashboard = pref_service.get_user_dashboard_data(user_id)

    return templates.TemplateResponse(
        "index.html",
        {
            "request": request,
            "active_tab": "home",
            "user_id": user_id,
            "layout": dashboard["layout"],
            "pinned_components": dashboard["pinned_components"],
            "component_data": dashboard["component_data"],
            "available_components": pref_service.get_available_components(),
        },
    )


@router.get("/health")
async def health_page(
    request: Request,
    db: Session = Depends(get_db),
):
    health_service = HealthService(db)
    services = health_service.get_all_services()
    summary = health_service.get_summary()

    return templates.TemplateResponse(
        "health.html",
        {
            "request": request,
            "active_tab": "health",
            "services": services,
            "summary": summary,
        },
    )


@router.get("/metrics")
async def metrics_page(
    request: Request,
    db: Session = Depends(get_db),
    metric: str = Query("cpu_usage"),
    hours: int = Query(24),
):
    metrics_service = MetricsService(db)
    available_metrics = metrics_service.get_available_metrics()

    return templates.TemplateResponse(
        "metrics.html",
        {
            "request": request,
            "active_tab": "metrics",
            "current_metric": metric,
            "hours": hours,
            "available_metrics": available_metrics,
        },
    )


@router.get("/alerts")
async def alerts_page(
    request: Request,
    db: Session = Depends(get_db),
    status: Optional[str] = Query(None),
):
    alert_service = AlertService(db)
    rules = alert_service.get_all_rules()
    alerts = alert_service.get_alert_history(status=status, limit=100)
    summary = alert_service.get_summary()

    return templates.TemplateResponse(
        "alerts.html",
        {
            "request": request,
            "active_tab": "alerts",
            "rules": rules,
            "alerts": alerts,
            "summary": summary,
            "current_status": status,
        },
    )


@router.get("/slow-sql")
async def slow_sql_page(
    request: Request,
    db: Session = Depends(get_db),
    table: Optional[str] = Query(None),
    sort_by: str = Query("last_seen"),
):
    slow_sql_service = SlowSQLService(db)
    sqls = slow_sql_service.get_slow_sql_list(table_name=table, sort_by=sort_by, limit=100)
    tables = slow_sql_service.get_tables()
    stats = slow_sql_service.get_statistics()

    return templates.TemplateResponse(
        "slow_sql.html",
        {
            "request": request,
            "active_tab": "slow_sql",
            "sqls": sqls,
            "tables": tables,
            "stats": stats,
            "current_table": table,
            "current_sort": sort_by,
        },
    )


@router.get("/assets")
async def assets_page(
    request: Request,
    db: Session = Depends(get_db),
    category: Optional[str] = Query(None),
):
    asset_service = AssetService(db)
    assets = asset_service.get_all_assets(category=category)
    categories = asset_service.get_categories()
    summary = asset_service.get_summary()

    return templates.TemplateResponse(
        "assets.html",
        {
            "request": request,
            "active_tab": "assets",
            "assets": assets,
            "categories": categories,
            "summary": summary,
            "current_category": category,
        },
    )


@router.get("/duty")
async def duty_page(
    request: Request,
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    today_duty = duty_service.get_today_duty()
    current_duty = duty_service.get_duty_user()
    upcoming = duty_service.get_upcoming_duties(days=7)
    week_schedule = duty_service.get_current_week_schedule()
    reports = duty_service.get_handover_reports(limit=20)

    return templates.TemplateResponse(
        "duty.html",
        {
            "request": request,
            "active_tab": "duty",
            "today_duty": today_duty,
            "current_duty": current_duty,
            "upcoming": upcoming,
            "week_schedule": week_schedule,
            "reports": reports,
        },
    )


@router.get("/logs")
async def logs_page(
    request: Request,
    db: Session = Depends(get_db),
    user_id: int = Query(settings.default_user_id),
):
    log_service = LogService(db)
    templates_list = log_service.get_templates(user_id)
    services = log_service.get_available_services()
    levels = log_service.get_available_levels()

    return templates.TemplateResponse(
        "logs.html",
        {
            "request": request,
            "active_tab": "logs",
            "templates": templates_list,
            "services": services,
            "levels": levels,
        },
    )


@router.get("/settings")
async def settings_page(
    request: Request,
    db: Session = Depends(get_db),
    user_id: int = Query(settings.default_user_id),
):
    pref_service = PreferenceService(db)
    pref = pref_service.get_or_create_preference(user_id)
    layout = pref_service.get_layout_config(user_id)
    pinned = pref_service.get_pinned_components(user_id)
    available = pref_service.get_available_components()

    return templates.TemplateResponse(
        "settings.html",
        {
            "request": request,
            "active_tab": "settings",
            "preference": pref,
            "layout": layout,
            "pinned_components": pinned,
            "available_components": available,
        },
    )
