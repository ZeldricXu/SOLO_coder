import json
import uuid
import hashlib
import time
import asyncio
from abc import ABC, abstractmethod
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, AsyncIterator, Callable, TypeVar, Generic, Tuple
from enum import Enum
from dataclasses import dataclass, field
from collections import defaultdict, deque
from contextlib import asynccontextmanager

from .logging_module import get_logger
from .config_module import get_app_config
from .storage_module import StorageManager, get_storage_manager
from .event_store import EventStore, EventType, Event, get_event_store

logger = get_logger(__name__)

T = TypeVar('T')


@dataclass
class TimingMetric:
    operation: str
    duration_ms: float
    timestamp: datetime
    success: bool
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class MetricsSummary:
    total_requests: int = 0
    total_errors: int = 0
    avg_duration_ms: float = 0.0
    p50_duration_ms: float = 0.0
    p95_duration_ms: float = 0.0
    p99_duration_ms: float = 0.0
    max_duration_ms: float = 0.0
    min_duration_ms: float = 0.0
    error_rate: float = 0.0
    operations: Dict[str, Dict[str, Any]] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_requests": self.total_requests,
            "total_errors": self.total_errors,
            "avg_duration_ms": round(self.avg_duration_ms, 2),
            "p50_duration_ms": round(self.p50_duration_ms, 2),
            "p95_duration_ms": round(self.p95_duration_ms, 2),
            "p99_duration_ms": round(self.p99_duration_ms, 2),
            "max_duration_ms": round(self.max_duration_ms, 2),
            "min_duration_ms": round(self.min_duration_ms, 2),
            "error_rate": round(self.error_rate, 4),
            "operations": self.operations,
        }


class PrometheusMetricsExporter:
    def __init__(self):
        self._enabled = False
        self._registry = None
        self._metrics = {}
        self._init_prometheus()

    def _init_prometheus(self):
        try:
            from prometheus_client import Counter, Histogram, Gauge
            self._enabled = True
            self._registry = {
                "commands_executed": Counter(
                    'audit_commands_executed_total',
                    'Total number of commands executed',
                    ['command_type', 'status']
                ),
                "command_duration": Histogram(
                    'audit_command_duration_seconds',
                    'Command execution duration in seconds',
                    ['command_type'],
                    buckets=[0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0]
                ),
                "audit_logs_written": Counter(
                    'audit_logs_written_total',
                    'Total audit logs written',
                    ['severity', 'success']
                ),
                "report_generation_duration": Histogram(
                    'audit_report_generation_seconds',
                    'Compliance report generation duration',
                ),
                "active_commands": Gauge(
                    'audit_active_commands',
                    'Number of commands currently executing',
                ),
            }
            logger.info("Prometheus metrics enabled")
        except ImportError:
            logger.info("Prometheus client not available, metrics disabled")
            self._enabled = False

    @property
    def is_enabled(self) -> bool:
        return self._enabled

    def observe_command(self, command_type: str, status: str, duration: float):
        if not self._enabled:
            self._registry["commands_executed"].labels(
                command_type=command_type,
                status=status
            ).inc()
            self._registry["command_duration"].labels(
                command_type=command_type
            ).observe(duration)

    def observe_audit_log(self, severity: str, success: bool):
        if not self._enabled:
            self._registry["audit_logs_written"].labels(
                severity=severity,
                success=str(success)
            ).inc()

    def observe_report_generation(self, duration: float):
        if not self._enabled:
            self._registry["report_generation_duration"].observe(duration)

    def set_active_commands(self, count: int):
        if not self._enabled:
            self._registry["active_commands"].set(count)


class MetricsCollector:
    def __init__(self, max_history: int = 10000):
        self._timings: deque[TimingMetric] = deque(maxlen=max_history)
        self._operation_stats: Dict[str, Dict[str, Any]] = defaultdict(lambda: {
            "count": 0, "errors": 0, "total_time": 0.0, "max_time": 0.0, "min_time": float('inf')
        })
        self._prometheus = PrometheusMetricsExporter()
        self._lock = None

    async def record(self, operation: str, duration_ms: float, success: bool, metadata: Optional[Dict[str, Any]] = None):
        metric = TimingMetric(
            operation=operation,
            duration_ms=duration_ms,
            timestamp=datetime.utcnow(),
            success=success,
            metadata=metadata or {},
        )
        self._timings.append(metric)

        stats = self._operation_stats[operation]
        stats["count"] += 1
        stats["total_time"] += duration_ms
        stats["max_time"] = max(stats["max_time"], duration_ms)
        stats["min_time"] = min(stats["min_time"], duration_ms)
        if not success:
            stats["errors"] += 1

    def get_summary(self, operation: Optional[str] = None) -> MetricsSummary:
        summary = MetricsSummary()

        timings = list(self._timings)
        if operation:
            timings = [t for t in timings if t.operation == operation]

        summary.total_requests = len(timings)
        if timings:
            durations = sorted([t.duration_ms for t in timings])
            summary.total_errors = sum(1 for t in timings if not t.success)
            summary.avg_duration_ms = sum(durations) / len(durations)
            summary.max_duration_ms = max(durations)
            summary.min_duration_ms = min(durations)
            summary.error_rate = summary.total_errors / summary.total_requests

            p50_idx = int(len(durations) * 0.5)
            p95_idx = int(len(durations) * 0.95)
            p99_idx = int(len(durations) * 0.99)

            summary.p50_duration_ms = durations[min(p50_idx, len(durations) - 1)]
            summary.p95_duration_ms = durations[min(p95_idx, len(durations) - 1)]
            summary.p99_duration_ms = durations[min(p99_idx, len(durations) - 1)]

        for op, stats in self._operation_stats.items():
            count = stats["count"]
            if count > 0:
                summary.operations[op] = {
                    "count": count,
                    "errors": stats["errors"],
                    "avg_duration_ms": stats["total_time"] / count,
                    "max_duration_ms": stats["max_time"],
                    "error_rate": stats["errors"] / count,
                }

        return summary

    def get_prometheus(self) -> PrometheusMetricsExporter:
        return self._prometheus

    def clear(self):
        self._timings.clear()
        self._operation_stats.clear()


class CommandStatus(str, Enum):
    PENDING = "pending"
    EXECUTING = "executing"
    COMPLETED = "completed"
    FAILED = "failed"
    ROLLBACK = "rollback"
    ROLLBACK_COMPLETED = "rollback_completed"


class CommandType(str, Enum):
    CREATE = "create"
    UPDATE = "update"
    DELETE = "delete"
    MIGRATE = "migrate"
    EXECUTE = "execute"
    BATCH = "batch"
    CUSTOM = "custom"


class AuditAction(str, Enum):
    LOGIN = "login"
    LOGOUT = "logout"
    ACCESS = "access"
    MODIFY = "modify"
    DELETE = "delete"
    EXPORT = "export"
    IMPORT = "import"
    CONFIG_CHANGE = "config_change"
    PERMISSION_CHANGE = "permission_change"
    API_CALL = "api_call"


class Severity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


@dataclass
class Command:
    command_id: str
    command_type: CommandType
    entity_id: Optional[str]
    payload: Dict[str, Any]
    status: CommandStatus = CommandStatus.PENDING
    created_at: datetime = field(default_factory=datetime.utcnow)
    executed_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    user_id: Optional[str] = None
    correlation_id: Optional[str] = None
    causation_id: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    retry_count: int = 0
    max_retries: int = 3

    def to_dict(self) -> Dict[str, Any]:
        return {
            "command_id": self.command_id,
            "command_type": self.command_type.value,
            "entity_id": self.entity_id,
            "payload": self.payload,
            "status": self.status.value,
            "created_at": self.created_at.isoformat(),
            "executed_at": self.executed_at.isoformat() if self.executed_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "result": self.result,
            "error_message": self.error_message,
            "user_id": self.user_id,
            "correlation_id": self.correlation_id,
            "causation_id": self.causation_id,
            "metadata": self.metadata,
            "retry_count": self.retry_count,
            "max_retries": self.max_retries,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Command':
        return cls(
            command_id=data["command_id"],
            command_type=CommandType(data["command_type"]),
            entity_id=data.get("entity_id"),
            payload=data["payload"],
            status=CommandStatus(data["status"]),
            created_at=datetime.fromisoformat(data["created_at"].replace('Z', '+00:00')),
            executed_at=datetime.fromisoformat(data["executed_at"].replace('Z', '+00:00')) if data.get("executed_at") else None,
            completed_at=datetime.fromisoformat(data["completed_at"].replace('Z', '+00:00')) if data.get("completed_at") else None,
            result=data.get("result"),
            error_message=data.get("error_message"),
            user_id=data.get("user_id"),
            correlation_id=data.get("correlation_id"),
            causation_id=data.get("causation_id"),
            metadata=data.get("metadata", {}),
            retry_count=data.get("retry_count", 0),
            max_retries=data.get("max_retries", 3),
        )


@dataclass
class AuditLogEntry:
    log_id: str
    action: AuditAction
    user_id: Optional[str]
    resource_type: Optional[str]
    resource_id: Optional[str]
    description: str
    severity: Severity = Severity.LOW
    timestamp: datetime = field(default_factory=datetime.utcnow)
    source_ip: Optional[str] = None
    user_agent: Optional[str] = None
    request_id: Optional[str] = None
    correlation_id: Optional[str] = None
    before_state: Optional[Dict[str, Any]] = None
    after_state: Optional[Dict[str, Any]] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    success: bool = True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "log_id": self.log_id,
            "action": self.action.value,
            "user_id": self.user_id,
            "resource_type": self.resource_type,
            "resource_id": self.resource_id,
            "description": self.description,
            "severity": self.severity.value,
            "timestamp": self.timestamp.isoformat(),
            "source_ip": self.source_ip,
            "user_agent": self.user_agent,
            "request_id": self.request_id,
            "correlation_id": self.correlation_id,
            "before_state": self.before_state,
            "after_state": self.after_state,
            "metadata": self.metadata,
            "success": self.success,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'AuditLogEntry':
        return cls(
            log_id=data["log_id"],
            action=AuditAction(data["action"]),
            user_id=data.get("user_id"),
            resource_type=data.get("resource_type"),
            resource_id=data.get("resource_id"),
            description=data["description"],
            severity=Severity(data.get("severity", Severity.LOW.value)),
            timestamp=datetime.fromisoformat(data["timestamp"].replace('Z', '+00:00')),
            source_ip=data.get("source_ip"),
            user_agent=data.get("user_agent"),
            request_id=data.get("request_id"),
            correlation_id=data.get("correlation_id"),
            before_state=data.get("before_state"),
            after_state=data.get("after_state"),
            metadata=data.get("metadata", {}),
            success=data.get("success", True),
        )


@dataclass
class ComplianceReport:
    report_id: str
    start_date: datetime
    end_date: datetime
    generated_at: datetime = field(default_factory=datetime.utcnow)
    total_events: int = 0
    high_severity_events: int = 0
    medium_severity_events: int = 0
    low_severity_events: int = 0
    failed_operations: int = 0
    user_activity: Dict[str, int] = field(default_factory=dict)
    action_distribution: Dict[str, int] = field(default_factory=dict)
    anomalies: List[Dict[str, Any]] = field(default_factory=list)
    summary: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "report_id": self.report_id,
            "start_date": self.start_date.isoformat(),
            "end_date": self.end_date.isoformat(),
            "generated_at": self.generated_at.isoformat(),
            "total_events": self.total_events,
            "high_severity_events": self.high_severity_events,
            "medium_severity_events": self.medium_severity_events,
            "low_severity_events": self.low_severity_events,
            "failed_operations": self.failed_operations,
            "user_activity": self.user_activity,
            "action_distribution": self.action_distribution,
            "anomalies": self.anomalies,
            "summary": self.summary,
        }


class CommandStore(ABC):
    @abstractmethod
    async def save_command(self, command: Command) -> None:
        pass

    @abstractmethod
    async def get_command(self, command_id: str) -> Optional[Command]:
        pass

    @abstractmethod
    async def update_command(self, command: Command) -> None:
        pass

    @abstractmethod
    async def list_commands(
        self,
        status: Optional[CommandStatus] = None,
        user_id: Optional[str] = None,
        entity_id: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[Command]:
        pass

    @abstractmethod
    async def get_commands_by_correlation(self, correlation_id: str) -> List[Command]:
        pass


class StorageCommandStore(CommandStore):
    def __init__(self, storage: StorageManager):
        self.storage = storage
        self._index_key = "commands/index.json"

    def _get_command_key(self, command_id: str) -> str:
        return f"commands/{command_id}.json"

    async def save_command(self, command: Command) -> None:
        key = self._get_command_key(command.command_id)
        await self.storage.save_data(key, command.to_dict())

        index = await self.storage.load_data(self._index_key) or []
        if command.command_id not in [e["command_id"] for e in index]:
            index.append({
                "command_id": command.command_id,
                "status": command.status.value,
                "entity_id": command.entity_id,
                "user_id": command.user_id,
                "correlation_id": command.correlation_id,
                "created_at": command.created_at.isoformat(),
            })
            await self.storage.save_data(self._index_key, index)

    async def get_command(self, command_id: str) -> Optional[Command]:
        key = self._get_command_key(command_id)
        data = await self.storage.load_data(key)
        return Command.from_dict(data) if data else None

    async def update_command(self, command: Command) -> None:
        command.updated_at = datetime.utcnow()
        key = self._get_command_key(command.command_id)
        await self.storage.save_data(key, command.to_dict())

        index = await self.storage.load_data(self._index_key) or []
        for entry in index:
            if entry["command_id"] == command.command_id:
                entry["status"] = command.status.value
                break
        await self.storage.save_data(self._index_key, index)

    async def list_commands(
        self,
        status: Optional[CommandStatus] = None,
        user_id: Optional[str] = None,
        entity_id: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[Command]:
        index = await self.storage.load_data(self._index_key) or []
        commands: List[Command] = []

        for entry in index:
            if status and entry["status"] != status.value:
                continue
            if user_id and entry.get("user_id") != user_id:
                continue
            if entity_id and entry.get("entity_id") != entity_id:
                continue

            cmd_time = datetime.fromisoformat(entry["created_at"].replace('Z', '+00:00'))
            if start_time and cmd_time < start_time:
                continue
            if end_time and cmd_time > end_time:
                continue

            command = await self.get_command(entry["command_id"])
            if command:
                commands.append(command)

            if len(commands) >= limit:
                break

        return commands

    async def get_commands_by_correlation(self, correlation_id: str) -> List[Command]:
        index = await self.storage.load_data(self._index_key) or []
        commands: List[Command] = []

        for entry in index:
            if entry.get("correlation_id") == correlation_id:
                command = await self.get_command(entry["command_id"])
                if command:
                    commands.append(command)

        return sorted(commands, key=lambda c: c.created_at)


class AuditLogStore(ABC):
    @abstractmethod
    async def append(self, entry: AuditLogEntry) -> None:
        pass

    @abstractmethod
    async def query(
        self,
        action: Optional[AuditAction] = None,
        user_id: Optional[str] = None,
        resource_type: Optional[str] = None,
        resource_id: Optional[str] = None,
        severity: Optional[Severity] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        success: Optional[bool] = None,
        limit: int = 1000,
    ) -> List[AuditLogEntry]:
        pass

    @abstractmethod
    async def generate_report(
        self,
        start_date: datetime,
        end_date: datetime,
        include_anomalies: bool = True,
    ) -> ComplianceReport:
        pass


class StorageAuditLogStore(AuditLogStore):
    def __init__(self, storage: StorageManager):
        self.storage = storage

    def _get_log_key(self, date: datetime) -> str:
        return f"audit/{date.strftime('%Y/%m/%d')}.jsonl"

    async def append(self, entry: AuditLogEntry) -> None:
        key = self._get_log_key(entry.timestamp)
        existing = await self.storage.load_data(key, deserialize=False) or b""
        line = json.dumps(entry.to_dict()) + '\n'
        new_content = existing + line.encode('utf-8')
        await self.storage.save_data(key, new_content, serialize=False)

    async def query(
        self,
        action: Optional[AuditAction] = None,
        user_id: Optional[str] = None,
        resource_type: Optional[str] = None,
        resource_id: Optional[str] = None,
        severity: Optional[Severity] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        success: Optional[bool] = None,
        limit: int = 1000,
    ) -> List[AuditLogEntry]:
        entries: List[AuditLogEntry] = []
        start = start_time or datetime.utcnow() - timedelta(days=30)
        end = end_time or datetime.utcnow()

        current = start.date()
        while current <= end.date() and len(entries) < limit:
            key = self._get_log_key(datetime.combine(current, datetime.min.time()))
            data = await self.storage.load_data(key, deserialize=False)

            if data:
                for line in data.decode('utf-8').strip().split('\n'):
                    if not line.strip():
                        continue
                    try:
                        entry_data = json.loads(line)
                        entry = AuditLogEntry.from_dict(entry_data)

                        if action and entry.action != action:
                            continue
                        if user_id and entry.user_id != user_id:
                            continue
                        if resource_type and entry.resource_type != resource_type:
                            continue
                        if resource_id and entry.resource_id != resource_id:
                            continue
                        if severity and entry.severity != severity:
                            continue
                        if start_time and entry.timestamp < start_time:
                            continue
                        if end_time and entry.timestamp > end_time:
                            continue
                        if success is not None and entry.success != success:
                            continue

                        entries.append(entry)
                        if len(entries) >= limit:
                            break
                    except (json.JSONDecodeError, ValueError):
                        continue

            current += timedelta(days=1)

        return entries

    async def generate_report(
        self,
        start_date: datetime,
        end_date: datetime,
        include_anomalies: bool = True,
    ) -> ComplianceReport:
        entries = await self.query(
            start_time=start_date,
            end_time=end_date,
            limit=100000,
        )

        report = ComplianceReport(
            report_id=str(uuid.uuid4()),
            start_date=start_date,
            end_date=end_date,
            total_events=len(entries),
        )

        action_counts: Dict[str, int] = defaultdict(int)
        user_counts: Dict[str, int] = defaultdict(int)

        for entry in entries:
            action_counts[entry.action.value] += 1
            if entry.user_id:
                user_counts[entry.user_id] += 1

            if entry.severity == Severity.HIGH:
                report.high_severity_events += 1
            elif entry.severity == Severity.MEDIUM:
                report.medium_severity_events += 1
            else:
                report.low_severity_events += 1

            if not entry.success:
                report.failed_operations += 1

        report.action_distribution = dict(action_counts)
        report.user_activity = dict(user_counts)

        if include_anomalies:
            report.anomalies = self._detect_anomalies(entries)

        report.summary = self._generate_summary(report)

        return report

    def _detect_anomalies(self, entries: List[AuditLogEntry]) -> List[Dict[str, Any]]:
        anomalies: List[Dict[str, Any]] = []

        high_severity = [e for e in entries if e.severity == Severity.HIGH]
        if len(high_severity) > 5:
            anomalies.append({
                "type": "high_severity_spike",
                "count": len(high_severity),
                "description": f"Unusually high number of high-severity events: {len(high_severity)}",
            })

        failed_ops = [e for e in entries if not e.success]
        if entries and (len(failed_ops) / len(entries)) > 0.1:
            anomalies.append({
                "type": "high_failure_rate",
                "failure_rate": len(failed_ops) / len(entries),
                "description": f"High failure rate: {len(failed_ops) / len(entries):.2%}",
            })

        return anomalies

    def _generate_summary(self, report: ComplianceReport) -> str:
        parts = [
            f"Compliance report for period {report.start_date.date()} to {report.end_date.date()}.",
            f"Total events: {report.total_events}.",
            f"Severity breakdown: High={report.high_severity_events}, "
            f"Medium={report.medium_severity_events}, Low={report.low_severity_events}.",
            f"Failed operations: {report.failed_operations}.",
            f"Unique users: {len(report.user_activity)}.",
        ]
        return " ".join(parts)


class CommandAuditManager:
    _instance: Optional['CommandAuditManager'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(
        self,
        command_store: Optional[CommandStore] = None,
        audit_store: Optional[AuditLogStore] = None,
        event_store: Optional[EventStore] = None,
        metrics_collector: Optional[MetricsCollector] = None,
    ):
        if self._initialized:
            return

        storage = get_storage_manager()
        self.command_store = command_store or StorageCommandStore(storage)
        self.audit_store = audit_store or StorageAuditLogStore(storage)
        self.event_store = event_store or get_event_store()
        self._handlers: Dict[CommandType, Callable] = {}
        self._metrics = metrics_collector or MetricsCollector()
        self._active_commands: Dict[str, datetime] = {}
        self._initialized = True

    @asynccontextmanager
    async def _timed_operation(self, operation: str, **metadata):
        start = time.perf_counter()
        success = True
        try:
            yield
        except Exception:
            success = False
            raise
        finally:
            duration_ms = (time.perf_counter() - start) * 1000
            await self._metrics.record(operation, duration_ms, success, metadata)

    async def create_command(
        self,
        command_type: CommandType,
        payload: Dict[str, Any],
        entity_id: Optional[str] = None,
        user_id: Optional[str] = None,
        correlation_id: Optional[str] = None,
        causation_id: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        max_retries: int = 3,
    ) -> Command:
        async with self._timed_operation("create_command", command_type=command_type.value):
            command = Command(
                command_id=str(uuid.uuid4()),
                command_type=command_type,
                entity_id=entity_id,
                payload=payload,
                user_id=user_id,
                correlation_id=correlation_id or str(uuid.uuid4()),
                causation_id=causation_id,
                metadata=metadata or {},
                max_retries=max_retries,
            )

            await self.command_store.save_command(command)
            logger.info("Command created", command_id=command.command_id, command_type=command_type)

            await self.event_store.append(
                aggregate_id=command.command_id,
                event_type=EventType.CUSTOM,
                payload={"command": command.to_dict()},
                metadata={"phase": "created"},
            )

            return command

    async def execute_command(self, command: Command) -> Command:
        self._active_commands[command.command_id] = datetime.utcnow()
        self._metrics.get_prometheus().set_active_commands(len(self._active_commands))

        start = time.perf_counter()
        success = True

        try:
            command.status = CommandStatus.EXECUTING
            command.executed_at = datetime.utcnow()
            await self.command_store.update_command(command)

            handler = self._handlers.get(command.command_type)
            try:
                if handler:
                    result = await handler(command.payload)
                    command.status = CommandStatus.COMPLETED
                    command.result = result
                else:
                    command.status = CommandStatus.COMPLETED
                    command.result = {"status": "executed"}

                command.completed_at = datetime.utcnow()
                await self.command_store.update_command(command)

                await self.event_store.append(
                    aggregate_id=command.command_id,
                    event_type=EventType.COMMAND_EXECUTED,
                    payload={"command": command.to_dict()},
                    metadata={"phase": "completed"},
                )

                await self.audit_store.append(AuditLogEntry(
                    log_id=str(uuid.uuid4()),
                    action=AuditAction.MODIFY,
                    user_id=command.user_id,
                    resource_type="command",
                    resource_id=command.command_id,
                    description=f"Command {command.command_type.value} executed successfully",
                    severity=Severity.LOW,
                    correlation_id=command.correlation_id,
                    after_state=command.result,
                    success=True,
                ))

            except Exception as e:
                success = False
                command.status = CommandStatus.FAILED
                command.error_message = str(e)
                command.completed_at = datetime.utcnow()
                await self.command_store.update_command(command)

                await self.event_store.append(
                    aggregate_id=command.command_id,
                    event_type=EventType.COMMAND_FAILED,
                    payload={"command": command.to_dict(), "error": str(e)},
                    metadata={"phase": "failed"},
                )

                await self.audit_store.append(AuditLogEntry(
                    log_id=str(uuid.uuid4()),
                    action=AuditAction.MODIFY,
                    user_id=command.user_id,
                    resource_type="command",
                    resource_id=command.command_id,
                    description=f"Command {command.command_type.value} failed: {str(e)}",
                    severity=Severity.HIGH,
                    correlation_id=command.correlation_id,
                    success=False,
                ))

                if command.retry_count < command.max_retries:
                    command.retry_count += 1
                    logger.warning("Retrying command", command_id=command.command_id, attempt=command.retry_count)
                    return await self.execute_command(command)
        finally:
            duration_ms = (time.perf_counter() - start) * 1000
            await self._metrics.record(
                "execute_command",
                duration_ms,
                success,
                {"command_type": command.command_type.value}
            )
            self._metrics.get_prometheus().observe_command(
                command.command_type.value,
                command.status.value,
                duration_ms / 1000.0
            )

            if command.command_id in self._active_commands:
                del self._active_commands[command.command_id]
            self._metrics.get_prometheus().set_active_commands(len(self._active_commands))

        return command

    async def get_command(self, command_id: str) -> Optional[Command]:
        return await self.command_store.get_command(command_id)

    async def list_commands(
        self,
        status: Optional[CommandStatus] = None,
        user_id: Optional[str] = None,
        entity_id: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[Command]:
        return await self.command_store.list_commands(
            status=status,
            user_id=user_id,
            entity_id=entity_id,
            start_time=start_time,
            end_time=end_time,
            limit=limit,
        )

    async def log_action(
        self,
        action: AuditAction,
        description: str,
        user_id: Optional[str] = None,
        resource_type: Optional[str] = None,
        resource_id: Optional[str] = None,
        severity: Severity = Severity.LOW,
        source_ip: Optional[str] = None,
        user_agent: Optional[str] = None,
        request_id: Optional[str] = None,
        correlation_id: Optional[str] = None,
        before_state: Optional[Dict[str, Any]] = None,
        after_state: Optional[Dict[str, Any]] = None,
        success: bool = True,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> AuditLogEntry:
        async with self._timed_operation("log_action", action=action.value, severity=severity.value):
            entry = AuditLogEntry(
                log_id=str(uuid.uuid4()),
                action=action,
                user_id=user_id,
                resource_type=resource_type,
                resource_id=resource_id,
                description=description,
                severity=severity,
                source_ip=source_ip,
                user_agent=user_agent,
                request_id=request_id,
                correlation_id=correlation_id,
                before_state=before_state,
                after_state=after_state,
                metadata=metadata or {},
                success=success,
            )

            await self.audit_store.append(entry)
            self._metrics.get_prometheus().observe_audit_log(severity.value, success)
            return entry

    async def query_audit_logs(
        self,
        action: Optional[AuditAction] = None,
        user_id: Optional[str] = None,
        resource_type: Optional[str] = None,
        resource_id: Optional[str] = None,
        severity: Optional[Severity] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        success: Optional[bool] = None,
        limit: int = 1000,
    ) -> List[AuditLogEntry]:
        async with self._timed_operation("query_audit_logs", limit=limit):
            return await self.audit_store.query(
                action=action,
                user_id=user_id,
                resource_type=resource_type,
                resource_id=resource_id,
                severity=severity,
                start_time=start_time,
                end_time=end_time,
                success=success,
                limit=limit,
            )

    async def generate_compliance_report(
        self,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        include_anomalies: bool = True,
    ) -> ComplianceReport:
        start = time.perf_counter()
        try:
            end = end_date or datetime.utcnow()
            start_date = start_date or end - timedelta(days=30)
            return await self.audit_store.generate_report(start_date, end, include_anomalies)
        finally:
            duration = time.perf_counter() - start
            self._metrics.get_prometheus().observe_report_generation(duration)

    def register_command_handler(self, command_type: CommandType, handler: Callable) -> None:
        self._handlers[command_type] = handler

    async def get_command_chain(self, correlation_id: str) -> List[Command]:
        async with self._timed_operation("get_command_chain"):
            return await self.command_store.get_commands_by_correlation(correlation_id)

    async def export_audit_logs(
        self,
        start_date: datetime,
        end_date: datetime,
        format: str = "json",
    ) -> str:
        async with self._timed_operation("export_audit_logs", format=format):
            entries = await self.query_audit_logs(
                start_time=start_date,
                end_time=end_date,
                limit=100000,
            )

            storage = get_storage_manager()
            timestamp = datetime.utcnow().strftime('%Y%m%d_%H%M%S')
            filename = f"audit_export_{timestamp}.{format}"
            key = f"audit/exports/{filename}"

            if format == "json":
                data = json.dumps([e.to_dict() for e in entries], indent=2)
                await storage.save_data(key, data)
            elif format == "csv":
                import csv
                import io
                output = io.StringIO()
                writer = csv.writer(output)
                header = list(AuditLogEntry.__dataclass_fields__.keys())
                writer.writerow(header)
                for entry in entries:
                    writer.writerow([str(getattr(entry, f)) for f in header])
                await storage.save_data(key, output.getvalue())
            else:
                raise ValueError(f"Unsupported format: {format}")

            return key

    def get_metrics(self, operation: Optional[str] = None) -> MetricsSummary:
        return self._metrics.get_summary(operation)

    def get_prometheus_exporter(self) -> PrometheusMetricsExporter:
        return self._metrics.get_prometheus()

    def get_active_command_count(self) -> int:
        return len(self._active_commands)

    def get_status(self) -> Dict[str, Any]:
        metrics = self.get_metrics()
        return {
            "active_commands": self.get_active_command_count(),
            "prometheus_enabled": self._metrics.get_prometheus().is_enabled,
            "metrics": metrics.to_dict(),
        }


def get_command_audit_manager() -> CommandAuditManager:
    return CommandAuditManager()
