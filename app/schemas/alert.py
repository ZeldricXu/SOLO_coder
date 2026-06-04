from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel


class AlertRuleCreate(BaseModel):
    name: str
    level: str
    condition_expr: str
    window_seconds: int = 300
    threshold: Optional[float] = None
    notification_channels: str
    enabled: bool = True


class AlertRuleUpdate(BaseModel):
    name: Optional[str] = None
    level: Optional[str] = None
    condition_expr: Optional[str] = None
    window_seconds: Optional[int] = None
    threshold: Optional[float] = None
    notification_channels: Optional[str] = None
    enabled: Optional[bool] = None


class AlertAck(BaseModel):
    user_id: int
    note: Optional[str] = None


class AlertTrigger(BaseModel):
    rule_id: int
    service_id: Optional[int] = None
    message: str
