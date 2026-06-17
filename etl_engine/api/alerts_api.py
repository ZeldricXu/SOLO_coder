import uuid
from datetime import datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from etl_engine.db.session import get_session
from etl_engine.quality.rules import QualityRule

router = APIRouter(prefix="/api/alerts", tags=["alerts"])


class AlertRuleCreate(BaseModel):
    alert_type: Literal["task_failure", "quality_degradation", "sla_timeout"]
    channels: list[str]
    min_severity: str = "warning"
    cooldown_minutes: int = 15
    enabled: bool = True


class AlertRuleUpdate(BaseModel):
    alert_type: Literal["task_failure", "quality_degradation", "sla_timeout"] | None = None
    channels: list[str] | None = None
    min_severity: str | None = None
    cooldown_minutes: int | None = None
    enabled: bool | None = None


class AlertRuleResponse(BaseModel):
    id: uuid.UUID
    alert_type: str
    channels: list[str]
    min_severity: str
    cooldown_minutes: int
    enabled: bool
    created_at: datetime


class ChannelInfo(BaseModel):
    name: str
    type: str
    configured: bool


class TestAlertRequest(BaseModel):
    channel: str
    alert_type: Literal["task_failure", "quality_degradation", "sla_timeout"] = "task_failure"
    severity: Literal["info", "warning", "error", "critical"] = "warning"
    message: str = "Test alert from ETL engine"


class TestAlertResponse(BaseModel):
    channel: str
    success: bool
    message: str


_stored_rules: dict[uuid.UUID, dict[str, Any]] = {}

_channel_configs: dict[str, dict[str, Any]] = {}


@router.get("/rules", response_model=list[AlertRuleResponse])
async def list_alert_rules(
    session: AsyncSession = Depends(get_session),
):
    return [
        AlertRuleResponse(
            id=rule_id,
            alert_type=data["alert_type"],
            channels=data["channels"],
            min_severity=data["min_severity"],
            cooldown_minutes=data["cooldown_minutes"],
            enabled=data["enabled"],
            created_at=data["created_at"],
        )
        for rule_id, data in _stored_rules.items()
    ]


@router.post("/rules", response_model=AlertRuleResponse, status_code=201)
async def create_alert_rule(
    body: AlertRuleCreate,
    session: AsyncSession = Depends(get_session),
):
    rule_id = uuid.uuid4()
    now = datetime.utcnow()
    _stored_rules[rule_id] = {
        "alert_type": body.alert_type,
        "channels": body.channels,
        "min_severity": body.min_severity,
        "cooldown_minutes": body.cooldown_minutes,
        "enabled": body.enabled,
        "created_at": now,
    }
    return AlertRuleResponse(
        id=rule_id,
        alert_type=body.alert_type,
        channels=body.channels,
        min_severity=body.min_severity,
        cooldown_minutes=body.cooldown_minutes,
        enabled=body.enabled,
        created_at=now,
    )


@router.put("/rules/{rule_id}", response_model=AlertRuleResponse)
async def update_alert_rule(
    rule_id: uuid.UUID,
    body: AlertRuleUpdate,
    session: AsyncSession = Depends(get_session),
):
    data = _stored_rules.get(rule_id)
    if data is None:
        raise HTTPException(status_code=404, detail="Alert rule not found")

    update_data = body.model_dump(exclude_unset=True)
    data.update(update_data)

    return AlertRuleResponse(
        id=rule_id,
        alert_type=data["alert_type"],
        channels=data["channels"],
        min_severity=data["min_severity"],
        cooldown_minutes=data["cooldown_minutes"],
        enabled=data["enabled"],
        created_at=data["created_at"],
    )


@router.delete("/rules/{rule_id}", status_code=204)
async def delete_alert_rule(
    rule_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    if rule_id not in _stored_rules:
        raise HTTPException(status_code=404, detail="Alert rule not found")
    del _stored_rules[rule_id]


@router.get("/channels", response_model=list[ChannelInfo])
async def list_channels(
    session: AsyncSession = Depends(get_session),
):
    channels: list[ChannelInfo] = []
    for name, config in _channel_configs.items():
        channel_type = config.get("type", "unknown")
        channels.append(ChannelInfo(name=name, type=channel_type, configured=True))
    return channels


@router.post("/test", response_model=TestAlertResponse)
async def send_test_alert(
    body: TestAlertRequest,
    session: AsyncSession = Depends(get_session),
):
    channel_config = _channel_configs.get(body.channel)
    if channel_config is None:
        return TestAlertResponse(
            channel=body.channel,
            success=False,
            message=f"Channel '{body.channel}' not configured",
        )

    try:
        from etl_engine.alerts.channels import Alert
        from etl_engine.connectors import get_source

        alert = Alert(
            alert_type=body.alert_type,
            severity=body.severity,
            pipeline_name="test-pipeline",
            message=body.message,
        )

        channel_type = channel_config.get("type", "")
        if channel_type == "email":
            from etl_engine.alerts.channels import EmailChannel
            channel = EmailChannel(channel_config)
        elif channel_type == "slack":
            from etl_engine.alerts.channels import SlackChannel
            channel = SlackChannel(channel_config)
        elif channel_type == "pagerduty":
            from etl_engine.alerts.channels import PagerDutyChannel
            channel = PagerDutyChannel(channel_config)
        else:
            return TestAlertResponse(
                channel=body.channel,
                success=False,
                message=f"Unknown channel type: {channel_type}",
            )

        success = await channel.send(alert)
        return TestAlertResponse(
            channel=body.channel,
            success=success,
            message="Test alert sent successfully" if success else "Test alert failed",
        )
    except Exception as exc:
        return TestAlertResponse(
            channel=body.channel,
            success=False,
            message=str(exc),
        )
