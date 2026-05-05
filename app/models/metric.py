from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Optional


class MetricType(str, Enum):
    CPU_USAGE = "cpu_usage"
    MEMORY_USAGE = "memory_usage"
    DISK_USAGE = "disk_usage"
    NETWORK_IO = "network_io"
    NETWORK_IN = "network_in"
    NETWORK_OUT = "network_out"


@dataclass
class Metric:
    metric_id: str
    server_id: str
    metric_type: str
    value: float
    unit: str
    collected_at: datetime = field(default_factory=datetime.utcnow)
    
    tags: dict = field(default_factory=dict)
    fields: dict = field(default_factory=dict)
    
    def to_influx_point(self) -> dict:
        return {
            "measurement": "system_metrics",
            "tags": {
                "server_id": self.server_id,
                "metric_type": self.metric_type,
                "unit": self.unit,
                **self.tags
            },
            "fields": {
                "value": self.value,
                "metric_id": self.metric_id,
                **self.fields
            },
            "time": self.collected_at.isoformat() if self.collected_at else datetime.utcnow().isoformat()
        }
    
    @classmethod
    def from_influx_point(cls, point: dict) -> 'Metric':
        values = point.get('values', {})
        tags = point.get('tags', {})
        
        collected_at = values.get('_time')
        if isinstance(collected_at, str):
            from dateutil.parser import parse
            collected_at = parse(collected_at)
        
        return cls(
            metric_id=values.get('metric_id', f"{tags.get('server_id', 'unknown')}_{tags.get('metric_type', 'unknown')}"),
            server_id=tags.get('server_id', 'unknown'),
            metric_type=tags.get('metric_type', 'unknown'),
            value=values.get('_value', 0.0),
            unit=tags.get('unit', 'unknown'),
            collected_at=collected_at,
            tags={k: v for k, v in tags.items() if k not in ['server_id', 'metric_type', 'unit']},
            fields={k: v for k, v in values.items() if k not in ['_time', '_value', 'metric_id']}
        )
    
    def to_dict(self) -> dict:
        return {
            "metric_id": self.metric_id,
            "server_id": self.server_id,
            "metric_type": self.metric_type,
            "value": self.value,
            "unit": self.unit,
            "collected_at": self.collected_at.isoformat() if self.collected_at else None,
            "tags": self.tags,
            "fields": self.fields
        }
    
    @classmethod
    def from_dict(cls, data: dict) -> 'Metric':
        collected_at = data.get('collected_at')
        if isinstance(collected_at, str):
            from dateutil.parser import parse
            collected_at = parse(collected_at)
        
        return cls(
            metric_id=data['metric_id'],
            server_id=data['server_id'],
            metric_type=data['metric_type'],
            value=data['value'],
            unit=data['unit'],
            collected_at=collected_at,
            tags=data.get('tags', {}),
            fields=data.get('fields', {})
        )
