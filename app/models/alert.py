from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import List, Optional, Any
from uuid import uuid4


class OperatorType(str, Enum):
    GREATER_THAN = "greater_than"
    LESS_THAN = "less_than"
    GREATER_OR_EQUAL = "greater_or_equal"
    LESS_OR_EQUAL = "less_or_equal"
    EQUAL = "equal"
    NOT_EQUAL = "not_equal"


class AlertSeverity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


class AlertStatus(str, Enum):
    TRIGGERED = "triggered"
    RESOLVED = "resolved"
    ACKNOWLEDGED = "acknowledged"
    SILENCED = "silenced"


@dataclass
class AlertRule:
    rule_id: str
    metric_type: str
    threshold: float
    operator: OperatorType
    duration: int
    severity: AlertSeverity
    notify_channels: List[str]
    silence_period: int = 300
    enabled: bool = True
    description: str = ""
    server_filter: Optional[List[str]] = None
    
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)
    
    def evaluate(self, value: float) -> bool:
        op = self.operator
        if op == OperatorType.GREATER_THAN:
            return value > self.threshold
        elif op == OperatorType.LESS_THAN:
            return value < self.threshold
        elif op == OperatorType.GREATER_OR_EQUAL:
            return value >= self.threshold
        elif op == OperatorType.LESS_OR_EQUAL:
            return value <= self.threshold
        elif op == OperatorType.EQUAL:
            return value == self.threshold
        elif op == OperatorType.NOT_EQUAL:
            return value != self.threshold
        return False
    
    def matches_server(self, server_id: str) -> bool:
        if self.server_filter is None or len(self.server_filter) == 0:
            return True
        return server_id in self.server_filter
    
    def to_dict(self) -> dict:
        return {
            "rule_id": self.rule_id,
            "metric_type": self.metric_type,
            "threshold": self.threshold,
            "operator": self.operator.value if isinstance(self.operator, Enum) else self.operator,
            "duration": self.duration,
            "severity": self.severity.value if isinstance(self.severity, Enum) else self.severity,
            "notify_channels": self.notify_channels,
            "silence_period": self.silence_period,
            "enabled": self.enabled,
            "description": self.description,
            "server_filter": self.server_filter,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None
        }
    
    @classmethod
    def from_dict(cls, data: dict) -> 'AlertRule':
        operator = data.get('operator')
        if isinstance(operator, str):
            operator = OperatorType(operator)
        
        severity = data.get('severity')
        if isinstance(severity, str):
            severity = AlertSeverity(severity)
        
        created_at = data.get('created_at')
        if isinstance(created_at, str):
            from dateutil.parser import parse
            created_at = parse(created_at)
        
        updated_at = data.get('updated_at')
        if isinstance(updated_at, str):
            from dateutil.parser import parse
            updated_at = parse(updated_at)
        
        return cls(
            rule_id=data['rule_id'],
            metric_type=data['metric_type'],
            threshold=data['threshold'],
            operator=operator,
            duration=data['duration'],
            severity=severity,
            notify_channels=data['notify_channels'],
            silence_period=data.get('silence_period', 300),
            enabled=data.get('enabled', True),
            description=data.get('description', ''),
            server_filter=data.get('server_filter'),
            created_at=created_at,
            updated_at=updated_at
        )


@dataclass
class AlertEvent:
    alert_id: str
    rule_id: str
    server_id: str
    metric_type: str
    metric_value: float
    threshold: float
    operator: str
    status: AlertStatus
    severity: AlertSeverity
    
    triggered_at: datetime = field(default_factory=datetime.utcnow)
    resolved_at: Optional[datetime] = None
    acknowledged_at: Optional[datetime] = None
    
    notify_channels: List[str] = field(default_factory=list)
    notify_status: str = "pending"
    notify_error: Optional[str] = None
    
    message: str = ""
    details: dict = field(default_factory=dict)
    
    def to_dict(self) -> dict:
        return {
            "alert_id": self.alert_id,
            "rule_id": self.rule_id,
            "server_id": self.server_id,
            "metric_type": self.metric_type,
            "metric_value": self.metric_value,
            "threshold": self.threshold,
            "operator": self.operator,
            "status": self.status.value if isinstance(self.status, Enum) else self.status,
            "severity": self.severity.value if isinstance(self.severity, Enum) else self.severity,
            "triggered_at": self.triggered_at.isoformat() if self.triggered_at else None,
            "resolved_at": self.resolved_at.isoformat() if self.resolved_at else None,
            "acknowledged_at": self.acknowledged_at.isoformat() if self.acknowledged_at else None,
            "notify_channels": self.notify_channels,
            "notify_status": self.notify_status,
            "notify_error": self.notify_error,
            "message": self.message,
            "details": self.details
        }
    
    @classmethod
    def from_dict(cls, data: dict) -> 'AlertEvent':
        status = data.get('status')
        if isinstance(status, str):
            status = AlertStatus(status)
        
        severity = data.get('severity')
        if isinstance(severity, str):
            severity = AlertSeverity(severity)
        
        def parse_dt(dt_str):
            if dt_str is None:
                return None
            if isinstance(dt_str, str):
                from dateutil.parser import parse
                return parse(dt_str)
            return dt_str
        
        return cls(
            alert_id=data['alert_id'],
            rule_id=data['rule_id'],
            server_id=data['server_id'],
            metric_type=data['metric_type'],
            metric_value=data['metric_value'],
            threshold=data['threshold'],
            operator=data['operator'],
            status=status,
            severity=severity,
            triggered_at=parse_dt(data.get('triggered_at')),
            resolved_at=parse_dt(data.get('resolved_at')),
            acknowledged_at=parse_dt(data.get('acknowledged_at')),
            notify_channels=data.get('notify_channels', []),
            notify_status=data.get('notify_status', 'pending'),
            notify_error=data.get('notify_error'),
            message=data.get('message', ''),
            details=data.get('details', {})
        )
    
    @classmethod
    def create_from_metric(cls, metric: 'Metric', rule: AlertRule, message: str = "") -> 'AlertEvent':
        return cls(
            alert_id=f"alert_{uuid4().hex[:8]}",
            rule_id=rule.rule_id,
            server_id=metric.server_id,
            metric_type=metric.metric_type,
            metric_value=metric.value,
            threshold=rule.threshold,
            operator=rule.operator.value if isinstance(rule.operator, Enum) else rule.operator,
            status=AlertStatus.TRIGGERED,
            severity=rule.severity,
            notify_channels=rule.notify_channels,
            message=message or f"{metric.metric_type} {metric.value} exceeded threshold {rule.threshold}",
            details={
                "metric_id": metric.metric_id,
                "unit": metric.unit,
                "collected_at": metric.collected_at.isoformat() if metric.collected_at else None
            }
        )
