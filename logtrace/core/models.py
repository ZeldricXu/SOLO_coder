from dataclasses import dataclass, field
from datetime import datetime
from typing import List, Optional, Dict, Any
import uuid


@dataclass
class LogRecord:
    log_id: str
    node_id: str
    log_level: str
    log_source: str
    log_content: str
    timestamp: datetime
    tags: List[str] = field(default_factory=list)
    is_exception: bool = False
    exception_type: Optional[str] = None
    matched_rule_id: Optional[str] = None
    context_id: Optional[str] = None

    @classmethod
    def create(
        cls,
        node_id: str,
        log_level: str,
        log_source: str,
        log_content: str,
        timestamp: datetime = None,
        tags: List[str] = None
    ) -> 'LogRecord':
        return cls(
            log_id=str(uuid.uuid4()),
            node_id=node_id,
            log_level=log_level.lower(),
            log_source=log_source,
            log_content=log_content,
            timestamp=timestamp or datetime.utcnow(),
            tags=tags or []
        )

    def to_dict(self) -> dict:
        return {
            'log_id': self.log_id,
            'node_id': self.node_id,
            'log_level': self.log_level,
            'log_source': self.log_source,
            'log_content': self.log_content,
            'timestamp': self.timestamp.isoformat() + 'Z',
            'tags': self.tags,
            'is_exception': self.is_exception,
            'exception_type': self.exception_type,
            'matched_rule_id': self.matched_rule_id,
            'context_id': self.context_id
        }


@dataclass
class ExceptionContext:
    context_id: str
    exception_log_id: str
    node_id: str
    rule_id: str
    rule_name: str
    exception_time: datetime
    context_logs_before: List[Dict[str, Any]] = field(default_factory=list)
    context_logs_after: List[Dict[str, Any]] = field(default_factory=list)
    before_window_seconds: int = 5
    after_window_seconds: int = 5

    @classmethod
    def create(
        cls,
        exception_log_id: str,
        node_id: str,
        rule_id: str,
        rule_name: str,
        exception_time: datetime,
        before_window_seconds: int = 5,
        after_window_seconds: int = 5
    ) -> 'ExceptionContext':
        return cls(
            context_id=str(uuid.uuid4()),
            exception_log_id=exception_log_id,
            node_id=node_id,
            rule_id=rule_id,
            rule_name=rule_name,
            exception_time=exception_time,
            before_window_seconds=before_window_seconds,
            after_window_seconds=after_window_seconds
        )

    def add_context_before(self, log_dict: Dict[str, Any]):
        self.context_logs_before.append(log_dict)

    def add_context_after(self, log_dict: Dict[str, Any]):
        self.context_logs_after.append(log_dict)

    def to_dict(self) -> dict:
        return {
            'context_id': self.context_id,
            'exception_log_id': self.exception_log_id,
            'node_id': self.node_id,
            'rule_id': self.rule_id,
            'rule_name': self.rule_name,
            'exception_time': self.exception_time.isoformat() + 'Z',
            'context_logs_before': self.context_logs_before,
            'context_logs_after': self.context_logs_after,
            'before_window_seconds': self.before_window_seconds,
            'after_window_seconds': self.after_window_seconds
        }


@dataclass
class NodeConfig:
    node_id: str
    node_name: str
    node_address: str
    log_path: str
    collect_mode: str
    collect_interval: int
    enabled: bool

    @classmethod
    def from_dict(cls, data: dict) -> 'NodeConfig':
        return cls(
            node_id=data['node_id'],
            node_name=data['node_name'],
            node_address=data['node_address'],
            log_path=data['log_path'],
            collect_mode=data.get('collect_mode', 'realtime'),
            collect_interval=data.get('collect_interval', 10),
            enabled=data.get('enabled', True)
        )


@dataclass
class ExceptionRule:
    rule_id: str
    rule_name: str
    pattern: str
    log_level_filter: List[str]
    severity: str
    alert_enabled: bool
    alert_threshold: int
    context_before_seconds: int = 5
    context_after_seconds: int = 5

    @classmethod
    def from_dict(cls, data: dict) -> 'ExceptionRule':
        return cls(
            rule_id=data['rule_id'],
            rule_name=data['rule_name'],
            pattern=data['pattern'],
            log_level_filter=[level.lower() for level in data.get('log_level_filter', [])],
            severity=data.get('severity', 'medium'),
            alert_enabled=data.get('alert_enabled', True),
            alert_threshold=data.get('alert_threshold', 10),
            context_before_seconds=data.get('context_before_seconds', 5),
            context_after_seconds=data.get('context_after_seconds', 5)
        )


@dataclass
class AlertRecord:
    alert_id: str
    rule_id: str
    node_id: str
    exception_count: int
    alert_time: datetime
    status: str
    notify_channels: List[str]
    context_id: Optional[str] = None

    @classmethod
    def create(
        cls,
        rule_id: str,
        node_id: str,
        exception_count: int,
        notify_channels: List[str],
        context_id: Optional[str] = None
    ) -> 'AlertRecord':
        return cls(
            alert_id=str(uuid.uuid4()),
            rule_id=rule_id,
            node_id=node_id,
            exception_count=exception_count,
            alert_time=datetime.utcnow(),
            status='triggered',
            notify_channels=notify_channels,
            context_id=context_id
        )

    def to_dict(self) -> dict:
        return {
            'alert_id': self.alert_id,
            'rule_id': self.rule_id,
            'node_id': self.node_id,
            'exception_count': self.exception_count,
            'alert_time': self.alert_time.isoformat() + 'Z',
            'status': self.status,
            'notify_channels': self.notify_channels,
            'context_id': self.context_id
        }


@dataclass
class LogStats:
    stat_id: str
    stat_date: str
    node_id: str
    total_logs: int
    error_count: int
    warning_count: int
    info_count: int

    @classmethod
    def create(cls, node_id: str, stat_date: str = None) -> 'LogStats':
        return cls(
            stat_id=str(uuid.uuid4()),
            stat_date=stat_date or datetime.utcnow().strftime('%Y-%m-%d'),
            node_id=node_id,
            total_logs=0,
            error_count=0,
            warning_count=0,
            info_count=0
        )

    def update(self, log_level: str):
        self.total_logs += 1
        if log_level in ['error', 'fatal']:
            self.error_count += 1
        elif log_level == 'warning':
            self.warning_count += 1
        else:
            self.info_count += 1

    def to_dict(self) -> dict:
        return {
            'stat_id': self.stat_id,
            'stat_date': self.stat_date,
            'node_id': self.node_id,
            'total_logs': self.total_logs,
            'error_count': self.error_count,
            'warning_count': self.warning_count,
            'info_count': self.info_count
        }
