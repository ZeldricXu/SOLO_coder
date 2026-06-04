from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime, timedelta

from app.database import get_db
from app.templates_shared import templates
from app.services import MetricsService
from app.schemas import MetricsQuery

router = APIRouter(prefix="/api/metrics", tags=["metrics"])


@router.get("/query")
async def query_metrics(
    metric_name: str = Query(...),
    hours: int = Query(24),
    step: Optional[int] = Query(None),
    db: Session = Depends(get_db),
):
    metrics_service = MetricsService(db)

    if not step:
        step = max(60, int(hours * 60 / 144))

    query = MetricsQuery(
        metric_name=metric_name,
        end_time=datetime.now(),
        start_time=datetime.now() - timedelta(hours=hours),
        step=step,
    )

    metrics = await metrics_service.query_metrics(query)
    return {
        "success": True,
        "metrics": metrics,
    }


@router.post("/reload")
async def reload_metrics(
    query: MetricsQuery,
    db: Session = Depends(get_db),
):
    metrics_service = MetricsService(db)
    metrics = await metrics_service.query_metrics(query)
    return {
        "success": True,
        "metrics": metrics,
    }


@router.get("/available")
async def get_available_metrics(
    db: Session = Depends(get_db),
):
    metrics_service = MetricsService(db)
    return {
        "success": True,
        "metrics": metrics_service.get_available_metrics(),
    }


@router.get("/chart/{metric_name}")
async def get_chart_data(
    metric_name: str,
    hours: int = Query(24),
    db: Session = Depends(get_db),
):
    metrics_service = MetricsService(db)
    data = metrics_service.get_chart_data_for_frontend(metric_name, hours)
    return {
        "success": True,
        "data": data,
    }


@router.get("/partial/chart/{metric_name}")
async def get_chart_partial(
    metric_name: str,
    hours: int = Query(24),
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    metrics_service = MetricsService(db)
    chart_data = metrics_service.get_chart_data_for_frontend(metric_name, hours)

    scope = {"type": "http", "method": "GET", "path": "/api/metrics/partial/chart", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/metric_chart.html",
        {
            "request": request,
            "metric_name": metric_name,
            "hours": hours,
            "chart_data": chart_data,
            "metric_info": chart_data.get("metric_info", {}),
        },
    )
