import uuid
import random
import string
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, TypeVar, Generic
from dataclasses import dataclass, field

T = TypeVar('T')


class BaseBuilder(Generic[T]):
    def __init__(self, target_class: type):
        self._target_class = target_class
        self._overrides: Dict[str, Any] = {}

    def with_(self, **kwargs) -> 'BaseBuilder[T]':
        self._overrides.update(kwargs)
        return self

    def _build_dict(self) -> Dict[str, Any]:
        raise NotImplementedError

    def build(self) -> T:
        data = self._build_dict()
        data.update(self._overrides)
        return self._target_class(**data)

    def build_many(self, count: int) -> List[T]:
        return [self.build() for _ in range(count)]


@dataclass
class EntityTestData:
    id: str
    type: str
    status: str
    attributes: Dict[str, Any]
    labels: Dict[str, Any]
    created_at: datetime
    updated_at: datetime


class EntityTestDataBuilder(BaseBuilder[EntityTestData]):
    def __init__(self):
        super().__init__(EntityTestData)
        self._counter = 0

    def _build_dict(self) -> Dict[str, Any]:
        self._counter += 1
        return {
            "id": f"ent_{uuid.uuid4().hex[:8]}",
            "type": random.choice(["task", "resource", "job", "workflow"]),
            "status": random.choice(["pending", "running", "completed", "failed"]),
            "attributes": {
                "name": f"test_entity_{self._counter}",
                "priority": random.randint(1, 5),
                "config": {"timeout": random.randint(30, 300)}
            },
            "labels": {
                "environment": random.choice(["dev", "staging", "prod"]),
                "team": random.choice(["data", "backend", "frontend"])
            },
            "created_at": datetime.utcnow() - timedelta(hours=random.randint(1, 100)),
            "updated_at": datetime.utcnow(),
        }

    def with_type(self, entity_type: str) -> 'EntityTestDataBuilder':
        return self.with_(type=entity_type)

    def with_status(self, status: str) -> 'EntityTestDataBuilder':
        return self.with_(status=status)

    def with_attributes(self, attributes: Dict[str, Any]) -> 'EntityTestDataBuilder':
        return self.with_(attributes=attributes)


@dataclass
class FaultDefinitionTestData:
    fault_id: str
    fault_type: str
    scope: str
    target: str
    parameters: Dict[str, Any]
    status: str
    rollback_strategy: str
    rollback_timeout: int
    created_at: datetime
    description: str
    call_count: int
    trigger_count: int


class FaultDefinitionTestDataBuilder(BaseBuilder[FaultDefinitionTestData]):
    def __init__(self):
        super().__init__(FaultDefinitionTestData)
        self._counter = 0

    def _build_dict(self) -> Dict[str, Any]:
        self._counter += 1
        fault_types = ["latency", "error", "data_corruption", "cpu_spike", "disk_io_slow"]
        scopes = ["global", "module", "function", "endpoint", "entity", "user"]
        targets = [
            "*", "data_access", "core_module",
            "api.v1.resources", "api.v1.tasks",
            f"entity_{self._counter}", f"user_{self._counter}"
        ]

        return {
            "fault_id": f"fault_{uuid.uuid4().hex[:8]}",
            "fault_type": random.choice(fault_types),
            "scope": random.choice(scopes),
            "target": random.choice(targets),
            "parameters": {
                "delay_ms": random.randint(100, 5000),
                "error_code": random.choice([400, 401, 403, 500, 502, 503, 504]),
                "error_message": f"Injected fault #{self._counter}",
                "probability": random.uniform(0.1, 1.0)
            },
            "status": random.choice(["active", "inactive", "expired", "triggered"]),
            "rollback_strategy": random.choice(["automatic", "manual", "timed", "conditional"]),
            "rollback_timeout": random.randint(60, 3600),
            "created_at": datetime.utcnow() - timedelta(minutes=random.randint(1, 1000)),
            "description": f"Test fault definition #{self._counter}",
            "call_count": random.randint(0, 1000),
            "trigger_count": random.randint(0, 100),
        }

    def with_fault_type(self, fault_type: str) -> 'FaultDefinitionTestDataBuilder':
        return self.with_(fault_type=fault_type)

    def with_scope(self, scope: str) -> 'FaultDefinitionTestDataBuilder':
        return self.with_(scope=scope)

    def with_target(self, target: str) -> 'FaultDefinitionTestDataBuilder':
        return self.with_(target=target)

    def with_status(self, status: str) -> 'FaultDefinitionTestDataBuilder':
        return self.with_(status=status)

    def with_parameters(self, parameters: Dict[str, Any]) -> 'FaultDefinitionTestDataBuilder':
        return self.with_(parameters=parameters)

    def active(self) -> 'FaultDefinitionTestDataBuilder':
        return self.with_(status="active")

    def inactive(self) -> 'FaultDefinitionTestDataBuilder':
        return self.with_(status="inactive")


@dataclass
class CommandTestData:
    command_id: str
    command_type: str
    entity_id: Optional[str]
    payload: Dict[str, Any]
    status: str
    created_at: datetime
    executed_at: Optional[datetime]
    completed_at: Optional[datetime]
    result: Optional[Dict[str, Any]]
    error_message: Optional[str]
    user_id: Optional[str]
    correlation_id: str
    retry_count: int
    max_retries: int


class CommandTestDataBuilder(BaseBuilder[CommandTestData]):
    def __init__(self):
        super().__init__(CommandTestData)
        self._counter = 0

    def _build_dict(self) -> Dict[str, Any]:
        self._counter += 1
        command_types = ["create", "update", "delete", "migrate", "execute", "batch", "custom"]
        statuses = ["pending", "executing", "completed", "failed", "rollback", "rollback_completed"]
        status = random.choice(statuses)

        base_time = datetime.utcnow() - timedelta(minutes=random.randint(1, 60))
        executed_at = base_time + timedelta(seconds=random.randint(1, 30)) if status != "pending" else None
        completed_at = executed_at + timedelta(seconds=random.randint(1, 120)) if status in ["completed", "failed"] else None

        return {
            "command_id": f"cmd_{uuid.uuid4().hex[:8]}",
            "command_type": random.choice(command_types),
            "entity_id": random.choice([None, f"ent_{uuid.uuid4().hex[:8]}"]),
            "payload": {
                "action": f"action_{self._counter}",
                "data": {"key": f"value_{self._counter}"},
                "options": {"async": random.choice([True, False])}
            },
            "status": status,
            "created_at": base_time,
            "executed_at": executed_at,
            "completed_at": completed_at,
            "result": {"success": True, "data": {"id": f"result_{self._counter}"}} if status == "completed" else None,
            "error_message": f"Error in command #{self._counter}" if status == "failed" else None,
            "user_id": random.choice([None, f"user_{random.randint(1, 100)}"]),
            "correlation_id": f"corr_{uuid.uuid4().hex[:12]}",
            "retry_count": random.randint(0, 5),
            "max_retries": random.randint(1, 10),
        }

    def with_type(self, command_type: str) -> 'CommandTestDataBuilder':
        return self.with_(command_type=command_type)

    def with_status(self, status: str) -> 'CommandTestDataBuilder':
        return self.with_(status=status)

    def with_payload(self, payload: Dict[str, Any]) -> 'CommandTestDataBuilder':
        return self.with_(payload=payload)

    def with_user_id(self, user_id: str) -> 'CommandTestDataBuilder':
        return self.with_(user_id=user_id)

    def with_correlation_id(self, correlation_id: str) -> 'CommandTestDataBuilder':
        return self.with_(correlation_id=correlation_id)

    def pending(self) -> 'CommandTestDataBuilder':
        return self.with_(status="pending", executed_at=None, completed_at=None)

    def completed(self) -> 'CommandTestDataBuilder':
        base_time = datetime.utcnow() - timedelta(minutes=5)
        return self.with_(
            status="completed",
            executed_at=base_time + timedelta(seconds=5),
            completed_at=base_time + timedelta(seconds=30),
            result={"success": True}
        )

    def failed(self) -> 'CommandTestDataBuilder':
        base_time = datetime.utcnow() - timedelta(minutes=5)
        return self.with_(
            status="failed",
            executed_at=base_time + timedelta(seconds=5),
            completed_at=base_time + timedelta(seconds=10),
            error_message="Command execution failed"
        )


@dataclass
class AuditLogTestData:
    log_id: str
    action: str
    user_id: Optional[str]
    resource_type: Optional[str]
    resource_id: Optional[str]
    description: str
    severity: str
    timestamp: datetime
    source_ip: Optional[str]
    request_id: Optional[str]
    correlation_id: Optional[str]
    before_state: Optional[Dict[str, Any]]
    after_state: Optional[Dict[str, Any]]
    success: bool


class AuditLogTestDataBuilder(BaseBuilder[AuditLogTestData]):
    def __init__(self):
        super().__init__(AuditLogTestData)
        self._counter = 0

    def _build_dict(self) -> Dict[str, Any]:
        self._counter += 1
        actions = ["login", "logout", "access", "modify", "delete", "export", "import", "config_change", "api_call"]
        severities = ["low", "medium", "high", "critical"]
        resource_types = ["entity", "config", "command", "task", "user", "system"]

        return {
            "log_id": f"audit_{uuid.uuid4().hex[:8]}",
            "action": random.choice(actions),
            "user_id": f"user_{random.randint(1, 100)}",
            "resource_type": random.choice(resource_types),
            "resource_id": f"res_{uuid.uuid4().hex[:8]}",
            "description": f"Audit log entry #{self._counter}: {random.choice(['User performed action', 'System event occurred', 'Configuration changed'])}",
            "severity": random.choice(severities),
            "timestamp": datetime.utcnow() - timedelta(minutes=random.randint(1, 10000)),
            "source_ip": f"192.168.{random.randint(0, 255)}.{random.randint(1, 254)}",
            "request_id": f"req_{uuid.uuid4().hex[:8]}",
            "correlation_id": f"corr_{uuid.uuid4().hex[:12]}",
            "before_state": {"status": "old", "value": random.randint(1, 100)},
            "after_state": {"status": "new", "value": random.randint(1, 100)},
            "success": random.choice([True, False]),
        }

    def with_action(self, action: str) -> 'AuditLogTestDataBuilder':
        return self.with_(action=action)

    def with_severity(self, severity: str) -> 'AuditLogTestDataBuilder':
        return self.with_(severity=severity)

    def with_user_id(self, user_id: str) -> 'AuditLogTestDataBuilder':
        return self.with_(user_id=user_id)

    def successful(self) -> 'AuditLogTestDataBuilder':
        return self.with_(success=True)

    def failed(self) -> 'AuditLogTestDataBuilder':
        return self.with_(success=False)


@dataclass
class ConfigTestData:
    config_id: str
    namespace: str
    version: int
    parameters: Dict[str, Any]
    enabled: bool
    applied_at: Optional[datetime]
    description: str


class ConfigTestDataBuilder(BaseBuilder[ConfigTestData]):
    def __init__(self):
        super().__init__(ConfigTestData)
        self._counter = 0

    def _build_dict(self) -> Dict[str, Any]:
        self._counter += 1
        namespaces = ["development", "staging", "production", "testing"]

        return {
            "config_id": f"cfg_{self._counter:03d}",
            "namespace": random.choice(namespaces),
            "version": random.randint(1, 50),
            "parameters": {
                "timeout": random.randint(30, 300),
                "retries": random.randint(1, 10),
                "feature_flags": {
                    "enable_cache": random.choice([True, False]),
                    "enable_monitoring": random.choice([True, False])
                },
                "limits": {
                    "max_connections": random.randint(10, 1000),
                    "rate_limit": random.randint(100, 10000)
                }
            },
            "enabled": random.choice([True, False]),
            "applied_at": datetime.utcnow() - timedelta(days=random.randint(0, 30)),
            "description": f"Configuration #{self._counter} for {random.choice(['database', 'cache', 'api', 'queue'])}",
        }

    def with_namespace(self, namespace: str) -> 'ConfigTestDataBuilder':
        return self.with_(namespace=namespace)

    def with_version(self, version: int) -> 'ConfigTestDataBuilder':
        return self.with_(version=version)

    def enabled(self) -> 'ConfigTestDataBuilder':
        return self.with_(enabled=True)

    def disabled(self) -> 'ConfigTestDataBuilder':
        return self.with_(enabled=False)


@dataclass
class RunTestData:
    run_id: str
    entity_id: str
    phase: str
    progress: int
    started_at: datetime
    completed_at: Optional[datetime]
    error_detail: Optional[Dict[str, Any]]
    metrics: Dict[str, Any]


class RunTestDataBuilder(BaseBuilder[RunTestData]):
    def __init__(self):
        super().__init__(RunTestData)
        self._counter = 0

    def _build_dict(self) -> Dict[str, Any]:
        self._counter += 1
        phases = ["initializing", "provisioning", "running", "processing", "finalizing", "completed", "failed"]
        phase = random.choice(phases)
        progress = 100 if phase == "completed" else random.randint(0, 99)
        started_at = datetime.utcnow() - timedelta(minutes=random.randint(1, 120))
        completed_at = started_at + timedelta(minutes=random.randint(1, 60)) if phase in ["completed", "failed"] else None

        return {
            "run_id": f"run_{uuid.uuid4().hex[:8]}",
            "entity_id": f"ent_{uuid.uuid4().hex[:8]}",
            "phase": phase,
            "progress": progress,
            "started_at": started_at,
            "completed_at": completed_at,
            "error_detail": {"error": f"Error in run #{self._counter}", "code": random.randint(1, 100)} if phase == "failed" else None,
            "metrics": {
                "duration_seconds": random.randint(1, 3600),
                "memory_used_mb": random.randint(10, 4096),
                "cpu_usage_percent": random.uniform(0, 100),
                "records_processed": random.randint(0, 1000000)
            },
        }

    def with_phase(self, phase: str) -> 'RunTestDataBuilder':
        return self.with_(phase=phase)

    def with_progress(self, progress: int) -> 'RunTestDataBuilder':
        return self.with_(progress=progress)

    def completed(self) -> 'RunTestDataBuilder':
        return self.with_(phase="completed", progress=100)

    def failed(self) -> 'RunTestDataBuilder':
        return self.with_(phase="failed", error_detail={"error": "Run failed"})


class BuilderFactory:
    @staticmethod
    def entity() -> EntityTestDataBuilder:
        return EntityTestDataBuilder()

    @staticmethod
    def fault() -> FaultDefinitionTestDataBuilder:
        return FaultDefinitionTestDataBuilder()

    @staticmethod
    def command() -> CommandTestDataBuilder:
        return CommandTestDataBuilder()

    @staticmethod
    def audit_log() -> AuditLogTestDataBuilder:
        return AuditLogTestDataBuilder()

    @staticmethod
    def config() -> ConfigTestDataBuilder:
        return ConfigTestDataBuilder()

    @staticmethod
    def run() -> RunTestDataBuilder:
        return RunTestDataBuilder()

    @staticmethod
    def random_string(length: int = 10) -> str:
        return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

    @staticmethod
    def random_email() -> str:
        return f"user_{BuilderFactory.random_string(8)}@example.com"

    @staticmethod
    def random_ip() -> str:
        return f"{random.randint(1, 255)}.{random.randint(0, 255)}.{random.randint(0, 255)}.{random.randint(1, 254)}"

    @staticmethod
    def random_datetime(days_back: int = 30) -> datetime:
        return datetime.utcnow() - timedelta(days=random.randint(0, days_back),
                                              hours=random.randint(0, 23),
                                              minutes=random.randint(0, 59))
