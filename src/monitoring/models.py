from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from src.common.models import generate_id, utc_now


class AlertSeverity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


class AlertStatus(str, Enum):
    ACTIVE = "active"
    ACKNOWLEDGED = "acknowledged"
    RESOLVED = "resolved"
    SUPPRESSED = "suppressed"


class AlertConditionOperator(str, Enum):
    GREATER_THAN = ">"
    LESS_THAN = "<"
    GREATER_EQUAL = ">="
    LESS_EQUAL = "<="
    EQUAL = "=="
    NOT_EQUAL = "!="
    IN = "in"
    NOT_IN = "not_in"


class NotificationChannelType(str, Enum):
    EMAIL = "email"
    SLACK = "slack"
    WEBHOOK = "webhook"
    PAGERDUTY = "pagerduty"
    SMS = "sms"


class AlertCondition(BaseModel):
    metric: str
    operator: AlertConditionOperator
    threshold: Any
    duration: int = 60
    window_size: int = 5


class NotificationChannel(BaseModel):
    channel_id: str = Field(default_factory=lambda: generate_id("chn"))
    type: NotificationChannelType
    name: str
    config: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True


class AlertRule(BaseModel):
    rule_id: str = Field(default_factory=lambda: generate_id("rule"))
    name: str
    description: str = ""
    conditions: List[AlertCondition]
    severity: AlertSeverity = AlertSeverity.WARNING
    notification_channels: List[str] = Field(default_factory=list)
    evaluation_interval: int = 60
    enabled: bool = True
    labels: Dict[str, str] = Field(default_factory=dict)
    created_by: Optional[str] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class AlertEvent(BaseModel):
    alert_id: str = Field(default_factory=lambda: generate_id("alert"))
    rule_id: str
    rule_name: str
    severity: AlertSeverity
    status: AlertStatus = AlertStatus.ACTIVE
    metric: str
    value: Any
    threshold: Any
    operator: str
    message: str
    labels: Dict[str, str] = Field(default_factory=dict)
    triggered_at: datetime = Field(default_factory=utc_now)
    acknowledged_at: Optional[datetime] = None
    resolved_at: Optional[datetime] = None
    acknowledged_by: Optional[str] = None
    resolved_by: Optional[str] = None


class MetricPoint(BaseModel):
    timestamp: datetime = Field(default_factory=utc_now)
    metric: str
    value: float
    tags: Dict[str, str] = Field(default_factory=dict)


class AlertNotification(BaseModel):
    notification_id: str = Field(default_factory=lambda: generate_id("notif"))
    alert_id: str
    channel_id: str
    channel_type: NotificationChannelType
    status: str = "sent"
    sent_at: datetime = Field(default_factory=utc_now)
    error: Optional[str] = None
