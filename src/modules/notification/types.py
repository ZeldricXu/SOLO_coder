from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional, Union
from enum import Enum
from datetime import datetime


class NotificationChannel(str, Enum):
    EMAIL = "email"
    SMS = "sms"
    WECHAT = "wechat"
    DINGTALK = "dingtalk"
    SLACK = "slack"
    WEBHOOK = "webhook"
    IN_APP = "in_app"


class NotificationStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    SENT = "sent"
    DELIVERED = "delivered"
    FAILED = "failed"
    READ = "read"


class NotificationPriority(int, Enum):
    LOW = 1
    NORMAL = 2
    HIGH = 3
    URGENT = 4


class TemplateType(str, Enum):
    TEXT = "text"
    HTML = "html"
    MARKDOWN = "markdown"
    RICH = "rich"


class NotificationTemplate(BaseModel):
    template_id: Optional[str] = None
    name: str
    description: str = ""
    type: TemplateType = TemplateType.TEXT
    channels: List[NotificationChannel] = Field(default_factory=list)
    subject: str = ""
    content: str = ""
    variables: List[str] = Field(default_factory=list)
    default_variables: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class NotificationRequest(BaseModel):
    template_id: Optional[str] = None
    channel: NotificationChannel
    recipients: List[str]
    subject: Optional[str] = None
    content: Optional[str] = None
    variables: Dict[str, Any] = Field(default_factory=dict)
    priority: NotificationPriority = NotificationPriority.NORMAL
    scheduled_at: Optional[datetime] = None
    expires_at: Optional[datetime] = None
    callback_url: Optional[str] = None


class Notification(BaseModel):
    notification_id: Optional[str] = None
    template_id: Optional[str] = None
    channel: NotificationChannel
    recipients: List[str]
    subject: str = ""
    rendered_content: str = ""
    priority: NotificationPriority = NotificationPriority.NORMAL
    status: NotificationStatus = NotificationStatus.PENDING
    scheduled_at: Optional[datetime] = None
    sent_at: Optional[datetime] = None
    delivered_at: Optional[datetime] = None
    error_message: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 3
    created_at: datetime = Field(default_factory=datetime.utcnow)


class ChannelConfig(BaseModel):
    channel: NotificationChannel
    enabled: bool = True
    config: Dict[str, Any] = Field(default_factory=dict)
    rate_limit_per_minute: int = 100


class NotificationBatchRequest(BaseModel):
    notifications: List[NotificationRequest]
