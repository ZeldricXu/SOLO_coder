from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks
from sqlalchemy.orm import Session
from typing import Optional, List

from app.database import get_db
from app.templates_shared import templates
from app.services import AlertService
from app.schemas import AlertRuleCreate, AlertRuleUpdate, AlertAck, AlertTrigger

router = APIRouter(prefix="/api/alerts", tags=["alerts"])


@router.get("/rules")
async def get_rules(
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    rules = alert_service.get_all_rules()
    return {
        "success": True,
        "rules": rules,
    }


@router.post("/rules")
async def create_rule(
    data: AlertRuleCreate,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    rule = alert_service.create_rule(data)
    return {
        "success": True,
        "rule": rule,
    }


@router.get("/rules/{rule_id}")
async def get_rule(
    rule_id: int,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    rule = alert_service.get_rule_by_id(rule_id)
    if not rule:
        raise HTTPException(status_code=404, detail="Rule not found")
    return {
        "success": True,
        "rule": rule,
    }


@router.put("/rules/{rule_id}")
async def update_rule(
    rule_id: int,
    data: AlertRuleUpdate,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    rule = alert_service.update_rule(rule_id, data)
    if not rule:
        raise HTTPException(status_code=404, detail="Rule not found")
    return {
        "success": True,
        "rule": rule,
    }


@router.delete("/rules/{rule_id}")
async def delete_rule(
    rule_id: int,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    success = alert_service.delete_rule(rule_id)
    if not success:
        raise HTTPException(status_code=404, detail="Rule not found")
    return {
        "success": True,
        "message": "Rule deleted",
    }


@router.get("/history")
async def get_history(
    status: Optional[str] = None,
    limit: int = 100,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    alerts = alert_service.get_alert_history(status=status, limit=limit)
    return {
        "success": True,
        "alerts": alerts,
    }


@router.post("/ack/{alert_id}")
async def acknowledge_alert(
    alert_id: int,
    data: AlertAck,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    alert = alert_service.acknowledge_alert(alert_id, data)
    if not alert:
        raise HTTPException(status_code=404, detail="Alert not found")
    return {
        "success": True,
        "alert": alert,
    }


@router.post("/resolve/{alert_id}")
async def resolve_alert(
    alert_id: int,
    user_id: int,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    alert = alert_service.resolve_alert(alert_id, user_id)
    if not alert:
        raise HTTPException(status_code=404, detail="Alert not found")
    return {
        "success": True,
        "alert": alert,
    }


@router.post("/trigger")
async def trigger_alert(
    data: AlertTrigger,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    alert = alert_service.manual_trigger(data)
    return {
        "success": True,
        "alert": alert,
    }


@router.get("/evaluate")
async def evaluate_rules(
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    triggered = alert_service.evaluate_rules()
    return {
        "success": True,
        "triggered_count": len(triggered),
        "triggered": triggered,
    }


@router.get("/summary")
async def get_summary(
    db: Session = Depends(get_db),
):
    alert_service = AlertService(db)
    summary = alert_service.get_summary()
    return {
        "success": True,
        "summary": summary,
    }


@router.get("/partial/list")
async def get_alerts_partial(
    status: Optional[str] = None,
    limit: int = 20,
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    alert_service = AlertService(db)
    alerts = alert_service.get_alert_history(status=status, limit=limit)
    summary = alert_service.get_summary()

    scope = {"type": "http", "method": "GET", "path": "/api/alerts/partial/list", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/alert_list.html",
        {
            "request": request,
            "alerts": alerts,
            "summary": summary,
        },
    )
