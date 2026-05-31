import asyncio
import json
import os
import pickle
import threading
import time
from abc import ABC, abstractmethod
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, AsyncGenerator, Callable, Dict, List, Optional, Tuple, Union
from uuid import uuid4

from src.config import get_settings
from src.logging_ import get_logger
from src.models import (
    APIResponse,
    BaseEntity,
    ConfigDefinition,
    MetricsSnapshot,
    RunInstance,
    RunPhase,
    Task,
    TaskGraph,
)
from src.notification import NotificationManager
from src.scheduler import ExecutionResult, TaskScheduler, TaskStatus
from src.utils.errors import (
    ResourceNotFoundError,
    TaskOrchestratorError,
    TimeoutError,
    ValidationError,
)
from src.utils.helpers import (
    ExecutionContext,
    generate_id,
    sanitize_dict,
    validate_params,
)

logger = get_logger(__name__)


@dataclass
class RequestContext:
    trace_id: str
    namespace: str = "default"
    user_id: Optional[str] = None
    roles: List[str] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: float = field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "namespace": self.namespace,
            "user_id": self.user_id,
            "roles": self.roles,
            "metadata": sanitize_dict(self.metadata),
            "created_at": self.created_at,
        }


@dataclass
class HandlerResponse:
    success: bool
    code: int = 200
    data: Optional[Dict[str, Any]] = None
    message: Optional[str] = None
    error: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "code": self.code,
            "data": self.data,
            "message": self.message,
            "error": self.error,
        }


class EventEmitter:
    def __init__(self):
        self._listeners: Dict[str, List[Callable[..., Any]]] = defaultdict(list)
        self._async_listeners: Dict[str, List[Callable[..., Any]]] = defaultdict(list)

    def on(self, event: str, listener: Callable[..., Any]) -> None:
        self._listeners[event].append(listener)
        logger.debug("Registered sync listener for event: %s", event)

    def on_async(self, event: str, listener: Callable[..., Any]) -> None:
        self._async_listeners[event].append(listener)
        logger.debug("Registered async listener for event: %s", event)

    def emit(self, event: str, *args: Any, **kwargs: Any) -> None:
        for listener in self._listeners.get(event, []):
            try:
                listener(*args, **kwargs)
            except Exception as e:
                logger.error("Listener error for event %s: %s", event, e)

    async def emit_async(self, event: str, *args: Any, **kwargs: Any) -> None:
        sync_tasks = [
            asyncio.to_thread(listener, *args, **kwargs)
            for listener in self._listeners.get(event, [])
        ]

        async_tasks = [
            listener(*args, **kwargs)
            for listener in self._async_listeners.get(event, [])
        ]

        all_tasks = sync_tasks + async_tasks
        if all_tasks:
            await asyncio.gather(*all_tasks, return_exceptions=True)

    def remove_listener(self, event: str, listener: Callable[..., Any]) -> bool:
        if event in self._listeners and listener in self._listeners[event]:
            self._listeners[event].remove(listener)
            return True
        if event in self._async_listeners and listener in self._async_listeners[event]:
            self._async_listeners[event].remove(listener)
            return True
        return False

    def clear_listeners(self, event: Optional[str] = None) -> None:
        if event:
            self._listeners.pop(event, None)
            self._async_listeners.pop(event, None)
        else:
            self._listeners.clear()
            self._async_listeners.clear()


class ResourceManager:
    def __init__(self, max_resources: int = 100):
        self._resources: Dict[str, Any] = {}
        self._locks: Dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
        self._pool_semaphores: Dict[str, asyncio.Semaphore] = {}
        self._max_resources = max_resources
        self._resource_usage: Dict[str, List[float]] = defaultdict(list)

    async def acquire(self, resource_id: str, timeout: float = 30.0) -> Any:
        if resource_id not in self._pool_semaphores:
            self._pool_semaphores[resource_id] = asyncio.Semaphore(self._max_resources)

        semaphore = self._pool_semaphores[resource_id]
        try:
            acquired = await asyncio.wait_for(semaphore.acquire(), timeout=timeout)
            if not acquired:
                raise TimeoutError(f"Failed to acquire resource {resource_id} within {timeout}s")
        except asyncio.TimeoutError:
            raise TimeoutError(f"Resource {resource_id} acquisition timed out after {timeout}s")

        lock = self._locks[resource_id]
        async with lock:
            resource = self._resources.get(resource_id)
            usage = self._resource_usage[resource_id]
            usage.append(time.time())
            if len(usage) > 1000:
                self._resource_usage[resource_id] = usage[-500:]

            logger.debug("Acquired resource: %s", resource_id)
            return resource

    def release(self, resource_id: str) -> None:
        if resource_id in self._pool_semaphores:
            self._pool_semaphores[resource_id].release()
            logger.debug("Released resource: %s", resource_id)

    def register(self, resource_id: str, resource: Any) -> None:
        self._resources[resource_id] = resource
        logger.info("Registered resource: %s", resource_id)

    def unregister(self, resource_id: str) -> bool:
        if resource_id in self._resources:
            del self._resources[resource_id]
            self._locks.pop(resource_id, None)
            self._pool_semaphores.pop(resource_id, None)
            self._resource_usage.pop(resource_id, None)
            logger.info("Unregistered resource: %s", resource_id)
            return True
        return False

    def get_usage_stats(self, resource_id: Optional[str] = None) -> Dict[str, Any]:
        if resource_id:
            usage = self._resource_usage.get(resource_id, [])
            return {
                "resource_id": resource_id,
                "usage_count": len(usage),
                "last_used": usage[-1] if usage else None,
            }
        return {
            rid: {
                "usage_count": len(usage),
                "last_used": usage[-1] if usage else None,
            }
            for rid, usage in self._resource_usage.items()
        }


class WALManager:
    def __init__(self, wal_dir: str = "./wal"):
        self.wal_dir = Path(wal_dir)
        self.wal_dir.mkdir(parents=True, exist_ok=True)
        self._current_log: Optional[Path] = None
        self._lock = threading.Lock()
        self._max_log_size = 100 * 1024 * 1024
        self._rotate_log()

    def _rotate_log(self) -> None:
        timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        self._current_log = self.wal_dir / f"wal_{timestamp}.log"

    def _check_rotate(self) -> None:
        if self._current_log and self._current_log.exists():
            if self._current_log.stat().st_size >= self._max_log_size:
                self._rotate_log()

    def write(self, entry: Dict[str, Any]) -> None:
        with self._lock:
            self._check_rotate()
            if self._current_log is None:
                self._rotate_log()

            entry["timestamp"] = time.time()
            entry["seq"] = generate_id("seq")

            with open(self._current_log, "a") as f:
                f.write(json.dumps(entry) + "\n")

            logger.debug("WAL entry written: %s", entry.get("type", "unknown"))

    def write_operation(
        self,
        operation_type: str,
        entity_id: str,
        data: Dict[str, Any],
    ) -> None:
        self.write({
            "type": operation_type,
            "entity_id": entity_id,
            "data": sanitize_dict(data),
        })

    def read_recent(self, limit: int = 100) -> List[Dict[str, Any]]:
        entries: List[Dict[str, Any]] = []
        log_files = sorted(self.wal_dir.glob("wal_*.log"), reverse=True)

        for log_file in log_files:
            if len(entries) >= limit:
                break
            try:
                with open(log_file, "r") as f:
                    lines = f.readlines()
                    for line in reversed(lines):
                        if len(entries) >= limit:
                            break
                        try:
                            entries.append(json.loads(line))
                        except json.JSONDecodeError:
                            continue
            except Exception as e:
                logger.warning("Failed to read WAL file %s: %s", log_file, e)

        return entries

    def replay(self, handler: Callable[[Dict[str, Any]], None]) -> int:
        count = 0
        log_files = sorted(self.wal_dir.glob("wal_*.log"))

        for log_file in log_files:
            try:
                with open(log_file, "r") as f:
                    for line in f:
                        try:
                            entry = json.loads(line)
                            handler(entry)
                            count += 1
                        except json.JSONDecodeError:
                            continue
            except Exception as e:
                logger.warning("Failed to replay WAL file %s: %s", log_file, e)

        logger.info("Replayed %d WAL entries", count)
        return count

    def cleanup_old(self, days: int = 7) -> int:
        removed = 0
        cutoff = time.time() - (days * 86400)

        for log_file in self.wal_dir.glob("wal_*.log"):
            try:
                if log_file.stat().st_mtime < cutoff:
                    log_file.unlink()
                    removed += 1
            except Exception as e:
                logger.warning("Failed to remove old WAL file %s: %s", log_file, e)

        logger.info("Removed %d old WAL files", removed)
        return removed


class TaskOrchestrator:
    def __init__(
        self,
        scheduler: Optional[TaskScheduler] = None,
        notification_manager: Optional[NotificationManager] = None,
    ):
        self.settings = get_settings()
        self.scheduler = scheduler or TaskScheduler()
        self.notification_manager = notification_manager or NotificationManager()
        self._entities: Dict[str, BaseEntity] = {}
        self._configs: Dict[str, ConfigDefinition] = {}
        self._runs: Dict[str, RunInstance] = {}
        self._metrics_snapshots: List[MetricsSnapshot] = []
        self._event_emitter = EventEmitter()
        self._wal = WALManager()

    def create_entity(
        self,
        entity_type: str,
        config: Dict[str, Any],
        labels: Dict[str, str],
    ) -> BaseEntity:
        entity = BaseEntity(
            type=entity_type,
            attributes={
                "config": config,
                "labels": labels,
            },
        )
        self._entities[entity.id] = entity
        self._wal.write_operation("entity_created", entity.id, entity.model_dump())
        logger.info("Created entity: %s (%s)", entity.id, entity_type)
        return entity

    def get_entity(self, entity_id: str) -> BaseEntity:
        if entity_id not in self._entities:
            raise ResourceNotFoundError(f"Entity not found: {entity_id}")
        return self._entities[entity_id]

    def update_entity_status(self, entity_id: str, status: str) -> BaseEntity:
        entity = self.get_entity(entity_id)
        entity.status = status
        entity.updated_at = datetime.utcnow()
        self._wal.write_operation(
            "entity_updated",
            entity_id,
            {"status": status},
        )
        return entity

    def list_entities(
        self,
        entity_type: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 100,
    ) -> List[BaseEntity]:
        entities = list(self._entities.values())
        if entity_type:
            entities = [e for e in entities if e.type == entity_type]
        if status:
            entities = [e for e in entities if e.status == status]
        return entities[:limit]

    def load_config(self, namespace: str) -> ConfigDefinition:
        if namespace not in self._configs:
            self._configs[namespace] = ConfigDefinition(
                namespace=namespace,
                parameters={"timeout": 30, "retries": 3},
            )
        return self._configs[namespace]

    def update_config(
        self,
        namespace: str,
        parameters: Dict[str, Any],
        version: Optional[int] = None,
    ) -> ConfigDefinition:
        existing = self._configs.get(namespace)
        new_version = version or (existing.version + 1 if existing else 1)

        config = ConfigDefinition(
            namespace=namespace,
            version=new_version,
            parameters=parameters,
            enabled=True,
        )
        self._configs[namespace] = config
        self._wal.write_operation(
            "config_updated",
            namespace,
            {"version": new_version, "parameters": parameters},
        )
        return config

    def persist_result(self, result: Dict[str, Any]) -> None:
        entity_id = result.get("entity_id")
        if entity_id:
            self._wal.write_operation("result_persisted", entity_id, result)
        logger.info("Result persisted: %s", sanitize_dict(result))

    async def process_core(
        self,
        payload: Dict[str, Any],
        rules: Dict[str, Any],
        context: ExecutionContext,
    ) -> Dict[str, Any]:
        logger.info(
            "Processing core logic",
            extra={"trace_id": context.trace_id},
        )

        await asyncio.sleep(0.05)

        result = {
            "processed": True,
            "timestamp": datetime.utcnow().isoformat(),
            "payload_hash": hash(json.dumps(payload, sort_keys=True)),
            "rules_applied": list(rules.keys()),
            "trace_id": context.trace_id,
        }

        self._event_emitter.emit("task.processed", result)
        await self._event_emitter.emit_async("task.processed.async", result)

        return result

    def build_event(self, event_name: str, result: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "event": event_name,
            "data": result,
            "timestamp": datetime.utcnow().isoformat(),
            "id": generate_id("evt"),
        }

    def rollback_transaction(self, context: ExecutionContext) -> None:
        logger.warning(
            "Rolling back transaction",
            extra={"trace_id": context.trace_id},
        )
        self._event_emitter.emit("transaction.rollback", context.to_dict())

    def record_metrics(self, context: ExecutionContext) -> None:
        snapshot = MetricsSnapshot(
            metrics={
                "elapsed_time": context.get_elapsed_time(),
                "error_count": len(context.errors),
                **context.metrics,
            },
            dimensions=context.tags,
        )
        self._metrics_snapshots.append(snapshot)

        if len(self._metrics_snapshots) > 10000:
            self._metrics_snapshots = self._metrics_snapshots[-5000:]

        logger.debug("Metrics recorded: %s", snapshot.metrics)

    def get_metrics(self, limit: int = 100) -> List[MetricsSnapshot]:
        return self._metrics_snapshots[-limit:]

    async def execute_graph(
        self,
        task_graph: TaskGraph,
        context: Optional[ExecutionContext] = None,
    ) -> Dict[str, ExecutionResult]:
        context = context or ExecutionContext()

        self.scheduler.reset()
        self.scheduler.register_task_graph(task_graph)

        results = await self.scheduler.run_all(context)

        await self._event_emitter.emit_async("graph.completed", {
            "graph_id": task_graph.graph_id,
            "results": {k: v.to_dict() if hasattr(v, "to_dict") else str(v) for k, v in results.items()},
        })

        return results

    def get_run_status(self, entity_id: str) -> Optional[RunInstance]:
        return self._runs.get(entity_id)

    def on_event(self, event: str, listener: Callable[..., Any]) -> None:
        self._event_emitter.on(event, listener)

    def on_event_async(self, event: str, listener: Callable[..., Any]) -> None:
        self._event_emitter.on_async(event, listener)


class CoreHandler:
    def __init__(
        self,
        orchestrator: Optional[TaskOrchestrator] = None,
        resource_manager: Optional[ResourceManager] = None,
    ):
        self.settings = get_settings()
        self.orchestrator = orchestrator or TaskOrchestrator()
        self.resource_manager = resource_manager or ResourceManager()

    def init_context(self, trace_id: Optional[str] = None) -> ExecutionContext:
        return ExecutionContext(trace_id=trace_id)

    async def execute_handler(
        self,
        request: Dict[str, Any],
    ) -> HandlerResponse:
        trace_id = request.get("traceId") or generate_id("trace")
        ctx = self.init_context(trace_id)

        logger.info(
            "Processing request",
            extra={"trace_id": ctx.trace_id, "request": sanitize_dict(request)},
        )

        try:
            validate_params(request, ["params", "namespace"])
            params = request["params"]
            namespace = request["namespace"]
            payload = request.get("payload", {})

            config = self.orchestrator.load_config(namespace)
            ctx.add_tag("namespace", namespace)

            pool_size = config.parameters.get("poolSize", 10)
            resource_id = f"pool_{namespace}"
            resource = await self.resource_manager.acquire(resource_id, timeout=30.0)

            try:
                rules = config.parameters.get("rules", {})
                result = await self.orchestrator.process_core(payload, rules, ctx)

                self.orchestrator.persist_result(result)

                event = self.orchestrator.build_event("task.completed", result)
                await self.orchestrator._event_emitter.emit_async("task.completed", event)

                return HandlerResponse(
                    success=True,
                    code=200,
                    data=result,
                    message="Success",
                )

            finally:
                self.resource_manager.release(resource_id)

        except ValidationError as e:
            return HandlerResponse(
                success=False,
                code=422,
                error="Validation failed",
                data=e.details,
            )

        except TimeoutError as e:
            return HandlerResponse(
                success=False,
                code=504,
                error="上游服务响应超时",
                data=e.details,
            )

        except Exception as e:
            self.orchestrator.rollback_transaction(ctx)
            logger.exception(
                "Handler execution failed",
                extra={"trace_id": ctx.trace_id},
            )
            return HandlerResponse(
                success=False,
                code=500,
                error="内部处理错误",
                data={"error": str(e)},
            )

        finally:
            self.orchestrator.record_metrics(ctx)
            ctx.cleanup()

    async def create_resource(
        self,
        request: Dict[str, Any],
    ) -> HandlerResponse:
        try:
            validate_params(request, ["type"])

            entity_type = request["type"]
            config = request.get("config", {})
            labels = request.get("labels", {})

            entity = self.orchestrator.create_entity(entity_type, config, labels)

            return HandlerResponse(
                success=True,
                code=201,
                data={"id": entity.id, "status": entity.status.value},
                message="Resource created successfully",
            )

        except ValidationError as e:
            return HandlerResponse(
                success=False,
                code=422,
                error=str(e),
                data=e.details,
            )

        except Exception as e:
            logger.exception("Failed to create resource")
            return HandlerResponse(
                success=False,
                code=500,
                error="Failed to create resource",
                data={"error": str(e)},
            )

    async def get_resource_status(
        self,
        resource_id: str,
    ) -> HandlerResponse:
        try:
            entity = self.orchestrator.get_entity(resource_id)
            progress = self.orchestrator.scheduler.get_progress()

            run_instance = self.orchestrator.get_run_status(resource_id)
            if run_instance:
                progress = run_instance.progress

            return HandlerResponse(
                success=True,
                code=200,
                data={
                    "id": resource_id,
                    "status": entity.status.value,
                    "progress": progress,
                    "type": entity.type.value,
                    "attributes": entity.attributes,
                },
            )

        except ResourceNotFoundError as e:
            return HandlerResponse(
                success=False,
                code=404,
                error=str(e),
            )

        except Exception as e:
            logger.exception("Failed to get resource status")
            return HandlerResponse(
                success=False,
                code=500,
                error="Failed to get resource status",
                data={"error": str(e)},
            )

    async def batch_operation(
        self,
        operations: List[Dict[str, Any]],
    ) -> HandlerResponse:
        batch_id = generate_id("batch")
        results: List[Dict[str, Any]] = []

        for op in operations:
            try:
                action = op.get("action")
                resource_id = op.get("id")
                params = op.get("parameters", {})

                if action == "stop" and resource_id:
                    entity = self.orchestrator.update_entity_status(resource_id, "stopped")
                    results.append({
                        "id": resource_id,
                        "action": action,
                        "success": True,
                        "status": entity.status.value,
                    })
                elif action == "start" and resource_id:
                    entity = self.orchestrator.update_entity_status(resource_id, "running")
                    results.append({
                        "id": resource_id,
                        "action": action,
                        "success": True,
                        "status": entity.status.value,
                    })
                else:
                    results.append({
                        "id": resource_id,
                        "action": action,
                        "success": False,
                        "error": f"Unknown action: {action}",
                    })

            except Exception as e:
                results.append({
                    "id": op.get("id"),
                    "action": op.get("action"),
                    "success": False,
                    "error": str(e),
                })

        return HandlerResponse(
            success=True,
            code=200,
            data={
                "batch_id": batch_id,
                "results": results,
            },
        )

    def get_statistics(self) -> Dict[str, Any]:
        return {
            "entities": len(self.orchestrator._entities),
            "configs": len(self.orchestrator._configs),
            "metrics_snapshots": len(self.orchestrator._metrics_snapshots),
            "resource_usage": self.resource_manager.get_usage_stats(),
        }
