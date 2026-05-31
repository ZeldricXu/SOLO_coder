import asyncio
import time
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional
from concurrent.futures import ThreadPoolExecutor

from config import settings
from core import emit_event, EventTypes
from models import generate_uuid, utc_now


class TaskStatus:
    PENDING = "pending"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    WAITING = "waiting"
    SUCCESS = "success"
    FAILED = "failed"
    TIMEOUT = "timeout"
    CANCELLED = "cancelled"
    SKIPPED = "skipped"


class TaskPriority:
    LOW = 10
    NORMAL = 50
    HIGH = 100
    CRITICAL = 200


class Task:
    def __init__(
        self,
        task_type: str,
        payload: Dict[str, Any],
        priority: int = TaskPriority.NORMAL,
        timeout: int = 300,
        max_retries: int = 3,
        retry_delay: int = 60,
        dependencies: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ):
        self.task_id = generate_uuid()
        self.task_type = task_type
        self.payload = payload
        self.priority = priority
        self.timeout = timeout
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.dependencies = dependencies or []
        self.metadata = metadata or {}
        self.status = TaskStatus.PENDING
        self.result: Optional[Any] = None
        self.error: Optional[str] = None
        self.retry_count = 0
        self.created_at = utc_now()
        self.started_at: Optional[datetime] = None
        self.completed_at: Optional[datetime] = None
        self.duration_ms: Optional[float] = None
        self.parent_task_id: Optional[str] = None
        self.child_tasks: List[str] = []
        self.trace_id: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "task_type": self.task_type,
            "payload": self.payload,
            "priority": self.priority,
            "status": self.status,
            "result": self.result,
            "error": self.error,
            "retry_count": self.retry_count,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "completed_at": self.completed_at,
            "duration_ms": self.duration_ms,
            "dependencies": self.dependencies,
            "metadata": self.metadata,
        }


class TaskResult:
    def __init__(
        self,
        task_id: str,
        success: bool,
        result: Optional[Any] = None,
        error: Optional[str] = None,
        duration_ms: Optional[float] = None,
    ):
        self.task_id = task_id
        self.success = success
        self.result = result
        self.error = error
        self.duration_ms = duration_ms
        self.completed_at = utc_now()


class TaskExecutor:
    def __init__(self, max_workers: int = 10):
        self._task_handlers: Dict[str, Callable] = {}
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._completed_tasks: Dict[str, Task] = {}
        self._pending_tasks: asyncio.PriorityQueue = asyncio.PriorityQueue()
        self._thread_pool = ThreadPoolExecutor(max_workers=max_workers)
        self._stop_event = asyncio.Event()
        self._dispatcher_task: Optional[asyncio.Task] = None

    def register_handler(self, task_type: str, handler: Callable) -> None:
        self._task_handlers[task_type] = handler

    async def submit(self, task: Task) -> str:
        task.status = TaskStatus.SCHEDULED
        await self._pending_tasks.put((-task.priority, task.task_id, task))
        emit_event(
            EventTypes.TASK_CREATED,
            "task_executor",
            task.to_dict(),
            task.trace_id,
        )
        return task.task_id

    async def _execute_task(self, task: Task) -> TaskResult:
        task.status = TaskStatus.RUNNING
        task.started_at = utc_now()
        start_time = time.time()

        handler = self._task_handlers.get(task.task_type)

        if handler is None:
            return TaskResult(
                task_id=task.task_id,
                success=False,
                error=f"No handler registered for task type: {task.task_type}",
            )

        try:
            if asyncio.iscoroutinefunction(handler):
                result = await asyncio.wait_for(
                    handler(task.payload, task.metadata),
                    timeout=task.timeout,
                )
            else:
                result = await asyncio.wait_for(
                    asyncio.to_thread(handler, task.payload, task.metadata),
                    timeout=task.timeout,
                )

            duration = (time.time() - start_time) * 1000
            task.duration_ms = duration
            task.completed_at = utc_now()

            return TaskResult(
                task_id=task.task_id,
                success=True,
                result=result,
                duration_ms=duration,
            )

        except asyncio.TimeoutError:
            duration = (time.time() - start_time) * 1000
            return TaskResult(
                task_id=task.task_id,
                success=False,
                error="Task timed out",
                duration_ms=duration,
            )

        except Exception as e:
            duration = (time.time() - start_time) * 1000
            return TaskResult(
                task_id=task.task_id,
                success=False,
                error=str(e),
                duration_ms=duration,
            )

    async def _process_task(self, task: Task) -> None:
        result = await self._execute_task(task)

        if result.success:
            task.status = TaskStatus.SUCCESS
            task.result = result.result

            emit_event(
                EventTypes.TASK_COMPLETED,
                "task_executor",
                {
                    "task_id": task.task_id,
                    "result": result.result,
                    "duration_ms": result.duration_ms,
                },
                task.trace_id,
            )
        else:
            if task.retry_count < task.max_retries:
                task.retry_count += 1
                task.status = TaskStatus.PENDING
                await asyncio.sleep(task.retry_delay)
                await self.submit(task)
                return

            task.status = TaskStatus.FAILED
            task.error = result.error

            emit_event(
                EventTypes.TASK_FAILED,
                "task_executor",
                {
                    "task_id": task.task_id,
                    "error": result.error,
                    "duration_ms": result.duration_ms,
                },
                task.trace_id,
            )

        task.completed_at = utc_now()
        self._completed_tasks[task.task_id] = task

        if task.task_id in self._running_tasks:
            del self._running_tasks[task.task_id]

    async def _dispatcher(self) -> None:
        while not self._stop_event.is_set():
            try:
                if len(self._running_tasks) >= settings.max_concurrent_tasks:
                    await asyncio.sleep(0.1)
                    continue

                try:
                    _, _, task = await asyncio.wait_for(
                        self._pending_tasks.get(), timeout=0.1
                    )
                except asyncio.TimeoutError:
                    continue

                if task.dependencies:
                    deps_completed = all(
                        dep_id in self._completed_tasks
                        and self._completed_tasks[dep_id].status == TaskStatus.SUCCESS
                        for dep_id in task.dependencies
                    )
                    if not deps_completed:
                        task.status = TaskStatus.WAITING
                        await self._pending_tasks.put((-task.priority, task.task_id, task))
                        await asyncio.sleep(0.1)
                        continue

                self._running_tasks[task.task_id] = asyncio.create_task(
                    self._process_task(task)
                )

            except Exception as e:
                print(f"Dispatcher error: {e}")
                await asyncio.sleep(1)

    async def start(self) -> None:
        if self._dispatcher_task is None or self._dispatcher_task.done():
            self._stop_event.clear()
            self._dispatcher_task = asyncio.create_task(self._dispatcher())

    async def stop(self) -> None:
        self._stop_event.set()
        if self._dispatcher_task:
            self._dispatcher_task.cancel()
            try:
                await self._dispatcher_task
            except asyncio.CancelledError:
                pass
            self._dispatcher_task = None

        for task in self._running_tasks.values():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        self._running_tasks.clear()

    async def cancel_task(self, task_id: str) -> bool:
        if task_id in self._running_tasks:
            self._running_tasks[task_id].cancel()
            return True
        return False

    def get_task_status(self, task_id: str) -> Optional[Task]:
        if task_id in self._completed_tasks:
            return self._completed_tasks[task_id]

        for _, _, task in self._pending_tasks._queue:
            if task.task_id == task_id:
                return task

        return None

    def get_stats(self) -> Dict[str, Any]:
        return {
            "pending": self._pending_tasks.qsize(),
            "running": len(self._running_tasks),
            "completed": len(self._completed_tasks),
            "handlers": list(self._task_handlers.keys()),
        }


task_executor = TaskExecutor()
