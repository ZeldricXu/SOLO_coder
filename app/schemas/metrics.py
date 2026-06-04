from datetime import datetime
from typing import Optional, List, Dict, Any
from pydantic import BaseModel


class MetricPoint(BaseModel):
    timestamp: datetime
    value: float


class MetricData(BaseModel):
    metric: str
    labels: Dict[str, str]
    values: List[MetricPoint]


class MetricsQuery(BaseModel):
    metric_name: str
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    step: int = 60
    filters: Optional[Dict[str, str]] = None
