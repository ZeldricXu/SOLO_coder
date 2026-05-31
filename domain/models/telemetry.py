from datetime import datetime
from typing import Dict, Any, Optional, List
from pydantic import BaseModel, Field


class TelemetryData(BaseModel):
    device_id: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    data: Dict[str, Any] = Field(default_factory=dict)
    quality: int = 100
    metadata: Dict[str, Any] = Field(default_factory=dict)

    def get_value(self, key: str, default: Any = None) -> Any:
        return self.data.get(key, default)

    def set_value(self, key: str, value: Any) -> None:
        self.data[key] = value

    def validate(self) -> bool:
        return len(self.data) > 0 and self.timestamp is not None


class AggregatedData(BaseModel):
    device_id: str
    metric: str
    aggregation_type: str
    period_start: datetime
    period_end: datetime
    value: float
    count: int
    min_value: Optional[float] = None
    max_value: Optional[float] = None
    sum_value: Optional[float] = None
    avg_value: Optional[float] = None
    std_dev: Optional[float] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)


class DataPoint(BaseModel):
    timestamp: datetime
    value: Any
    quality: int = 100


class TimeSeriesData(BaseModel):
    device_id: str
    metric: str
    data_points: List[DataPoint] = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)

    def add_data_point(self, timestamp: datetime, value: Any, quality: int = 100) -> None:
        self.data_points.append(DataPoint(timestamp=timestamp, value=value, quality=quality))

    def get_values(self) -> List[Any]:
        return [dp.value for dp in self.data_points]

    def get_timestamps(self) -> List[datetime]:
        return [dp.timestamp for dp in self.data_points]
