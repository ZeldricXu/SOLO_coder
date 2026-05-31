import time
import asyncio
import random
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Callable, Tuple, Union
from enum import Enum
from dataclasses import dataclass, field
from collections import defaultdict
from functools import wraps

from .logging_module import get_logger
from .config_module import get_app_config, get_config_manager
from .event_store import EventStore, EventType, get_event_store
from .audit_module import AuditAction, Severity, get_command_audit_manager

logger = get_logger(__name__)


class FaultType(str, Enum):
    LATENCY = "latency"
    ERROR = "error"
    EXCEPTION = "exception"
    DATA_CORRUPTION = "data_corruption"
    MEMORY_LEAK = "memory_leak"
    CPU_SPIKE = "cpu_spike"
    DISK_IO_SLOW = "disk_io_slow"
    NETWORK_PARTITION = "network_partition"
    RESOURCE_EXHAUSTION = "resource_exhaustion"


class FaultStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    EXPIRED = "expired"
    TRIGGERED = "triggered"


class InjectionScope(str, Enum):
    GLOBAL = "global"
    MODULE = "module"
    FUNCTION = "function"
    ENDPOINT = "endpoint"
    ENTITY = "entity"
    USER = "user"


class RollbackStrategy(str, Enum):
    AUTOMATIC = "automatic"
    MANUAL = "manual"
    TIMED = "timed"
    CONDITIONAL = "conditional"


@dataclass
class FaultCondition:
    min_calls: int = 0
    max_calls: Optional[int] = None
    probability: float = 1.0
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    custom_condition: Optional[Callable] = None

    def should_trigger(self, call_count: int) -> bool:
        if call_count < self.min_calls:
            return False
        if self.max_calls and call_count > self.max_calls:
            return False
        if self.start_time and datetime.utcnow() < self.start_time:
            return False
        if self.end_time and datetime.utcnow() > self.end_time:
            return False
        if random.random() > self.probability:
            return False
        if self.custom_condition and not self.custom_condition():
            return False
        return True


@dataclass
class BatchOperationResult:
    success_count: int = 0
    failed_count: int = 0
    results: List[Tuple[str, bool, Optional[str]]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success_count": self.success_count,
            "failed_count": self.failed_count,
            "total": self.success_count + self.failed_count,
            "results": [
                {"fault_id": fid, "success": ok, "error": err}
                for fid, ok, err in self.results
            ],
        }


@dataclass
class FaultDefinition:
    fault_id: str
    fault_type: FaultType
    scope: InjectionScope
    target: str
    parameters: Dict[str, Any] = field(default_factory=dict)
    condition: FaultCondition = field(default_factory=FaultCondition)
    status: FaultStatus = FaultStatus.INACTIVE
    rollback_strategy: RollbackStrategy = RollbackStrategy.AUTOMATIC
    rollback_timeout: int = 300
    created_at: datetime = field(default_factory=datetime.utcnow)
    created_by: Optional[str] = None
    description: Optional[str] = None
    call_count: int = 0
    trigger_count: int = 0
    last_triggered_at: Optional[datetime] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "fault_id": self.fault_id,
            "fault_type": self.fault_type.value,
            "scope": self.scope.value,
            "target": self.target,
            "parameters": self.parameters,
            "status": self.status.value,
            "rollback_strategy": self.rollback_strategy.value,
            "rollback_timeout": self.rollback_timeout,
            "created_at": self.created_at.isoformat(),
            "created_by": self.created_by,
            "description": self.description,
            "call_count": self.call_count,
            "trigger_count": self.trigger_count,
            "last_triggered_at": self.last_triggered_at.isoformat() if self.last_triggered_at else None,
            "metadata": self.metadata,
        }


@dataclass
class FaultInjectionResult:
    fault_id: str
    fault_type: FaultType
    triggered: bool
    details: Dict[str, Any] = field(default_factory=dict)
    timestamp: datetime = field(default_factory=datetime.utcnow)


class FaultInjector(ABC):
    @abstractmethod
    async def inject(self, fault: FaultDefinition) -> None:
        pass

    @abstractmethod
    async def rollback(self, fault: FaultDefinition) -> None:
        pass


class LatencyInjector(FaultInjector):
    async def inject(self, fault: FaultDefinition) -> None:
        delay = fault.parameters.get("delay_ms", 1000) / 1000.0
        jitter = fault.parameters.get("jitter_ms", 0) / 1000.0
        actual_delay = delay + random.uniform(-jitter, jitter)
        actual_delay = max(0, actual_delay)
        await asyncio.sleep(actual_delay)

    async def rollback(self, fault: FaultDefinition) -> None:
        pass


class ErrorInjector(FaultInjector):
    async def inject(self, fault: FaultDefinition) -> None:
        error_code = fault.parameters.get("error_code", 500)
        error_message = fault.parameters.get("error_message", "Injected fault error")
        raise RuntimeError(f"Error {error_code}: {error_message}")

    async def rollback(self, fault: FaultDefinition) -> None:
        pass


class DataCorruptionInjector(FaultInjector):
    async def inject(self, fault: FaultDefinition) -> None:
        corruption_probability = fault.parameters.get("corruption_probability", 0.1)
        if random.random() < corruption_probability:
            raise ValueError("Data corruption detected")

    async def rollback(self, fault: FaultDefinition) -> None:
        pass


class CPUSpikeInjector(FaultInjector):
    async def inject(self, fault: FaultDefinition) -> None:
        duration = fault.parameters.get("duration_ms", 500) / 1000.0
        end_time = time.time() + duration
        while time.time() < end_time:
            pass

    async def rollback(self, fault: FaultDefinition) -> None:
        pass


class FaultInjectorFactory:
    _injectors: Dict[FaultType, FaultInjector] = {
        FaultType.LATENCY: LatencyInjector(),
        FaultType.ERROR: ErrorInjector(),
        FaultType.EXCEPTION: ErrorInjector(),
        FaultType.DATA_CORRUPTION: DataCorruptionInjector(),
        FaultType.CPU_SPIKE: CPUSpikeInjector(),
    }

    @classmethod
    def get(cls, fault_type: FaultType) -> FaultInjector:
        return cls._injectors.get(fault_type, ErrorInjector())

    @classmethod
    def register(cls, fault_type: FaultType, injector: FaultInjector) -> None:
        cls._injectors[fault_type] = injector


class FaultInjectionManager:
    _instance: Optional['FaultInjectionManager'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, event_store: Optional[EventStore] = None):
        if self._initialized:
            return

        self._faults: Dict[str, FaultDefinition] = {}
        self._active_faults: Dict[Tuple[str, str], List[FaultDefinition]] = defaultdict(list)
        self._event_store = event_store or get_event_store()
        self._enabled = False
        self._initialized = True

    def enable(self) -> None:
        config = get_config_manager()
        if config.is_feature_enabled("fault_injection"):
            self._enabled = True
            logger.warning("Fault injection enabled - this should only be used in testing environments")
        else:
            logger.warning("Fault injection feature is disabled in config")

    def disable(self) -> None:
        self._enabled = False
        logger.info("Fault injection disabled")

    @property
    def is_enabled(self) -> bool:
        return self._enabled

    def register_fault(self, fault: FaultDefinition) -> FaultDefinition:
        self._faults[fault.fault_id] = fault
        if fault.status == FaultStatus.ACTIVE:
            self._activate_fault(fault)
        logger.info("Fault registered", fault_id=fault.fault_id, fault_type=fault.fault_type)
        return fault

    def create_fault(
        self,
        fault_type: FaultType,
        scope: InjectionScope,
        target: str,
        parameters: Optional[Dict[str, Any]] = None,
        condition: Optional[FaultCondition] = None,
        rollback_strategy: RollbackStrategy = RollbackStrategy.AUTOMATIC,
        rollback_timeout: int = 300,
        created_by: Optional[str] = None,
        description: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> FaultDefinition:
        fault = FaultDefinition(
            fault_id=str(uuid.uuid4()),
            fault_type=fault_type,
            scope=scope,
            target=target,
            parameters=parameters or {},
            condition=condition or FaultCondition(),
            status=FaultStatus.INACTIVE,
            rollback_strategy=rollback_strategy,
            rollback_timeout=rollback_timeout,
            created_by=created_by,
            description=description,
            metadata=metadata or {},
        )
        return self.register_fault(fault)

    def _activate_fault(self, fault: FaultDefinition) -> None:
        key = (fault.scope.value, fault.target)
        self._active_faults[key].append(fault)
        fault.status = FaultStatus.ACTIVE

    def _deactivate_fault(self, fault: FaultDefinition) -> None:
        key = (fault.scope.value, fault.target)
        if fault in self._active_faults.get(key, []):
            self._active_faults[key].remove(fault)
        fault.status = FaultStatus.INACTIVE

    def activate_fault(self, fault_id: str) -> Optional[FaultDefinition]:
        fault = self._faults.get(fault_id)
        if fault:
            self._activate_fault(fault)
            logger.info("Fault activated", fault_id=fault_id)

            asyncio.create_task(self._event_store.append(
                aggregate_id=fault_id,
                event_type=EventType.CUSTOM,
                payload={"action": "fault_activated", "fault": fault.to_dict()},
                metadata={"phase": "fault_injection"},
            ))
        return fault

    def deactivate_fault(self, fault_id: str) -> Optional[FaultDefinition]:
        fault = self._faults.get(fault_id)
        if fault:
            self._deactivate_fault(fault)
            logger.info("Fault deactivated", fault_id=fault_id)

            asyncio.create_task(self._event_store.append(
                aggregate_id=fault_id,
                event_type=EventType.CUSTOM,
                payload={"action": "fault_deactivated", "fault": fault.to_dict()},
                metadata={"phase": "fault_injection"},
            ))
        return fault

    def get_fault(self, fault_id: str) -> Optional[FaultDefinition]:
        return self._faults.get(fault_id)

    def list_faults(self, status: Optional[FaultStatus] = None,
                    scope: Optional[InjectionScope] = None,
                    fault_type: Optional[FaultType] = None) -> List[FaultDefinition]:
        faults = list(self._faults.values())
        if status:
            faults = [f for f in faults if f.status == status]
        if scope:
            faults = [f for f in faults if f.scope == scope]
        if fault_type:
            faults = [f for f in faults if f.fault_type == fault_type]
        return faults

    def delete_fault(self, fault_id: str) -> bool:
        fault = self._faults.get(fault_id)
        if fault:
            self._deactivate_fault(fault)
            del self._faults[fault_id]
            logger.info("Fault deleted", fault_id=fault_id)
            return True
        return False

    def _get_matching_faults(self, scope: InjectionScope, target: str) -> List[FaultDefinition]:
        if not self._enabled:
            return []

        key = (scope.value, target)
        faults = self._active_faults.get(key, [])

        global_key = (InjectionScope.GLOBAL.value, "*")
        global_faults = self._active_faults.get(global_key, [])

        all_faults = faults + global_faults
        return [f for f in all_faults if f.status == FaultStatus.ACTIVE]

    async def check_and_inject(self, scope: InjectionScope, target: str) -> List[FaultInjectionResult]:
        results: List[FaultInjectionResult] = []
        faults = self._get_matching_faults(scope, target)

        for fault in faults:
            fault.call_count += 1
            if fault.condition.should_trigger(fault.call_count):
                fault.trigger_count += 1
                fault.last_triggered_at = datetime.utcnow()

                injector = FaultInjectorFactory.get(fault.fault_type)
                try:
                    await injector.inject(fault)
                    results.append(FaultInjectionResult(
                        fault_id=fault.fault_id,
                        fault_type=fault.fault_type,
                        triggered=True,
                        details={"status": "injected"},
                    ))
                    logger.warning("Fault injected", fault_id=fault.fault_id, fault_type=fault.fault_type)
                except Exception as e:
                    results.append(FaultInjectionResult(
                        fault_id=fault.fault_id,
                        fault_type=fault.fault_type,
                        triggered=True,
                        details={"error": str(e)},
                    ))
                    raise

        return results

    def inject_decorator(self, scope: InjectionScope = InjectionScope.FUNCTION, target: Optional[str] = None):
        def decorator(func):
            func_target = target or f"{func.__module__}.{func.__name__}"

            @wraps(func)
            async def async_wrapper(*args, **kwargs):
                await self.check_and_inject(scope, func_target)
                return await func(*args, **kwargs)

            @wraps(func)
            def sync_wrapper(*args, **kwargs):
                loop = asyncio.new_event_loop()
                try:
                    loop.run_until_complete(self.check_and_inject(scope, func_target))
                finally:
                    loop.close()
                return func(*args, **kwargs)

            if asyncio.iscoroutinefunction(func):
                return async_wrapper
            return sync_wrapper

        return decorator

    async def rollback_fault(self, fault_id: str) -> bool:
        fault = self._faults.get(fault_id)
        if not fault:
            return False

        injector = FaultInjectorFactory.get(fault.fault_type)
        try:
            await injector.rollback(fault)
            self._deactivate_fault(fault)
            logger.info("Fault rolled back", fault_id=fault_id)

            asyncio.create_task(self._event_store.append(
                aggregate_id=fault_id,
                event_type=EventType.CUSTOM,
                payload={"action": "fault_rolled_back", "fault": fault.to_dict()},
                metadata={"phase": "fault_injection"},
            ))

            return True
        except Exception as e:
            logger.error("Fault rollback failed", fault_id=fault_id, error=str(e))
            return False

    async def rollback_all(self) -> int:
        count = 0
        for fault_id in list(self._faults.keys()):
            if await self.rollback_fault(fault_id):
                count += 1
        logger.info("All faults rolled back", count=count)
        return count

    def get_active_fault_count(self) -> int:
        return sum(1 for f in self._faults.values() if f.status == FaultStatus.ACTIVE)

    def get_fault_stats(self) -> Dict[str, Any]:
        return {
            "total_faults": len(self._faults),
            "active_faults": self.get_active_fault_count(),
            "by_type": {
                ft.value: len([f for f in self._faults.values() if f.fault_type == ft])
                for ft in FaultType
            },
            "by_scope": {
                sc.value: len([f for f in self._faults.values() if f.scope == sc])
                for sc in InjectionScope
            },
        }

    def batch_create_faults(
        self,
        fault_specs: List[Dict[str, Any]],
    ) -> BatchOperationResult:
        result = BatchOperationResult()

        for spec in fault_specs:
            try:
                fault = self.create_fault(
                    fault_type=FaultType(spec["fault_type"]),
                    scope=InjectionScope(spec.get("scope", InjectionScope.GLOBAL)),
                    target=spec.get("target", "*"),
                    parameters=spec.get("parameters", {}),
                    condition=spec.get("condition"),
                    rollback_strategy=RollbackStrategy(spec.get("rollback_strategy", RollbackStrategy.AUTOMATIC)),
                    rollback_timeout=spec.get("rollback_timeout", 300),
                    created_by=spec.get("created_by"),
                    description=spec.get("description"),
                    metadata=spec.get("metadata", {}),
                )
                result.success_count += 1
                result.results.append((fault.fault_id, True, None))
            except Exception as e:
                result.failed_count += 1
                result.results.append(("", False, str(e)))

        logger.info("Batch create completed", success=result.success_count, failed=result.failed_count)
        return result

    def batch_activate_faults(self, fault_ids: List[str]) -> BatchOperationResult:
        result = BatchOperationResult()

        for fault_id in fault_ids:
            try:
                fault = self.activate_fault(fault_id)
                if fault:
                    result.success_count += 1
                    result.results.append((fault_id, True, None))
                else:
                    result.failed_count += 1
                    result.results.append((fault_id, False, "Fault not found"))
            except Exception as e:
                result.failed_count += 1
                result.results.append((fault_id, False, str(e)))

        logger.info("Batch activate completed", success=result.success_count, failed=result.failed_count)
        return result

    def batch_deactivate_faults(self, fault_ids: List[str]) -> BatchOperationResult:
        result = BatchOperationResult()

        for fault_id in fault_ids:
            try:
                fault = self.deactivate_fault(fault_id)
                if fault:
                    result.success_count += 1
                    result.results.append((fault_id, True, None))
                else:
                    result.failed_count += 1
                    result.results.append((fault_id, False, "Fault not found"))
            except Exception as e:
                result.failed_count += 1
                result.results.append((fault_id, False, str(e)))

        logger.info("Batch deactivate completed", success=result.success_count, failed=result.failed_count)
        return result

    def batch_delete_faults(self, fault_ids: List[str]) -> BatchOperationResult:
        result = BatchOperationResult()

        for fault_id in fault_ids:
            try:
                if self.delete_fault(fault_id):
                    result.success_count += 1
                    result.results.append((fault_id, True, None))
                else:
                    result.failed_count += 1
                    result.results.append((fault_id, False, "Fault not found"))
            except Exception as e:
                result.failed_count += 1
                result.results.append((fault_id, False, str(e)))

        logger.info("Batch delete completed", success=result.success_count, failed=result.failed_count)
        return result

    async def batch_rollback_faults(self, fault_ids: List[str]) -> BatchOperationResult:
        result = BatchOperationResult()

        for fault_id in fault_ids:
            try:
                if await self.rollback_fault(fault_id):
                    result.success_count += 1
                    result.results.append((fault_id, True, None))
                else:
                    result.failed_count += 1
                    result.results.append((fault_id, False, "Fault not found or rollback failed"))
            except Exception as e:
                result.failed_count += 1
                result.results.append((fault_id, False, str(e)))

        logger.info("Batch rollback completed", success=result.success_count, failed=result.failed_count)
        return result

    def batch_create_and_activate(
        self,
        fault_specs: List[Dict[str, Any]],
    ) -> BatchOperationResult:
        create_result = self.batch_create_faults(fault_specs)
        created_ids = [r[0] for r in create_result.results if r[1]]

        if created_ids:
            activate_result = self.batch_activate_faults(created_ids)
            combined = BatchOperationResult()
            combined.success_count = activate_result.success_count
            combined.failed_count = create_result.failed_count + activate_result.failed_count
            combined.results = create_result.results + [
                (r[0], r[1], r[2] if r[1] else f"Create succeeded but activate failed: {r[2]}")
                for r in activate_result.results
            ]
            return combined

        return create_result

    def get_faults_by_ids(self, fault_ids: List[str]) -> List[FaultDefinition]:
        return [self._faults[fid] for fid in fault_ids if fid in self._faults]


def get_fault_injection_manager() -> FaultInjectionManager:
    return FaultInjectionManager()
