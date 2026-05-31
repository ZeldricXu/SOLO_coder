import asyncio
import uuid
import signal
from abc import ABC, abstractmethod
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Callable, Coroutine, TypeVar, Generic, Union
from enum import Enum
from dataclasses import dataclass, field
from collections import defaultdict, deque
from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor
from functools import partial

from .logging_module import get_logger
from .config_module import get_app_config, get_config_manager
from .event_store import EventStore, EventType, get_event_store
from .notification_module import NotificationManager, NotificationPriority, NotificationChannel, get_notification_manager
from .fault_injection import FaultInjectionManager, InjectionScope, get_fault_injection_manager
from .audit_module import CommandAuditManager, CommandType, get_command_audit_manager
from .data_access import DatabaseManager, Entity, EntityStatus, get_db_manager, get_entity_repository, get_run_repository

logger = get_logger(__name__)

T = TypeVar('T')
R = TypeVar('R')


class TaskStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class TaskPriority(int, Enum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


@dataclass
class TaskContext:
    task_id: str
    trace_id: str
    created_at: datetime
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    user_id: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    resources_acquired: List[str] = field(default_factory=list)
    rollback_actions: List[Callable] = field(default_factory=list)

    async def cleanup(self):
        for action in reversed(self.rollback_actions):
            try:
                if asyncio.iscoroutinefunction(action):
                    await action()
                else:
                    action()
            except Exception as e:
                logger.error("Rollback action failed", task_id=self.task_id, error=str(e))


@dataclass
class TaskResult:
    task_id: str
    status: TaskStatus
    result: Optional[Any] = None
    error: Optional[str] = None
    error_detail: Optional[Dict[str, Any]] = None
    metrics: Dict[str, Any] = field(default_factory=dict)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


@dataclass
class TaskDefinition:
    name: str
    func: Callable[..., Coroutine[Any, Any, Any]]
    description: Optional[str] = None
    default_timeout: int = 300
    default_priority: TaskPriority = TaskPriority.MEDIUM
    default_max_retries: int = 3
    default_retry_delay: int = 5
    tags: List[str] = field(default_factory=list)


class Task(Generic[T]):
    def __init__(
        self,
        name: str,
        func: Callable[..., Coroutine[Any, Any, T]],
        args: Optional[tuple] = None,
        kwargs: Optional[Dict[str, Any]] = None,
        priority: TaskPriority = TaskPriority.MEDIUM,
        timeout: int = 300,
        max_retries: int = 3,
        retry_delay: int = 5,
        task_id: Optional[str] = None,
        correlation_id: Optional[str] = None,
        user_id: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ):
        self.task_id = task_id or str(uuid.uuid4())
        self.name = name
        self.func = func
        self.args = args or ()
        self.kwargs = kwargs or {}
        self.priority = priority
        self.timeout = timeout
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.correlation_id = correlation_id or str(uuid.uuid4())
        self.user_id = user_id
        self.metadata = metadata or {}

        self.status = TaskStatus.PENDING
        self.result: Optional[T] = None
        self.error: Optional[str] = None
        self.error_detail: Optional[Dict[str, Any]] = None
        self.retry_count = 0
        self.created_at = datetime.utcnow()
        self.started_at: Optional[datetime] = None
        self.completed_at: Optional[datetime] = None
        self._context: Optional[TaskContext] = None
        self._cancel_event = asyncio.Event()

    async def execute(self) -> TaskResult:
        self.status = TaskStatus.RUNNING
        self.started_at = datetime.utcnow()
        self._context = TaskContext(
            task_id=self.task_id,
            trace_id=self.correlation_id,
            created_at=self.created_at,
            started_at=self.started_at,
            user_id=self.user_id,
            metadata=self.metadata,
        )

        try:
            async with asyncio.timeout(self.timeout):
                if self._cancel_event.is_set():
                    raise asyncio.CancelledError()

                self.result = await self.func(*self.args, **self.kwargs, context=self._context)
                self.status = TaskStatus.COMPLETED

        except asyncio.TimeoutError:
            self.status = TaskStatus.TIMEOUT
            self.error = "Task timed out"
            logger.error("Task timed out", task_id=self.task_id, name=self.name)

        except asyncio.CancelledError:
            self.status = TaskStatus.CANCELLED
            self.error = "Task cancelled"
            logger.warning("Task cancelled", task_id=self.task_id, name=self.name)
            raise

        except Exception as e:
            self.retry_count += 1
            if self.retry_count < self.max_retries:
                logger.warning(
                    "Task failed, retrying",
                    task_id=self.task_id,
                    name=self.name,
                    attempt=self.retry_count,
                    error=str(e),
                )
                await asyncio.sleep(self.retry_delay)
                return await self.execute()

            self.status = TaskStatus.FAILED
            self.error = str(e)
            self.error_detail = {"type": type(e).__name__, "message": str(e)}
            logger.error("Task failed permanently", task_id=self.task_id, name=self.name, error=str(e))

        finally:
            self.completed_at = datetime.utcnow()
            if self._context:
                await self._context.cleanup()

        return TaskResult(
            task_id=self.task_id,
            status=self.status,
            result=self.result,
            error=self.error,
            error_detail=self.error_detail,
            started_at=self.started_at,
            completed_at=self.completed_at,
            metrics={
                "duration_seconds": (self.completed_at - self.started_at).total_seconds() if self.started_at and self.completed_at else 0,
                "retries": self.retry_count,
            },
        )

    def cancel(self) -> None:
        self._cancel_event.set()
        self.status = TaskStatus.CANCELLED


class TaskScheduler:
    def __init__(self, max_concurrent: int = 100):
        self.max_concurrent = max_concurrent
        self._tasks: Dict[str, Task] = {}
        self._queue: asyncio.PriorityQueue = asyncio.PriorityQueue()
        self._active_tasks: Dict[str, asyncio.Task] = {}
        self._completed_results: Dict[str, TaskResult] = {}
        self._task_definitions: Dict[str, TaskDefinition] = {}
        self._running = False
        self._scheduler_task: Optional[asyncio.Task] = None
        self._semaphore = asyncio.Semaphore(max_concurrent)
        self._event_store: Optional[EventStore] = None
        self._notification_manager: Optional[NotificationManager] = None

    def register_task_definition(self, definition: TaskDefinition) -> None:
        self._task_definitions[definition.name] = definition
        logger.info("Task definition registered", name=definition.name)

    def get_task_definition(self, name: str) -> Optional[TaskDefinition]:
        return self._task_definitions.get(name)

    async def submit(self, task: Task) -> str:
        self._tasks[task.task_id] = task
        priority = -task.priority.value
        await self._queue.put((priority, task.task_id, task))
        task.status = TaskStatus.QUEUED
        logger.info("Task submitted", task_id=task.task_id, name=task.name, priority=task.priority)

        if self._event_store:
            asyncio.create_task(self._event_store.append(
                aggregate_id=task.task_id,
                event_type=EventType.TASK_STARTED,
                payload={"task_name": task.name, "metadata": task.metadata},
                correlation_id=task.correlation_id,
            ))

        return task.task_id

    async def create_and_submit(
        self,
        name: str,
        func: Callable[..., Coroutine[Any, Any, Any]],
        args: Optional[tuple] = None,
        kwargs: Optional[Dict[str, Any]] = None,
        **task_kwargs,
    ) -> str:
        task = Task(name=name, func=func, args=args, kwargs=kwargs, **task_kwargs)
        return await self.submit(task)

    async def _execute_task(self, task: Task) -> None:
        async with self._semaphore:
            try:
                result = await task.execute()
                self._completed_results[task.task_id] = result

                if self._event_store:
                    await self._event_store.append(
                        aggregate_id=task.task_id,
                        event_type=EventType.TASK_COMPLETED if result.status == TaskStatus.COMPLETED else EventType.TASK_FAILED,
                        payload={"result": result.result, "error": result.error, "metrics": result.metrics},
                        correlation_id=task.correlation_id,
                    )

                if self._notification_manager and result.status in [TaskStatus.FAILED, TaskStatus.TIMEOUT]:
                    asyncio.create_task(self._notification_manager.send_immediately(
                        title=f"Task Failed: {task.name}",
                        message=f"Task {task.task_id} failed with error: {result.error}",
                        priority=NotificationPriority.HIGH,
                        channels=[NotificationChannel.CONSOLE],
                        tags=["task_failure", task.name],
                    ))

            except asyncio.CancelledError:
                task.status = TaskStatus.CANCELLED
            except Exception as e:
                logger.error("Task execution error", task_id=task.task_id, error=str(e))
            finally:
                self._active_tasks.pop(task.task_id, None)

    async def _scheduler_loop(self) -> None:
        while self._running:
            try:
                if not self._queue.empty():
                    priority, task_id, task = await self._queue.get()
                    if task_id in self._tasks and task.status == TaskStatus.QUEUED:
                        execution_task = asyncio.create_task(self._execute_task(task))
                        self._active_tasks[task_id] = execution_task
                        self._queue.task_done()
                else:
                    await asyncio.sleep(0.1)
            except Exception as e:
                logger.error("Scheduler loop error", error=str(e))
                await asyncio.sleep(1)

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._event_store = get_event_store()
        self._notification_manager = get_notification_manager()
        self._scheduler_task = asyncio.create_task(self._scheduler_loop())
        logger.info("Task scheduler started", max_concurrent=self.max_concurrent)

    async def stop(self) -> None:
        self._running = False
        for task_id, task in list(self._active_tasks.items()):
            task.cancel()
        if self._scheduler_task:
            self._scheduler_task.cancel()
            try:
                await self._scheduler_task
            except asyncio.CancelledError:
                pass
        logger.info("Task scheduler stopped")

    def get_task(self, task_id: str) -> Optional[Task]:
        return self._tasks.get(task_id)

    def get_task_status(self, task_id: str) -> Optional[TaskStatus]:
        task = self._tasks.get(task_id)
        return task.status if task else None

    def get_task_result(self, task_id: str) -> Optional[TaskResult]:
        return self._completed_results.get(task_id)

    def cancel_task(self, task_id: str) -> bool:
        task = self._tasks.get(task_id)
        if task and task.status in [TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING]:
            task.cancel()
            if task_id in self._active_tasks:
                self._active_tasks[task_id].cancel()
            return True
        return False

    def list_tasks(
        self,
        status: Optional[TaskStatus] = None,
        name: Optional[str] = None,
        limit: int = 100,
    ) -> List[Task]:
        tasks = list(self._tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        if name:
            tasks = [t for t in tasks if t.name == name]
        return sorted(tasks, key=lambda t: t.created_at, reverse=True)[:limit]

    async def wait_for_task(self, task_id: str, timeout: Optional[float] = None) -> Optional[TaskResult]:
        async def wait():
            while task_id not in self._completed_results:
                await asyncio.sleep(0.1)
            return self._completed_results[task_id]

        if timeout:
            return await asyncio.wait_for(wait(), timeout=timeout)
        return await wait()

    def get_stats(self) -> Dict[str, Any]:
        status_counts = defaultdict(int)
        for task in self._tasks.values():
            status_counts[task.status.value] += 1

        return {
            "total_tasks": len(self._tasks),
            "active_tasks": len(self._active_tasks),
            "queued_tasks": self._queue.qsize(),
            "completed_results": len(self._completed_results),
            "by_status": dict(status_counts),
            "task_definitions": len(self._task_definitions),
        }


class ExecutionHandler:
    def __init__(self):
        self.db_manager = get_db_manager()
        self.entity_repo = get_entity_repository()
        self.run_repo = get_run_repository()
        self.command_audit = get_command_audit_manager()
        self.fault_injection = get_fault_injection_manager()
        self.event_store = get_event_store()
        self.notification = get_notification_manager()
        self.config_manager = get_config_manager()

    async def create_entity(
        self,
        entity_type: str,
        config: Dict[str, Any],
        labels: Optional[Dict[str, Any]] = None,
        user_id: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        trace_id = str(uuid.uuid4())
        ctx = {
            "trace_id": trace_id,
            "user_id": user_id,
            "started_at": datetime.utcnow(),
        }

        try:
            await self._validate_params({"type": entity_type, "config": config})
            entity_config = self._load_config(entity_type)

            await self.fault_injection.check_and_inject(InjectionScope.ENTITY, entity_type)

            entity = None
            async with self.db_manager.get_session() as session:
                entity = await self.entity_repo.create(
                    session,
                    type=entity_type,
                    status=EntityStatus.PROVISIONING,
                    attributes=config,
                    labels=labels or {},
                )

                run_id = f"run_{uuid.uuid4().hex[:8]}"
                await self.run_repo.create(
                    session,
                    run_id=run_id,
                    entity_id=entity.id,
                    phase="provisioning",
                    progress=0,
                )

            result = await self._process_core(entity.id, config, entity_config.get("rules", {}), ctx)

            async with self.db_manager.get_session() as session:
                await self.entity_repo.update_status(session, entity.id, EntityStatus.COMPLETED)

            await self.event_store.append(
                aggregate_id=entity.id,
                event_type=EventType.CREATED,
                payload={"type": entity_type, "config": config, "result": result},
                correlation_id=trace_id,
                metadata=metadata or {},
            )

            await self.command_audit.log_action(
                action=AuditAction.MODIFY,
                description=f"Created entity {entity_type} with id {entity.id}",
                user_id=user_id,
                resource_type="entity",
                resource_id=entity.id,
                success=True,
            )

            return {
                "id": entity.id,
                "status": EntityStatus.COMPLETED,
                "trace_id": trace_id,
                "result": result,
            }

        except ValueError as e:
            logger.error("Validation error", trace_id=trace_id, error=str(e))
            return {"code": 422, "error": str(e), "trace_id": trace_id}
        except asyncio.TimeoutError:
            logger.error("Timeout error", trace_id=trace_id)
            return {"code": 504, "error": "上游服务响应超时", "trace_id": trace_id}
        except Exception as e:
            logger.error("Internal error", trace_id=trace_id, error=str(e))
            await self._rollback_transaction(ctx)
            return {"code": 500, "error": "内部处理错误", "trace_id": trace_id}

    async def _validate_params(self, params: Dict[str, Any]) -> None:
        if not params.get("type"):
            raise ValueError("Entity type is required")
        if "config" not in params:
            raise ValueError("Config is required")

    def _load_config(self, namespace: str) -> Dict[str, Any]:
        return self.config_manager.get(namespace) or {}

    async def _process_core(
        self,
        entity_id: str,
        payload: Dict[str, Any],
        rules: Dict[str, Any],
        ctx: Dict[str, Any],
    ) -> Dict[str, Any]:
        await asyncio.sleep(0.1)
        return {
            "entity_id": entity_id,
            "processed": True,
            "rules_applied": list(rules.keys()),
            "payload_size": len(str(payload)),
        }

    async def _rollback_transaction(self, ctx: Dict[str, Any]) -> None:
        logger.warning("Rolling back transaction", trace_id=ctx.get("trace_id"))
        entity_id = ctx.get("entity_id")
        if entity_id:
            try:
                async with self.db_manager.get_session() as session:
                    await self.entity_repo.update_status(session, entity_id, EntityStatus.FAILED)
            except Exception as e:
                logger.error("Rollback failed", entity_id=entity_id, error=str(e))

    async def get_entity_status(self, entity_id: str) -> Dict[str, Any]:
        async with self.db_manager.get_session() as session:
            entity = await self.entity_repo.get_by_id(session, entity_id)
            if not entity:
                return {"code": 404, "error": "Entity not found"}

            runs = await self.run_repo.get_active_runs(session, entity_id)
            progress = max([run.progress for run in runs]) if runs else 0

            return {
                "id": entity.id,
                "status": entity.status,
                "progress": progress,
                "active_runs": len(runs),
                "created_at": entity.created_at.isoformat(),
                "updated_at": entity.updated_at.isoformat(),
            }


class CoreEngine:
    _instance: Optional['CoreEngine'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if self._initialized:
            return

        self.scheduler = TaskScheduler()
        self.execution_handler = ExecutionHandler()
        self.db_manager = get_db_manager()
        self._shutdown_callbacks: List[Callable] = []
        self._initialized = True

    async def initialize(self) -> None:
        await self.db_manager.create_tables()
        await self.scheduler.start()

        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                asyncio.get_event_loop().add_signal_handler(sig, lambda: asyncio.create_task(self.shutdown()))
            except NotImplementedError:
                pass

        logger.info("Core engine initialized")

    async def shutdown(self) -> None:
        logger.info("Shutting down core engine")
        for callback in self._shutdown_callbacks:
            try:
                if asyncio.iscoroutinefunction(callback):
                    await callback()
                else:
                    callback()
            except Exception as e:
                logger.error("Shutdown callback error", error=str(e))

        await self.scheduler.stop()
        await self.db_manager.close()
        logger.info("Core engine shutdown complete")

    def add_shutdown_callback(self, callback: Callable) -> None:
        self._shutdown_callbacks.append(callback)

    async def submit_task(
        self,
        name: str,
        func: Callable[..., Coroutine[Any, Any, Any]],
        args: Optional[tuple] = None,
        kwargs: Optional[Dict[str, Any]] = None,
        **task_kwargs,
    ) -> str:
        return await self.scheduler.create_and_submit(name, func, args, kwargs, **task_kwargs)

    def get_task_status(self, task_id: str) -> Optional[TaskStatus]:
        return self.scheduler.get_task_status(task_id)

    def get_task_result(self, task_id: str) -> Optional[TaskResult]:
        return self.scheduler.get_task_result(task_id)

    async def wait_for_task(self, task_id: str, timeout: Optional[float] = None) -> Optional[TaskResult]:
        return await self.scheduler.wait_for_task(task_id, timeout)


def get_core_engine() -> CoreEngine:
    return CoreEngine()
