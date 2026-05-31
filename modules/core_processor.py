import asyncio
import uuid
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Callable, List
from datetime import datetime
from enum import Enum
from abc import ABC, abstractmethod
from ..models.entity import Entity, EntityStatus
from ..models.config import ConfigDefinition
from ..models.run import RunInstance, RunPhase
from ..config import settings
from .logging_module import get_logger
from .monitoring_module import get_monitoring

logger = get_logger(__name__)
monitoring = get_monitoring()


class ProcessingError(Exception):
    pass


class ValidationError(ProcessingError):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message)
        self.details = details or {}


class TimeoutError(ProcessingError):
    pass


class ResourceAcquisitionError(ProcessingError):
    pass


@dataclass
class ProcessingContext:
    trace_id: str
    request_id: str
    started_at: float
    namespace: str
    params: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    errors: List[Exception] = field(default_factory=list)
    resources: List[Any] = field(default_factory=list)

    def add_metadata(self, key: str, value: Any) -> None:
        self.metadata[key] = value

    def record_error(self, error: Exception) -> None:
        self.errors.append(error)


@dataclass
class ProcessingResult:
    success: bool
    data: Optional[Dict[str, Any]] = None
    error_code: Optional[int] = None
    error_message: Optional[str] = None
    error_details: Optional[Dict[str, Any]] = None
    run_id: Optional[str] = None
    execution_time_ms: float = 0.0


class ResourcePool:
    def __init__(self, max_size: int = 100):
        self._max_size = max_size
        self._available: List[Any] = []
        self._in_use: set = set()
        self._lock = asyncio.Lock()

    async def acquire(self, timeout: float = 5.0) -> Any:
        start_time = time.time()
        while time.time() - start_time < timeout:
            async with self._lock:
                if self._available:
                    resource = self._available.pop()
                    self._in_use.add(id(resource))
                    return resource
                if len(self._in_use) < self._max_size:
                    resource = object()
                    self._in_use.add(id(resource))
                    return resource
            await asyncio.sleep(0.1)
        raise ResourceAcquisitionError("Failed to acquire resource within timeout")

    def release(self, resource: Any) -> None:
        if id(resource) in self._in_use:
            self._in_use.discard(id(resource))
            self._available.append(resource)


class AbstractProcessor(ABC):
    @abstractmethod
    async def process(self, payload: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
        pass


class DefaultProcessor(AbstractProcessor):
    async def process(self, payload: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
        processing_rules = config.get("rules", {})
        result = {
            "processed": True,
            "original_payload": payload,
            "applied_rules": list(processing_rules.keys()),
            "timestamp": datetime.utcnow().isoformat(),
        }

        if "transform" in processing_rules:
            result["transformed"] = self._apply_transform(payload, processing_rules["transform"])

        if "validate_output" in processing_rules:
            result["validation_passed"] = self._validate_output(result)

        return result

    def _apply_transform(self, payload: Dict[str, Any], transform_config: Dict[str, Any]) -> Dict[str, Any]:
        transformed = payload.copy()
        if "uppercase_fields" in transform_config:
            for field in transform_config["uppercase_fields"]:
                if field in transformed and isinstance(transformed[field], str):
                    transformed[field] = transformed[field].upper()
        if "prefix" in transform_config:
            prefix = transform_config["prefix"]
            transformed = {f"{prefix}_{k}": v for k, v in transformed.items()}
        return transformed

    def _validate_output(self, result: Dict[str, Any]) -> bool:
        return True


class ConfigManager:
    def __init__(self):
        self._configs: Dict[str, ConfigDefinition] = {}
        self._versions: Dict[str, List[ConfigDefinition]] = {}

    def load_config(self, namespace: str) -> ConfigDefinition:
        if namespace not in self._configs:
            config = ConfigDefinition(
                config_id=f"cfg_{uuid.uuid4().hex[:8]}",
                namespace=namespace,
                version=1,
                parameters={
                    "timeout": settings.default_timeout,
                    "retries": settings.default_retries,
                    "poolSize": 10,
                    "rules": {},
                },
                enabled=True,
                applied_at=datetime.utcnow(),
            )
            self._configs[namespace] = config
            self._versions[namespace] = [config]
        return self._configs[namespace]

    def update_config(self, namespace: str, parameters: Dict[str, Any]) -> ConfigDefinition:
        existing = self._configs.get(namespace)
        new_version = existing.version + 1 if existing else 1

        config = ConfigDefinition(
            config_id=f"cfg_{uuid.uuid4().hex[:8]}",
            namespace=namespace,
            version=new_version,
            parameters=parameters,
            enabled=True,
            applied_at=datetime.utcnow(),
        )
        self._configs[namespace] = config
        if namespace not in self._versions:
            self._versions[namespace] = []
        self._versions[namespace].append(config)
        return config

    def get_config_history(self, namespace: str) -> List[ConfigDefinition]:
        return self._versions.get(namespace, [])

    def diff_configs(self, namespace1: str, namespace2: str) -> Dict[str, Any]:
        cfg1 = self.load_config(namespace1)
        cfg2 = self.load_config(namespace2)

        all_keys = set(cfg1.parameters.keys()) | set(cfg2.parameters.keys())
        diff = {}
        for key in all_keys:
            v1 = cfg1.parameters.get(key)
            v2 = cfg2.parameters.get(key)
            if v1 != v2:
                diff[key] = {"namespace1": v1, "namespace2": v2}
        return diff


class RunManager:
    def __init__(self):
        self._runs: Dict[str, RunInstance] = {}

    def create_run(self, entity_id: str) -> RunInstance:
        run = RunInstance(
            run_id=f"run_{uuid.uuid4().hex[:8]}",
            entity_id=entity_id,
            phase=RunPhase.PENDING,
            started_at=datetime.utcnow(),
        )
        self._runs[run.run_id] = run
        return run

    def update_run(self, run_id: str, **kwargs) -> Optional[RunInstance]:
        if run_id not in self._runs:
            return None
        run = self._runs[run_id]
        for key, value in kwargs.items():
            if hasattr(run, key):
                setattr(run, key, value)
        run.updated_at = datetime.utcnow()
        return run

    def get_run(self, run_id: str) -> Optional[RunInstance]:
        return self._runs.get(run_id)

    def get_runs_by_entity(self, entity_id: str) -> List[RunInstance]:
        return [r for r in self._runs.values() if r.entity_id == entity_id]


class EventEmitter:
    def __init__(self):
        self._handlers: Dict[str, List[Callable[[Dict[str, Any]], None]]] = {}

    def on(self, event_name: str, handler: Callable[[Dict[str, Any]], None]) -> None:
        if event_name not in self._handlers:
            self._handlers[event_name] = []
        self._handlers[event_name].append(handler)

    def emit(self, event_name: str, data: Dict[str, Any]) -> None:
        if event_name in self._handlers:
            for handler in self._handlers[event_name]:
                try:
                    handler(data)
                except Exception as e:
                    logger.error(f"Error in event handler for {event_name}: {e}")


class CoreProcessor:
    def __init__(self):
        self._resource_pool = ResourcePool(max_size=settings.max_concurrent_tasks)
        self._config_manager = ConfigManager()
        self._run_manager = RunManager()
        self._event_emitter = EventEmitter()
        self._processors: Dict[str, AbstractProcessor] = {
            "default": DefaultProcessor(),
        }

    def register_processor(self, name: str, processor: AbstractProcessor) -> None:
        self._processors[name] = processor

    def get_config_manager(self) -> ConfigManager:
        return self._config_manager

    def get_run_manager(self) -> RunManager:
        return self._run_manager

    def get_event_emitter(self) -> EventEmitter:
        return self._event_emitter

    def validate_params(self, params: Dict[str, Any]) -> None:
        if not isinstance(params, dict):
            raise ValidationError("Parameters must be a dictionary")

        required_fields = ["payload"]
        for field in required_fields:
            if field not in params:
                raise ValidationError(f"Missing required field: {field}", {"missing_field": field})

        payload = params["payload"]
        if not isinstance(payload, dict):
            raise ValidationError("Payload must be a dictionary")

    async def execute_handler(self, request: Dict[str, Any]) -> ProcessingResult:
        start_time = time.time()
        trace_id = request.get("traceId", f"trace_{uuid.uuid4().hex}")
        request_id = request.get("requestId", f"req_{uuid.uuid4().hex}")

        ctx = ProcessingContext(
            trace_id=trace_id,
            request_id=request_id,
            started_at=start_time,
            namespace=request.get("namespace", "default"),
            params=request.get("params", {}),
        )

        monitoring.collector.increment("requests.total")
        monitoring.collector.increment(f"requests.namespace.{ctx.namespace}")

        try:
            self.validate_params(ctx.params)
            logger.info(f"[{trace_id}] Validated parameters for request {request_id}")

            config = self._config_manager.load_config(ctx.namespace)
            ctx.add_metadata("config_version", config.version)
            logger.info(f"[{trace_id}] Loaded config version {config.version}")

            pool_size = config.parameters.get("poolSize", 10)
            resource = await self._acquire_resource(pool_size)
            ctx.resources.append(resource)

            try:
                processor_name = request.get("processor", "default")
                processor = self._processors.get(processor_name, self._processors["default"])

                monitoring.collector.start_timer("processing.duration")
                result_data = await self._process_with_retry(
                    processor,
                    ctx.params["payload"],
                    config.parameters,
                    config.parameters.get("retries", settings.default_retries),
                    config.parameters.get("timeout", settings.default_timeout),
                )
                processing_time = monitoring.collector.stop_timer("processing.duration")

                run = self._run_manager.create_run(request.get("entity_id", "unknown"))
                self._run_manager.update_run(
                    run.run_id,
                    phase=RunPhase.COMPLETED,
                    progress=1.0,
                    completed_at=datetime.utcnow(),
                    metadata={"result": result_data},
                )

                self._persist_result(result_data, ctx)

                event_data = self._build_event("task.completed", result_data, run.run_id, ctx)
                self._event_emitter.emit("task.completed", event_data)

                monitoring.collector.increment("requests.success")
                logger.info(f"[{trace_id}] Request {request_id} completed successfully in {processing_time:.2f}s")

                return ProcessingResult(
                    success=True,
                    data=result_data,
                    run_id=run.run_id,
                    execution_time_ms=(time.time() - start_time) * 1000,
                )

            finally:
                self._resource_pool.release(resource)
                logger.debug(f"[{trace_id}] Resource released")

        except ValidationError as e:
            monitoring.collector.increment("requests.validation_error")
            logger.warning(f"[{trace_id}] Validation error: {e}")
            return ProcessingResult(
                success=False,
                error_code=422,
                error_message=str(e),
                error_details=e.details,
                execution_time_ms=(time.time() - start_time) * 1000,
            )

        except TimeoutError as e:
            monitoring.collector.increment("requests.timeout")
            logger.error(f"[{trace_id}] Timeout error: {e}")
            return ProcessingResult(
                success=False,
                error_code=504,
                error_message="上游服务响应超时",
                execution_time_ms=(time.time() - start_time) * 1000,
            )

        except ResourceAcquisitionError as e:
            monitoring.collector.increment("requests.resource_error")
            logger.error(f"[{trace_id}] Resource acquisition error: {e}")
            return ProcessingResult(
                success=False,
                error_code=503,
                error_message="Service unavailable - resource pool exhausted",
                execution_time_ms=(time.time() - start_time) * 1000,
            )

        except Exception as e:
            monitoring.collector.increment("requests.error")
            logger.error(f"[{trace_id}] Unexpected error: {e}", exc_info=True)
            self._rollback_transaction(ctx)
            return ProcessingResult(
                success=False,
                error_code=500,
                error_message="内部处理错误",
                execution_time_ms=(time.time() - start_time) * 1000,
            )

        finally:
            self._record_metrics(ctx)
            self._cleanup_context(ctx)
            logger.debug(f"[{trace_id}] Context cleaned up")

    async def _acquire_resource(self, pool_size: int) -> Any:
        try:
            return await asyncio.wait_for(
                self._resource_pool.acquire(),
                timeout=5.0,
            )
        except asyncio.TimeoutError:
            raise ResourceAcquisitionError("Resource acquisition timed out")

    async def _process_with_retry(
        self,
        processor: AbstractProcessor,
        payload: Dict[str, Any],
        config: Dict[str, Any],
        retries: int,
        timeout: int,
    ) -> Dict[str, Any]:
        last_error = None
        for attempt in range(retries + 1):
            try:
                return await asyncio.wait_for(
                    processor.process(payload, config),
                    timeout=timeout,
                )
            except asyncio.TimeoutError:
                last_error = TimeoutError(f"Processing timed out after {timeout}s")
                if attempt < retries:
                    logger.warning(f"Timeout on attempt {attempt + 1}, retrying...")
                    await asyncio.sleep(0.5 * (attempt + 1))
            except Exception as e:
                last_error = e
                if attempt < retries:
                    logger.warning(f"Error on attempt {attempt + 1}: {e}, retrying...")
                    await asyncio.sleep(0.5 * (attempt + 1))

        raise last_error if last_error else ProcessingError("Processing failed")

    def _persist_result(self, result: Dict[str, Any], ctx: ProcessingContext) -> None:
        ctx.add_metadata("persisted", True)
        logger.debug(f"[{ctx.trace_id}] Result persisted")

    def _build_event(self, event_type: str, data: Dict[str, Any], run_id: str, ctx: ProcessingContext) -> Dict[str, Any]:
        return {
            "event_type": event_type,
            "run_id": run_id,
            "trace_id": ctx.trace_id,
            "request_id": ctx.request_id,
            "namespace": ctx.namespace,
            "data": data,
            "timestamp": datetime.utcnow().isoformat(),
        }

    def _rollback_transaction(self, ctx: ProcessingContext) -> None:
        logger.warning(f"[{ctx.trace_id}] Rolling back transaction")
        ctx.add_metadata("rolled_back", True)

    def _record_metrics(self, ctx: ProcessingContext) -> None:
        monitoring.collector.record_histogram(
            "request.duration",
            (time.time() - ctx.started_at) * 1000,
        )
        monitoring.collector.create_snapshot()

    def _cleanup_context(self, ctx: ProcessingContext) -> None:
        ctx.resources.clear()


_core_processor: Optional[CoreProcessor] = None


def get_core_processor() -> CoreProcessor:
    global _core_processor
    if _core_processor is None:
        _core_processor = CoreProcessor()
    return _core_processor
