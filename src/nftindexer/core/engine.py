import asyncio
import time
import traceback
from typing import Any, Callable, Dict, Optional
from contextvars import ContextVar, Token
from collections.abc import AsyncGenerator

from ..config import get_settings
from ..utils import get_logger, generate_id, NFTIndexerError, ValidationError, TimeoutError

logger = get_logger(__name__)

request_id_var: ContextVar[str] = ContextVar("request_id", default="")
trace_id_var: ContextVar[str] = ContextVar("trace_id", default="")


class ExecutionContext:
    def __init__(self, trace_id: Optional[str] = None, request_id: Optional[str] = None):
        self.trace_id = trace_id or generate_id("trace")
        self.request_id = request_id or generate_id("req")
        self.start_time = time.time()
        self.metrics: Dict[str, Any] = {}
        self.events: list[Dict[str, Any]] = []
        self.tokens: list[Token] = []

    def __enter__(self):
        self.tokens.append(request_id_var.set(self.request_id))
        self.tokens.append(trace_id_var.set(self.trace_id))
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        for token in reversed(self.tokens):
            try:
                token.reset()
            except (ValueError, LookupError):
                pass
        self.tokens.clear()

    def record_metric(self, key: str, value: Any) -> None:
        self.metrics[key] = value

    def emit_event(self, event_type: str, data: Dict[str, Any]) -> None:
        self.events.append({
            "type": event_type,
            "data": data,
            "timestamp": time.time(),
        })

    @property
    def elapsed_ms(self) -> float:
        return (time.time() - self.start_time) * 1000


def init_context(trace_id: Optional[str] = None) -> ExecutionContext:
    return ExecutionContext(trace_id=trace_id)


class CoreEngine:
    def __init__(self):
        self.settings = get_settings()
        self._event_handlers: Dict[str, list[Callable]] = {}
        self._initialized = False
        self._modules: Dict[str, Any] = {}

    async def initialize(self) -> None:
        if self._initialized:
            return
        logger.info("Initializing core engine")
        from ..db import init_db
        await init_db()
        self._initialized = True
        logger.info("Core engine initialized")

    async def shutdown(self) -> None:
        if not self._initialized:
            return
        logger.info("Shutting down core engine")
        from ..db import close_db
        await close_db()
        self._initialized = False
        logger.info("Core engine shutdown complete")

    def register_module(self, name: str, module: Any) -> None:
        self._modules[name] = module
        logger.info(f"Registered module: {name}")

    def get_module(self, name: str) -> Optional[Any]:
        return self._modules.get(name)

    def register_event_handler(self, event_type: str, handler: Callable) -> None:
        if event_type not in self._event_handlers:
            self._event_handlers[event_type] = []
        self._event_handlers[event_type].append(handler)

    async def dispatch_event(self, event_type: str, data: Dict[str, Any]) -> None:
        handlers = self._event_handlers.get(event_type, [])
        for handler in handlers:
            try:
                if asyncio.iscoroutinefunction(handler):
                    await handler(data)
                else:
                    handler(data)
            except Exception as e:
                logger.error(f"Error in event handler for {event_type}: {e}")

    async def execute_handler(
        self,
        handler_func: Callable,
        params: Dict[str, Any],
        namespace: str = "default",
        trace_id: Optional[str] = None,
        timeout: Optional[float] = None,
    ) -> Dict[str, Any]:
        ctx = init_context(trace_id)
        with ctx:
            try:
                ctx.record_metric("params", params)
                ctx.record_metric("namespace", namespace)

                if not isinstance(params, dict):
                    raise ValidationError("Parameters must be a dictionary")

                config = self._load_config(namespace)
                ctx.record_metric("config", config)

                result = None
                if asyncio.iscoroutinefunction(handler_func):
                    if timeout:
                        result = await asyncio.wait_for(
                            handler_func(params, config),
                            timeout=timeout,
                        )
                    else:
                        result = await handler_func(params, config)
                else:
                    result = handler_func(params, config)

                ctx.record_metric("result", result)
                ctx.emit_event("task.completed", {"result": result})

                await self._persist_result(result)
                await self.dispatch_event("task.completed", {"result": result})

                return self._build_success_response(result, ctx)

            except ValidationError as e:
                logger.warning(f"Validation error: {e.message}")
                return self._build_error_response(422, e.message, e.details, ctx)

            except asyncio.TimeoutError:
                logger.error("Handler execution timeout")
                return self._build_error_response(504, "上游服务响应超时", {}, ctx)

            except Exception as e:
                logger.error(f"Handler execution error: {e}\n{traceback.format_exc()}")
                await self._rollback_transaction(ctx)
                return self._build_error_response(500, f"内部处理错误: {str(e)}", {}, ctx)

            finally:
                await self._record_metrics(ctx)

    def _load_config(self, namespace: str) -> Dict[str, Any]:
        return {
            "pool_size": self.settings.db.pool_size,
            "rules": {
                "timeout": 30,
                "retries": 3,
            },
        }

    async def _persist_result(self, result: Any) -> None:
        pass

    async def _rollback_transaction(self, ctx: ExecutionContext) -> None:
        logger.warning(f"Rolling back transaction for trace {ctx.trace_id}")

    async def _record_metrics(self, ctx: ExecutionContext) -> None:
        metrics = {
            "trace_id": ctx.trace_id,
            "request_id": ctx.request_id,
            "elapsed_ms": ctx.elapsed_ms,
            "metrics": ctx.metrics,
            "event_count": len(ctx.events),
        }
        logger.debug(f"Recorded metrics: {metrics}")

    def _build_success_response(self, result: Any, ctx: ExecutionContext) -> Dict[str, Any]:
        return {
            "code": 200,
            "message": "success",
            "data": result,
            "request_id": ctx.request_id,
            "trace_id": ctx.trace_id,
            "elapsed_ms": ctx.elapsed_ms,
        }

    def _build_error_response(
        self, code: int, message: str, details: Dict[str, Any], ctx: ExecutionContext
    ) -> Dict[str, Any]:
        return {
            "code": code,
            "message": message,
            "details": details,
            "request_id": ctx.request_id,
            "trace_id": ctx.trace_id,
            "elapsed_ms": ctx.elapsed_ms,
        }

    async def create_resource(
        self, resource_type: str, config: Dict[str, Any], labels: Dict[str, str]
    ) -> Dict[str, Any]:
        resource_id = generate_id("rsc")
        logger.info(f"Creating resource {resource_id} of type {resource_type}")
        return {
            "id": resource_id,
            "type": resource_type,
            "status": "provisioning",
            "config": config,
            "labels": labels,
        }

    async def get_resource_status(self, resource_id: str) -> Dict[str, Any]:
        logger.info(f"Getting status for resource {resource_id}")
        return {
            "id": resource_id,
            "status": "running",
            "progress": 0.8,
        }

    async def batch_operation(self, operations: list[Dict[str, Any]]) -> Dict[str, Any]:
        batch_id = generate_id("batch")
        logger.info(f"Executing batch operation {batch_id} with {len(operations)} operations")

        results = []
        for op in operations:
            try:
                result = await self._execute_operation(op)
                results.append({
                    "id": op.get("id"),
                    "success": True,
                    "data": result,
                })
            except Exception as e:
                results.append({
                    "id": op.get("id"),
                    "success": False,
                    "error": str(e),
                })

        return {
            "batch_id": batch_id,
            "results": results,
        }

    async def _execute_operation(self, operation: Dict[str, Any]) -> Any:
        action = operation.get("action")
        resource_id = operation.get("id")
        params = operation.get("params", {})

        if action == "start":
            return {"status": "started", "resource_id": resource_id}
        elif action == "stop":
            return {"status": "stopped", "resource_id": resource_id}
        elif action == "restart":
            return {"status": "restarted", "resource_id": resource_id}
        else:
            raise ValidationError(f"Unknown action: {action}")


_core_engine: Optional[CoreEngine] = None


def get_core_engine() -> CoreEngine:
    global _core_engine
    if _core_engine is None:
        _core_engine = CoreEngine()
    return _core_engine
