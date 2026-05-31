import asyncio
import logging
import uuid
import time
from typing import Dict, List, Optional, Callable, Any
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import threading

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import (
    TaskNotFoundException,
    TaskConflictException,
    TaskTimeoutException
)

logger = logging.getLogger(__name__)


class TaskStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
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
class Task:
    task_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    name: str = ""
    description: str = ""
    status: TaskStatus = TaskStatus.PENDING
    priority: TaskPriority = TaskPriority.MEDIUM
    payload: Dict[str, Any] = field(default_factory=dict)
    result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.now)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    retry_count: int = 0
    max_retries: int = 3
    timeout_seconds: int = 300
    version: int = 1
    callback_url: Optional[str] = None
    tags: List[str] = field(default_factory=list)


class TaskScheduler:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._tasks: Dict[str, Task] = {}
        self._task_queue: asyncio.PriorityQueue = asyncio.PriorityQueue()
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._lock = threading.RLock()
        self._max_retry_attempts = config.get("scheduler.max_retry_attempts", 3)
        self._retry_delay = config.get("scheduler.retry_delay_seconds", 1)
        self._task_timeout = config.get("scheduler.task_timeout_seconds", 300)
        self._handler_registry: Dict[str, Callable[[Task], Any]] = {}
        self._is_running = False
        self._worker_task: Optional[asyncio.Task] = None

    def register_handler(self, task_type: str, handler: Callable[[Task], Any]) -> None:
        self._handler_registry[task_type] = handler
        logger.info(f"Registered handler for task type: {task_type}")

    async def submit_task(self, task: Task) -> Task:
        if not self._check_preconditions(task):
            raise ValueError("Task preconditions not met")

        with self._lock:
            self._tasks[task.task_id] = task

        self._event_bus.publish(Event(
            event_type="task.created",
            source="scheduler",
            payload={"task_id": task.task_id, "task_name": task.name}
        ))

        await self._task_queue.put((-task.priority.value, task.task_id))
        return task

    def _check_preconditions(self, task: Task) -> bool:
        if not task.name:
            return False
        if task.task_id in self._tasks:
            return False
        return True

    async def create_task(
        self,
        name: str,
        payload: Dict[str, Any],
        description: str = "",
        priority: TaskPriority = TaskPriority.MEDIUM,
        max_retries: Optional[int] = None,
        timeout_seconds: Optional[int] = None,
        tags: Optional[List[str]] = None
    ) -> Task:
        task = Task(
            name=name,
            description=description,
            priority=priority,
            payload=payload,
            max_retries=max_retries or self._max_retry_attempts,
            timeout_seconds=timeout_seconds or self._task_timeout,
            tags=tags or []
        )
        return await self.submit_task(task)

    def get_task(self, task_id: str) -> Task:
        with self._lock:
            task = self._tasks.get(task_id)
        if not task:
            raise TaskNotFoundException(f"Task {task_id} not found")
        return task

    def list_tasks(
        self,
        status: Optional[TaskStatus] = None,
        tag: Optional[str] = None,
        limit: int = 100
    ) -> List[Task]:
        with self._lock:
            tasks = list(self._tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]
        if tag:
            tasks = [t for t in tasks if tag in t.tags]

        tasks.sort(key=lambda t: t.created_at, reverse=True)
        return tasks[:limit]

    async def cancel_task(self, task_id: str) -> Task:
        task = self.get_task(task_id)

        with self._lock:
            if task.status in [TaskStatus.RUNNING]:
                if task_id in self._running_tasks:
                    self._running_tasks[task_id].cancel()
            task.status = TaskStatus.CANCELLED

        self._event_bus.publish(Event(
            event_type="task.cancelled",
            source="scheduler",
            payload={"task_id": task_id}
        ))

        return task

    async def update_task_status(
        self,
        task_id: str,
        new_status: TaskStatus,
        expected_version: int,
        result: Optional[Dict[str, Any]] = None,
        error_message: Optional[str] = None
    ) -> Task:
        for attempt in range(self._max_retry_attempts):
            task = self.get_task(task_id)

            with self._lock:
                if task.version != expected_version:
                    if attempt < self._max_retry_attempts - 1:
                        await asyncio.sleep(self._retry_delay)
                        continue
                    raise TaskConflictException(
                        f"Version conflict for task {task_id}. "
                        f"Expected {expected_version}, got {task.version}"
                    )

                task.status = new_status
                task.version += 1
                if result is not None:
                    task.result = result
                if error_message is not None:
                    task.error_message = error_message

                if new_status == TaskStatus.RUNNING:
                    task.started_at = datetime.now()
                elif new_status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.TIMEOUT]:
                    task.completed_at = datetime.now()

            self._event_bus.publish(Event(
                event_type=f"task.{new_status}",
                source="scheduler",
                payload={
                    "task_id": task_id,
                    "version": task.version,
                    "result": result,
                    "error_message": error_message
                }
            ))

            return task

        raise TaskConflictException(f"Failed to update task {task_id} after retries")

    async def _execute_task(self, task: Task) -> None:
        try:
            await self.update_task_status(
                task.task_id,
                TaskStatus.RUNNING,
                task.version
            )

            handler = self._handler_registry.get(task.name)
            if handler:
                try:
                    result = await asyncio.wait_for(
                        self._call_handler(handler, task),
                        timeout=task.timeout_seconds
                    )
                    await self.update_task_status(
                        task.task_id,
                        TaskStatus.COMPLETED,
                        task.version + 1,
                        result=result
                    )
                except asyncio.TimeoutError:
                    await self.update_task_status(
                        task.task_id,
                        TaskStatus.TIMEOUT,
                        task.version + 1,
                        error_message=f"Task timed out after {task.timeout_seconds}s"
                    )
                except Exception as e:
                    if task.retry_count < task.max_retries:
                        task.retry_count += 1
                        await asyncio.sleep(self._retry_delay)
                        await self._task_queue.put((-task.priority.value, task.task_id))
                    else:
                        await self.update_task_status(
                            task.task_id,
                            TaskStatus.FAILED,
                            task.version + 1,
                            error_message=str(e)
                        )
            else:
                await self.update_task_status(
                    task.task_id,
                    TaskStatus.FAILED,
                    task.version + 1,
                    error_message=f"No handler registered for task type: {task.name}"
                )
        finally:
            self._running_tasks.pop(task.task_id, None)

    async def _call_handler(self, handler: Callable, task: Task) -> Any:
        if asyncio.iscoroutinefunction(handler):
            return await handler(task)
        return handler(task)

    async def _worker(self) -> None:
        while self._is_running:
            try:
                _, task_id = await self._task_queue.get()
                with self._lock:
                    task = self._tasks.get(task_id)

                if task and task.status == TaskStatus.PENDING:
                    running_task = asyncio.create_task(self._execute_task(task))
                    self._running_tasks[task_id] = running_task

                self._task_queue.task_done()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in worker: {e}")

    async def start(self) -> None:
        if self._is_running:
            return
        self._is_running = True
        self._worker_task = asyncio.create_task(self._worker())
        logger.info("Task scheduler started")

    async def stop(self) -> None:
        self._is_running = False
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass

        for running_task in self._running_tasks.values():
            running_task.cancel()

        logger.info("Task scheduler stopped")

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            tasks = list(self._tasks.values())

        stats = {
            "total": len(tasks),
            "by_status": {}
        }

        for status in TaskStatus:
            count = sum(1 for t in tasks if t.status == status)
            stats["by_status"][status.value] = count

        return stats
