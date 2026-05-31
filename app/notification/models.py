from pydantic import BaseModel, Field
from typing import Optional, Dict, Any
from datetime import datetime
from enum import IntEnum


class NotificationPriority(IntEnum):
    LOW = 1
    MEDIUM = 3
    HIGH = 7
    CRITICAL = 10


class NotificationChannel(str):
    EMAIL = "email"
    SLACK = "slack"
    WEBHOOK = "webhook"
    SMS = "sms"
    PUSH = "push"


class NotificationRequest(BaseModel):
    title: str = Field(..., max_length=200)
    content: str = Field(...)
    priority: int = Field(default=5, ge=1, le=10)
    channel: str = Field(default="email")
    recipient: Optional[str] = None
    deduplication_key: Optional[str] = None
    ttl_seconds: Optional[int] = Field(default=300, ge=0)
    metadata: Optional[Dict[str, Any]] = None


class NotificationResponse(BaseModel):
    notification_id: str
    status: str
    message: Optional[str] = None
    sent_at: Optional[datetime] = None
    suppressed: bool = False
    suppression_reason: Optional[str] = None


class SuppressionRule(BaseModel):
    rule_id: str
    name: str
    enabled: bool = True
    priority_threshold: Optional[int] = None
    channel: Optional[str] = None
    time_window_seconds: int = 60
    max_count: int = 10
    pattern: Optional[str] = None
