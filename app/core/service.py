from typing import Optional, List, Dict, Any, Callable, Awaitable
from uuid import UUID
from datetime import datetime, timezone
import asyncio
import time
from collections import defaultdict

from app.logging import get_logger
from app.utils import generate_short_id
from app.exceptions import ValidationError, NotFoundError, ConflictError
from app.config import settings

logger = get_logger(__name__)


class ExecutionContext:
    def __init__(self, trace_id: str):
        self.trace_id = trace_id
        self.start_time = time.time()
        self.data: Dict[str, Any] = {}
        self.errors: List[Exception] = []

    def elapsed_ms(self) -> float:
        return (time.time() - self.start_time) * 1000

    def add_error(self, error: Exception) -> None:
        self.errors.append(error)

    def has_errors(self) -> bool:
        return len(self.errors) > 0


class EventBus:
    _instance = None
    _handlers: Dict[str, List[Callable[[Dict[str, Any]], Awaitable[None]]]]

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._handlers = defaultdict(list)
        return cls._instance

    async def subscribe(self, event_name: str, handler: Callable[[Dict[str, Any]], Awaitable[None]]) -> None:
        self._handlers[event_name].append(handler)
        logger.debug("Subscribed to event", event_name=event_name, handler_count=len(self._handlers[event_name]))

    async def unsubscribe(self, event_name: str, handler: Callable[[Dict[str, Any]], Awaitable[None]]) -> None:
        if handler in self._handlers[event_name]:
            self._handlers[event_name].remove(handler)
            logger.debug("Unsubscribed from event", event_name=event_name)

    async def emit(self, event_name: str, payload: Dict[str, Any]) -> None:
        if event_name not in self._handlers:
            return

        logger.debug("Emitting event", event_name=event_name, handler_count=len(self._handlers[event_name]))

        for handler in self._handlers[event_name]:
            try:
                await handler(payload)
            except Exception as e:
                logger.error(
                    "Event handler failed",
                    event_name=event_name,
                    error=str(e),
                    exc_info=True,
                )


class ResourceManager:
    def __init__(self):
        self._resources: Dict[str, Dict[str, Any]] = {}
        self._locks: Dict[str, asyncio.Lock] = {}

    async def acquire(self, resource_type: str, resource_id: str) -> Dict[str, Any]:
        key = f"{resource_type}:{resource_id}"
        if key not in self._locks:
            self._locks[key] = asyncio.Lock()

        await self._locks[key].acquire()

        if key not in self._resources:
            self._resources[key] = {
                "type": resource_type,
                "id": resource_id,
                "acquired_at": datetime.now(timezone.utc),
                "status": "acquired",
            }

        logger.debug("Resource acquired", resource_type=resource_type, resource_id=resource_id)
        return self._resources[key]

    async def release(self, resource_type: str, resource_id: str) -> None:
        key = f"{resource_type}:{resource_id}"
        if key in self._locks and self._locks[key].locked():
            self._locks[key].release()
            if key in self._resources:
                del self._resources[key]
            logger.debug("Resource released", resource_type=resource_type, resource_id=resource_id)

    def is_acquired(self, resource_type: str, resource_id: str) -> bool:
        key = f"{resource_type}:{resource_id}"
        return key in self._resources and self._resources[key]["status"] == "acquired"


class TaskExecutionService:
    def __init__(self):
        self.resource_manager = ResourceManager()
        self.event_bus = EventBus()
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._task_results: Dict[str, Dict[str, Any]] = {}

    async def execute_handler(
        self,
        task_type: str,
        namespace: str,
        payload: Dict[str, Any],
        priority: int = 2,
        callback_url: Optional[str] = None,
        user_id: Optional[UUID] = None,
    ) -> Dict[str, Any]:
        trace_id = generate_short_id("trc_")
        ctx = ExecutionContext(trace_id)

        logger.info(
            "Task execution started",
            trace_id=trace_id,
            task_type=task_type,
            namespace=namespace,
            priority=priority,
        )

        try:
            self._validate_params(payload)
            config = self._load_config(namespace)

            resource = await self.resource_manager.acquire(task_type, namespace)

            try:
                result = await self._process_core(payload, config, ctx)
                self._persist_result(result, ctx)
                await self.event_bus.emit(
                    "task.completed",
                    {"trace_id": trace_id, "result": result, "task_type": task_type},
                )

                response_data = {
                    "task_id": trace_id,
                    "status": "completed",
                    "run_id": generate_short_id("run_"),
                    "result": result,
                }
                self._task_results[trace_id] = response_data

                logger.info(
                    "Task execution completed",
                    trace_id=trace_id,
                    duration_ms=ctx.elapsed_ms(),
                )

                return response_data

            finally:
                await self.resource_manager.release(task_type, namespace)

        except ValidationError as e:
            error_response = {
                "task_id": trace_id,
                "status": "failed",
                "error": {
                    "code": 422,
                    "message": str(e),
                    "details": getattr(e, "details", {}),
                },
            }
            self._task_results[trace_id] = error_response
            logger.warning(
                "Task validation failed",
                trace_id=trace_id,
                error=str(e),
            )
            return error_response

        except asyncio.TimeoutError:
            error_response = {
                "task_id": trace_id,
                "status": "failed",
                "error": {
                    "code": 504,
                    "message": "上游服务响应超时",
                },
            }
            self._task_results[trace_id] = error_response
            logger.error(
                "Task execution timeout",
                trace_id=trace_id,
            )
            return error_response

        except Exception as e:
            await self._rollback_transaction(ctx)
            error_response = {
                "task_id": trace_id,
                "status": "failed",
                "error": {
                    "code": 500,
                    "message": "内部处理错误",
                    "details": {"error_type": type(e).__name__},
                },
            }
            self._task_results[trace_id] = error_response
            logger.error(
                "Task execution failed",
                trace_id=trace_id,
                error=str(e),
                exc_info=True,
            )
            return error_response

        finally:
            await self._record_metrics(ctx)

    def _validate_params(self, params: Dict[str, Any]) -> None:
        if not isinstance(params, dict):
            raise ValidationError("Parameters must be a dictionary")

        required_fields = []
        for field in required_fields:
            if field not in params:
                raise ValidationError(f"Missing required parameter: {field}")

        logger.debug("Parameters validated", param_count=len(params))

    def _load_config(self, namespace: str) -> Dict[str, Any]:
        default_config = {
            "pool_size": 10,
            "timeout": 30,
            "retries": 3,
            "rules": {
                "validation_enabled": True,
                "audit_enabled": True,
            },
        }
        return default_config

    async def _process_core(
        self,
        payload: Dict[str, Any],
        config: Dict[str, Any],
        ctx: ExecutionContext,
    ) -> Dict[str, Any]:
        logger.debug("Processing core logic", payload_size=len(str(payload)))

        await asyncio.sleep(0.01)

        result = {
            "processed": True,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "config_applied": config.get("rules", {}),
            "payload_hash": hash(str(payload)),
            "metadata": {
                "processing_time_ms": ctx.elapsed_ms(),
            },
        }

        return result

    def _persist_result(self, result: Dict[str, Any], ctx: ExecutionContext) -> None:
        logger.debug("Result persisted", trace_id=ctx.trace_id)

    async def _rollback_transaction(self, ctx: ExecutionContext) -> None:
        logger.warning(
            "Rolling back transaction",
            trace_id=ctx.trace_id,
            error_count=len(ctx.errors),
        )

    async def _record_metrics(self, ctx: ExecutionContext) -> None:
        from app.monitoring import MetricsCollector

        try:
            collector = MetricsCollector()
            collector.observe_histogram(
                "task_execution_duration_seconds",
                ctx.elapsed_ms() / 1000,
                labels={"status": "failed" if ctx.has_errors() else "success"},
            )
            collector.increment_counter(
                "task_execution_total",
                labels={"status": "failed" if ctx.has_errors() else "success"},
            )
        except Exception as e:
            logger.debug("Failed to record metrics", error=str(e))

    async def create_resource(
        self,
        resource_type: str,
        config: Dict[str, Any],
        labels: Dict[str, str],
        namespace: str = "default",
    ) -> Dict[str, Any]:
        resource_id = generate_short_id("rsc_")

        resource = {
            "id": resource_id,
            "type": resource_type,
            "status": "provisioning",
            "config": config,
            "labels": labels,
            "namespace": namespace,
            "created_at": datetime.now(timezone.utc),
            "metadata": {},
        }

        logger.info(
            "Resource created",
            resource_id=resource_id,
            type=resource_type,
            namespace=namespace,
        )

        resource["status"] = "active"
        return resource

    async def get_resource_status(self, resource_id: str) -> Dict[str, Any]:
        return {
            "id": resource_id,
            "status": "completed",
            "progress": 1.0,
            "phase": "running",
            "started_at": datetime.now(timezone.utc),
            "completed_at": None,
            "error_detail": None,
            "metadata": {},
        }

    async def batch_operations(
        self,
        operations: List[Dict[str, Any]],
        timeout_seconds: int = 60,
    ) -> Dict[str, Any]:
        batch_id = generate_short_id("batch_")
        results = []
        success_count = 0
        failed_count = 0

        logger.info(
            "Batch operation started",
            batch_id=batch_id,
            operation_count=len(operations),
            timeout_seconds=timeout_seconds,
        )

        for op in operations:
            try:
                action = op.get("action")
                resource_id = op.get("id")
                params = op.get("params", {})

                result = await self._execute_operation(action, resource_id, params)
                results.append(
                    {
                        "id": resource_id,
                        "action": action,
                        "status": "success",
                        "message": f"Operation {action} completed",
                        "result": result,
                    }
                )
                success_count += 1
            except Exception as e:
                results.append(
                    {
                        "id": op.get("id"),
                        "action": op.get("action"),
                        "status": "failed",
                        "message": str(e),
                        "result": None,
                    }
                )
                failed_count += 1

        logger.info(
            "Batch operation completed",
            batch_id=batch_id,
            success_count=success_count,
            failed_count=failed_count,
        )

        return {
            "batch_id": batch_id,
            "results": results,
            "total_count": len(operations),
            "success_count": success_count,
            "failed_count": failed_count,
        }

    async def _execute_operation(
        self,
        action: str,
        resource_id: str,
        params: Dict[str, Any],
    ) -> Dict[str, Any]:
        supported_actions = ["start", "stop", "restart", "delete", "query"]

        if action not in supported_actions:
            raise ValidationError(f"Unsupported operation: {action}")

        logger.debug(
            "Executing operation",
            action=action,
            resource_id=resource_id,
        )

        return {
            "action": action,
            "resource_id": resource_id,
            "executed_at": datetime.now(timezone.utc).isoformat(),
            "params": params,
        }

    def get_task_result(self, task_id: str) -> Optional[Dict[str, Any]]:
        return self._task_results.get(task_id)

    async def list_tasks(
        self,
        status: Optional[str] = None,
        task_type: Optional[str] = None,
        limit: int = 100,
    ) -> List[Dict[str, Any]]:
        tasks = list(self._task_results.values())
        if status:
            tasks = [t for t in tasks if t.get("status") == status]
        if task_type:
            tasks = [t for t in tasks if t.get("task_type") == task_type]
        return tasks[:limit]
