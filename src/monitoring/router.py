from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from src.common.models import APIResponse
from src.monitoring.models import (
    AlertRule,
    AlertEvent,
    AlertSeverity,
    AlertCondition,
    NotificationChannel,
    NotificationChannelType,
    AlertStatus,
)
from src.monitoring.alerting import AlertManager

router = APIRouter(prefix="/monitoring", tags=["Monitoring"])

_alert_manager: Optional[AlertManager] = None


def get_alert_manager() -> AlertManager:
    global _alert_manager
    if _alert_manager is None:
        _alert_manager = AlertManager()
        _alert_manager.notification_service.register_channel(
            NotificationChannel(
                name="Slack Alerts",
                type=NotificationChannelType.SLACK,
                config={"webhook_url": "https://hooks.slack.com/..."},
            )
        )
    return _alert_manager


class CreateAlertRuleRequest(BaseModel):
    name: str
    description: str = ""
    conditions: List[AlertCondition]
    severity: AlertSeverity = AlertSeverity.WARNING
    notification_channels: List[str] = []
    labels: Dict[str, str] = {}


class RecordMetricRequest(BaseModel):
    metric: str
    value: float
    tags: Dict[str, str] = {}


@router.get("/alerts/rules")
async def list_alert_rules(
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    return APIResponse(data=[r.model_dump() for r in manager.list_rules()])


@router.post("/alerts/rules", status_code=201)
async def create_alert_rule(
    request: CreateAlertRuleRequest,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    rule = AlertRule(
        name=request.name,
        description=request.description,
        conditions=request.conditions,
        severity=request.severity,
        notification_channels=request.notification_channels,
        labels=request.labels,
    )
    rule_id = manager.add_rule(rule)
    return APIResponse(code=201, data={"rule_id": rule_id})


@router.get("/alerts/rules/{rule_id}")
async def get_alert_rule(
    rule_id: str,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    rule = manager.get_rule(rule_id)
    if not rule:
        raise HTTPException(status_code=404, detail="Rule not found")
    return APIResponse(data=rule.model_dump())


@router.delete("/alerts/rules/{rule_id}")
async def delete_alert_rule(
    rule_id: str,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    deleted = manager.remove_rule(rule_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Rule not found")
    return APIResponse(data={"rule_id": rule_id, "deleted": True})


@router.get("/alerts/active")
async def list_active_alerts(
    status: Optional[AlertStatus] = None,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    alerts = manager.get_active_alerts()
    if status:
        alerts = [a for a in alerts if a.status == status]
    return APIResponse(data=[a.model_dump() for a in alerts])


@router.post("/alerts/{alert_id}/acknowledge")
async def acknowledge_alert(
    alert_id: str,
    user_id: str,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    alert = manager.acknowledge_alert(alert_id, user_id)
    if not alert:
        raise HTTPException(status_code=404, detail="Alert not found")
    return APIResponse(data=alert.model_dump())


@router.post("/metrics/record")
async def record_metric(
    request: RecordMetricRequest,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    manager.record_metric(request.metric, request.value, request.tags)
    return APIResponse(data={"recorded": True})


@router.post("/metrics/batch")
async def record_metrics_batch(
    requests: List[RecordMetricRequest],
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    for req in requests:
        manager.record_metric(req.metric, req.value, req.tags)
    return APIResponse(data={"recorded_count": len(requests)})


@router.get("/metrics/query")
async def query_metric(
    metric: str,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    aggregated = manager.metric_store.get_aggregated(metric)
    return APIResponse(data=aggregated)


@router.get("/notifications/channels")
async def list_notification_channels(
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    return APIResponse(data=[c.model_dump() for c in manager.notification_service.list_channels()])


@router.post("/notifications/channels", status_code=201)
async def create_notification_channel(
    channel: NotificationChannel,
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    manager.notification_service.register_channel(channel)
    return APIResponse(code=201, data={"channel_id": channel.channel_id})


@router.post("/alerts/evaluate")
async def evaluate_alerts(
    manager: AlertManager = Depends(get_alert_manager),
) -> APIResponse:
    events = await manager.evaluate_all()
    return APIResponse(data={"evaluated": len(events), "events": [e.model_dump() for e in events]})
