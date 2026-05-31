from datetime import datetime
from typing import Dict, Any, Optional
from pydantic import BaseModel, Field


class MetricsSnapshot(BaseModel):
    snapshot_id: str = Field(..., description="Snapshot identifier")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="Snapshot timestamp")
    metrics: Dict[str, float] = Field(default_factory=dict, description="Metrics data")
    dimensions: Dict[str, str] = Field(default_factory=dict, description="Metric dimensions")
    tags: Dict[str, str] = Field(default_factory=dict, description="Additional tags")

    class Config:
        from_attributes = True


class MetricAlert(BaseModel):
    alert_id: str = Field(..., description="Alert identifier")
    metric_name: str = Field(..., description="Metric name")
    threshold: float = Field(..., description="Alert threshold")
    operator: str = Field(default="gt", description="Comparison operator (gt, lt, gte, lte, eq)")
    severity: str = Field(default="warning", description="Alert severity (info, warning, critical)")
    triggered: bool = Field(default=False, description="Whether alert is triggered")
    triggered_at: Optional[datetime] = Field(default=None, description="When alert was triggered")
    last_value: Optional[float] = Field(default=None, description="Last metric value")
    notification_channels: list[str] = Field(default_factory=list, description="Notification channels")
