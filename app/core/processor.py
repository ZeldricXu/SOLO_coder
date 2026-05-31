import asyncio
import threading
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, AsyncIterator, Callable, Dict, List, Optional

from app.config.manager import get_config_manager
from app.monitoring.metrics import get_metrics_collector, MetricType
from app.monitoring.tracing import get_tracer, SpanStatus


class EventType(str, Enum):
    TASK_STARTED = "task.started"
    TASK_COMPLETED = "task.completed"
    TASK_FAILED = "task.failed"
    RESOURCE_ACQUIRED = "resource.acquired"
    RESOURCE_RELEASED = "resource.released"
    CONFIG_UPDATED = "config.updated"


@dataclass
class Event:
    event_type: str
    payload: Dict[str, Any]
    timestamp: datetime = field(default_factory=datetime.utcnow)
    event_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    trace_id: Optional[str] = None


@dataclass
class ProcessingContext:
    trace_id: str
    request_id: str
    namespace: str = "default"
    start_time: datetime = field(default_factory=datetime.utcnow)
    attributes: Dict[str, Any] = field(default_factory=dict)
    errors: List[str] = field(default_factory=list)
    resources_acquired: List[str] = field(default_factory=list)

    def set_attribute(self, key: str, value: Any) -> None:
        self.attributes[key] = value

    def get_attribute(self, key: str, default: Any = None) -> Any:
        return self.attributes.get(key, default)

    def add_error(self, message: str) -> None:
        self.errors.append(message)

    def record_elapsed_ms(self) -> float:
        return (datetime.utcnow() - self.start_time).total_seconds() * 1000


class EventEmitter:
    def __init__(self):
        self._listeners: Dict[str, List[Callable[[Event], None]]] = {}
        self._async_listeners: Dict[str, List[Callable[[Event], Any]]] = {}
        self._lock = threading.Lock()

    def on(self, event_type: str, listener: Callable[[Event], None]) -> None:
        with self._lock:
            if event_type not in self._listeners:
                self._listeners[event_type] = []
            self._listeners[event_type].append(listener)

    def on_async(self, event_type: str, listener: Callable[[Event], Any]) -> None:
        with self._lock:
            if event_type not in self._async_listeners:
                self._async_listeners[event_type] = []
            self._async_listeners[event_type].append(listener)

    def off(self, event_type: str, listener: Callable) -> None:
        with self._lock:
            if event_type in self._listeners:
                if listener in self._listeners[event_type]:
                    self._listeners[event_type].remove(listener)
            if event_type in self._async_listeners:
                if listener in self._async_listeners[event_type]:
                    self._async_listeners[event_type].remove(listener)

    def emit(self, event: Event) -> None:
        with self._lock:
            listeners = list(self._listeners.get(event.event_type, []))
            listeners.extend(self._listeners.get("*", []))
        for listener in listeners:
            try:
                listener(event)
            except Exception:
                pass

    async def emit_async(self, event: Event) -> None:
        self.emit(event)
        with self._lock:
            async_listeners = list(self._async_listeners.get(event.event_type, []))
            async_listeners.extend(self._async_listeners.get("*", []))
        for listener in async_listeners:
            try:
                result = listener(event)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass

    def once(self, event_type: str, listener: Callable[[Event], None]) -> None:
        def wrapper(event: Event):
            try:
                listener(event)
            finally:
                self.off(event_type, wrapper)
        self.on(event_type, wrapper)


class BusinessRuleEngine:
    def __init__(self):
        self._rules: Dict[str, List[Callable]] = {}
        self._lock = threading.Lock()

    def register_rule(self, rule_name: str, rule_func: Callable) -> None:
        with self._lock:
            if rule_name not in self._rules:
                self._rules[rule_name] = []
            self._rules[rule_name].append(rule_func)

    def execute_rules(self, rule_name: str, context: ProcessingContext, data: Any) -> Any:
        with self._lock:
            rules = list(self._rules.get(rule_name, []))
        result = data
        for rule in rules:
            try:
                result = rule(context, result)
            except Exception as e:
                context.add_error(f"Rule '{rule_name}' failed: {e}")
        return result

    def list_rules(self) -> Dict[str, int]:
        with self._lock:
            return {name: len(rules) for name, rules in self._rules.items()}


class ValidationError(Exception):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message)
        self.details = details or {}


class TimeoutError(Exception):
    pass


class RequestProcessor:
    def __init__(self):
        self.emitter = EventEmitter()
        self.rule_engine = BusinessRuleEngine()
        self._resource_pool: Dict[str, Any] = {}
        self._lock = threading.Lock()

    def init_context(self, trace_id: Optional[str] = None) -> ProcessingContext:
        return ProcessingContext(
            trace_id=trace_id or uuid.uuid4().hex,
            request_id=uuid.uuid4().hex
        )

    def validate_params(self, params: Dict[str, Any], schema: Optional[Dict[str, Any]] = None) -> None:
        if not isinstance(params, dict):
            raise ValidationError("参数必须是字典类型", {"params_type": type(params).__name__})
        if schema:
            for key, config in schema.items():
                required = config.get("required", False)
                param_type = config.get("type")
                if required and key not in params:
                    raise ValidationError(f"缺少必需参数: {key}", {"missing": key})
                if key in params and param_type:
                    value = params[key]
                    if param_type == "string" and not isinstance(value, str):
                        raise ValidationError(f"参数 '{key}' 必须是字符串", {"key": key})
                    elif param_type == "integer" and not isinstance(value, (int, float)):
                        raise ValidationError(f"参数 '{key}' 必须是数字", {"key": key})
                    elif param_type == "boolean" and not isinstance(value, bool):
                        raise ValidationError(f"参数 '{key}' 必须是布尔值", {"key": key})
                    elif param_type == "dict" and not isinstance(value, dict):
                        raise ValidationError(f"参数 '{key}' 必须是字典", {"key": key})

    def load_config(self, namespace: str) -> Dict[str, Any]:
        config_manager = get_config_manager()
        return config_manager.get(namespace)

    def acquire_resource(self, pool_size: int) -> Dict[str, Any]:
        return {
            "pool_size": pool_size,
            "acquired_at": datetime.utcnow().isoformat()
        }

    def release_resource(self, resource: Dict[str, Any]) -> None:
        pass

    def process_core(self, payload: Dict[str, Any], rules: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        processed = {
            "original": payload,
            "processed_at": datetime.utcnow().isoformat(),
            "status": "processed"
        }
        if rules:
            processed["rules_applied"] = list(rules.keys())
        return processed

    def persist_result(self, result: Any) -> None:
        pass

    async def execute_handler(
        self,
        request: Dict[str, Any],
        schema: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        tracer = get_tracer()
        metrics = get_metrics_collector()
        trace_id = request.get("traceId")
        ctx = self.init_context(trace_id)

        with tracer.span("execute_handler", trace_id=ctx.trace_id):
            try:
                params = request.get("params", {})
                with tracer.span("validate_params", trace_id=ctx.trace_id):
                    self.validate_params(params, schema)

                namespace = request.get("namespace", "default")
                ctx.namespace = namespace
                with tracer.span("load_config", trace_id=ctx.trace_id):
                    config = self.load_config(namespace)
                    ctx.set_attribute("config", config)

                pool_size = config.get("pool_size", 10)
                with tracer.span("acquire_resource", trace_id=ctx.trace_id):
                    resource = self.acquire_resource(pool_size)
                    ctx.resources_acquired.append("db_connection")

                try:
                    payload = request.get("payload", {})
                    with tracer.span("process_core", trace_id=ctx.trace_id):
                        with metrics.timeit("request_processing_duration", {"namespace": namespace}):
                            result = self.process_core(payload, config.get("rules"))
                            ctx.set_attribute("result", result)

                    with tracer.span("persist_result", trace_id=ctx.trace_id):
                        self.persist_result(result)

                    with tracer.span("emit_event", trace_id=ctx.trace_id):
                        event = Event(
                            event_type=EventType.TASK_COMPLETED,
                            payload={
                                "request_id": ctx.request_id,
                                "result": result
                            },
                            trace_id=ctx.trace_id
                        )
                        await self.emitter.emit_async(event)

                    metrics.increment_counter("requests_succeeded", labels={"namespace": namespace})
                    return self._success_response(result)

                finally:
                    with tracer.span("release_resource", trace_id=ctx.trace_id):
                        self.release_resource(resource)

            except ValidationError as e:
                metrics.increment_counter("requests_failed", labels={"error_type": "validation"})
                tracer.end_span(status=SpanStatus.ERROR, error=str(e))
                return self._error_response(422, "参数校验失败", e.details)
            except TimeoutError:
                metrics.increment_counter("requests_failed", labels={"error_type": "timeout"})
                tracer.end_span(status=SpanStatus.ERROR, error="上游服务响应超时")
                return self._error_response(504, "上游服务响应超时")
            except Exception as e:
                metrics.increment_counter("requests_failed", labels={"error_type": "internal"})
                tracer.end_span(status=SpanStatus.ERROR, error=str(e))
                await self._rollback_transaction(ctx)
                return self._error_response(500, "内部处理错误")
            finally:
                metrics.record_timer("request_total_duration_ms", ctx.record_elapsed_ms())

    async def _rollback_transaction(self, ctx: ProcessingContext) -> None:
        for resource_id in reversed(ctx.resources_acquired):
            try:
                self.release_resource({})
            except Exception:
                pass

    @staticmethod
    def _success_response(data: Any) -> Dict[str, Any]:
        return {
            "code": 200,
            "data": data,
            "message": "success"
        }

    @staticmethod
    def _error_response(code: int, message: str, details: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        response = {
            "code": code,
            "error": message
        }
        if details:
            response["details"] = details
        return response


_processor_instance: Optional[RequestProcessor] = None
_processor_lock = threading.Lock()


def get_request_processor() -> RequestProcessor:
    global _processor_instance
    if _processor_instance is None:
        with _processor_lock:
            if _processor_instance is None:
                _processor_instance = RequestProcessor()
    return _processor_instance


async def execute_handler(
    request: Dict[str, Any],
    schema: Optional[Dict[str, Any]] = None
) -> Dict[str, Any]:
    processor = get_request_processor()
    return await processor.execute_handler(request, schema)


def process_request(
    request: Dict[str, Any],
    schema: Optional[Dict[str, Any]] = None
) -> Dict[str, Any]:
    processor = get_request_processor()
    return asyncio.run(processor.execute_handler(request, schema))


def emit_event(event_type: str, payload: Dict[str, Any], trace_id: Optional[str] = None) -> None:
    processor = get_request_processor()
    event = Event(
        event_type=event_type,
        payload=payload,
        trace_id=trace_id
    )
    processor.emitter.emit(event)
