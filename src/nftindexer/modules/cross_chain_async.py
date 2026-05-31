from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from enum import Enum
import asyncio
import time
import uuid
from datetime import datetime, timezone

from ..utils import get_logger

logger = get_logger(__name__)


class TaskStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class TaskType(str, Enum):
    INITIATE_BRIDGE = "initiate_bridge"
    CONFIRM_SOURCE = "confirm_source"
    VERIFY_PROOF = "verify_proof"
    COMPLETE_TRANSACTION = "complete_transaction"
    ROLLBACK = "rollback"


@dataclass
class AsyncTask:
    task_id: str
    task_type: TaskType
    status: TaskStatus = TaskStatus.PENDING
    params: Dict[str, Any] = field(default_factory=dict)
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    created_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    timeout: float = 300.0
    retries: int = 0
    max_retries: int = 3
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "task_type": self.task_type.value,
            "status": self.status.value,
            "params": self.params,
            "result": self.result,
            "error": self.error,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "completed_at": self.completed_at,
            "retries": self.retries,
            "max_retries": self.max_retries,
        }


@dataclass
class TaskNotification:
    task_id: str
    task_type: TaskType
    status: TaskStatus
    data: Dict[str, Any]
    timestamp: float = field(default_factory=time.time)


class ITaskResultHandler(ABC):
    @abstractmethod
    async def handle_result(self, notification: TaskNotification) -> None:
        ...


class WebhookResultHandler(ITaskResultHandler):
    def __init__(self, webhook_url: str, headers: Optional[Dict[str, str]] = None):
        self._webhook_url = webhook_url
        self._headers = headers or {}

    async def handle_result(self, notification: TaskNotification) -> None:
        try:
            import aiohttp
            async with aiohttp.ClientSession() as session:
                payload = {
                    "task_id": notification.task_id,
                    "task_type": notification.task_type.value,
                    "status": notification.status.value,
                    "data": notification.data,
                    "timestamp": notification.timestamp,
                }
                async with session.post(
                    self._webhook_url,
                    json=payload,
                    headers=self._headers,
                    timeout=aiohttp.ClientTimeout(total=30),
                ) as response:
                    if response.status >= 400:
                        logger.error(
                            f"Webhook notification failed for task {notification.task_id}: {response.status}"
                        )
        except Exception as e:
            logger.error(f"Error sending webhook notification: {e}")


class CallbackResultHandler(ITaskResultHandler):
    def __init__(self, callback: Callable[[TaskNotification], Any]):
        self._callback = callback

    async def handle_result(self, notification: TaskNotification) -> None:
        try:
            result = self._callback(notification)
            if asyncio.iscoroutine(result):
                await result
        except Exception as e:
            logger.error(f"Error in callback result handler: {e}")


class EventEmitterResultHandler(ITaskResultHandler):
    def __init__(self):
        self._listeners: Dict[TaskStatus, List[Callable[[TaskNotification], Any]]] = {}
        self._all_listeners: List[Callable[[TaskNotification], Any]] = []

    def add_listener(
        self,
        callback: Callable[[TaskNotification], Any],
        status: Optional[TaskStatus] = None,
    ) -> None:
        if status:
            if status not in self._listeners:
                self._listeners[status] = []
            self._listeners[status].append(callback)
        else:
            self._all_listeners.append(callback)

    def remove_listener(
        self,
        callback: Callable[[TaskNotification], Any],
        status: Optional[TaskStatus] = None,
    ) -> None:
        if status and status in self._listeners:
            if callback in self._listeners[status]:
                self._listeners[status].remove(callback)
        else:
            if callback in self._all_listeners:
                self._all_listeners.remove(callback)

    async def handle_result(self, notification: TaskNotification) -> None:
        listeners = []
        if notification.status in self._listeners:
            listeners.extend(self._listeners[notification.status])
        listeners.extend(self._all_listeners)

        for listener in listeners:
            try:
                result = listener(notification)
                if asyncio.iscoroutine(result):
                    await result
            except Exception as e:
                logger.error(f"Error in event listener: {e}")


class AsyncTaskExecutor:
    def __init__(self, max_concurrent_tasks: int = 10):
        self._max_concurrent_tasks = max_concurrent_tasks
        self._tasks: Dict[str, AsyncTask] = {}
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._queue: asyncio.Queue[AsyncTask] = asyncio.Queue()
        self._result_handlers: List[ITaskResultHandler] = []
        self._task_handlers: Dict[TaskType, Callable[[AsyncTask], Any]] = {}
        self._event_emitter = EventEmitterResultHandler()
        self._semaphore = asyncio.Semaphore(max_concurrent_tasks)
        self._running = False
        self._worker_task: Optional[asyncio.Task] = None
        self._lock = asyncio.Lock()

    async def start(self) -> None:
        if self._running:
            return

        self._running = True
        self._worker_task = asyncio.create_task(self._worker_loop())
        logger.info("AsyncTaskExecutor started")

    async def shutdown(self) -> None:
        if not self._running:
            return

        self._running = False

        for task_id, task in self._running_tasks.items():
            if not task.done():
                task.cancel()
                logger.info(f"Cancelled running task {task_id}")

        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass

        self._result_handlers.clear()
        self._task_handlers.clear()
        self._tasks.clear()
        self._running_tasks.clear()

        logger.info("AsyncTaskExecutor shutdown")

    def register_task_handler(
        self, task_type: TaskType, handler: Callable[[AsyncTask], Any]
    ) -> None:
        self._task_handlers[task_type] = handler
        logger.info(f"Registered handler for task type {task_type}")

    def unregister_task_handler(self, task_type: TaskType) -> None:
        if task_type in self._task_handlers:
            del self._task_handlers[task_type]
            logger.info(f"Unregistered handler for task type {task_type}")

    def add_result_handler(self, handler: ITaskResultHandler) -> None:
        self._result_handlers.append(handler)
        logger.info("Added result handler")

    def remove_result_handler(self, handler: ITaskResultHandler) -> None:
        if handler in self._result_handlers:
            self._result_handlers.remove(handler)
            logger.info("Removed result handler")

    def add_event_listener(
        self,
        callback: Callable[[TaskNotification], Any],
        status: Optional[TaskStatus] = None,
    ) -> None:
        self._event_emitter.add_listener(callback, status)

    def remove_event_listener(
        self,
        callback: Callable[[TaskNotification], Any],
        status: Optional[TaskStatus] = None,
    ) -> None:
        self._event_emitter.remove_listener(callback, status)

    async def submit_task(
        self,
        task_type: TaskType,
        params: Dict[str, Any],
        timeout: float = 300.0,
        max_retries: int = 3,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> AsyncTask:
        task_id = str(uuid.uuid4())
        task = AsyncTask(
            task_id=task_id,
            task_type=task_type,
            params=params,
            timeout=timeout,
            max_retries=max_retries,
            metadata=metadata or {},
        )

        async with self._lock:
            self._tasks[task_id] = task

        await self._queue.put(task)
        logger.info(f"Submitted task {task_id} of type {task_type}")

        return task

    async def get_task(self, task_id: str) -> Optional[AsyncTask]:
        async with self._lock:
            return self._tasks.get(task_id)

    async def list_tasks(
        self,
        status: Optional[TaskStatus] = None,
        task_type: Optional[TaskType] = None,
        limit: int = 100,
    ) -> List[AsyncTask]:
        async with self._lock:
            tasks = list(self._tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]
        if task_type:
            tasks = [t for t in tasks if t.task_type == task_type]

        return sorted(tasks, key=lambda t: t.created_at, reverse=True)[:limit]

    async def cancel_task(self, task_id: str) -> bool:
        async with self._lock:
            task = self._tasks.get(task_id)
            if not task or task.status in [
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
            ]:
                return False

            task.status = TaskStatus.CANCELLED
            task.completed_at = time.time()

            if task_id in self._running_tasks:
                self._running_tasks[task_id].cancel()

            await self._notify_handlers(task)
            logger.info(f"Cancelled task {task_id}")
            return True

    async def _worker_loop(self) -> None:
        while self._running:
            try:
                task = await self._queue.get()

                if task.status == TaskStatus.CANCELLED:
                    self._queue.task_done()
                    continue

                asyncio.create_task(self._execute_task(task))

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in worker loop: {e}")
                await asyncio.sleep(1)

    async def _execute_task(self, task: AsyncTask) -> None:
        async with self._semaphore:
            task.started_at = time.time()
            task.status = TaskStatus.RUNNING

            async with self._lock:
                self._running_tasks[task.task_id] = asyncio.current_task()

            await self._notify_handlers(task)

            handler = self._task_handlers.get(task.task_type)
            if not handler:
                task.status = TaskStatus.FAILED
                task.error = f"No handler registered for task type {task.task_type}"
                task.completed_at = time.time()
                await self._notify_handlers(task)
                return

            try:
                result = await asyncio.wait_for(
                    self._run_handler(handler, task),
                    timeout=task.timeout,
                )
                task.result = result
                task.status = TaskStatus.COMPLETED
                task.completed_at = time.time()
                logger.info(f"Task {task.task_id} completed successfully")

            except asyncio.TimeoutError:
                task.status = TaskStatus.TIMEOUT
                task.error = f"Task timed out after {task.timeout} seconds"
                task.completed_at = time.time()
                logger.warning(f"Task {task.task_id} timed out")

            except Exception as e:
                task.retries += 1
                if task.retries < task.max_retries:
                    task.status = TaskStatus.PENDING
                    task.error = f"Attempt {task.retries} failed: {str(e)}"
                    logger.warning(
                        f"Task {task.task_id} failed attempt {task.retries}/{task.max_retries}: {e}"
                    )
                    await asyncio.sleep(1.0 * task.retries)
                    await self._queue.put(task)
                    return
                else:
                    task.status = TaskStatus.FAILED
                    task.error = str(e)
                    task.completed_at = time.time()
                    logger.error(f"Task {task.task_id} failed after {task.retries} attempts: {e}")

            finally:
                async with self._lock:
                    if task.task_id in self._running_tasks:
                        del self._running_tasks[task.task_id]

                await self._notify_handlers(task)

    async def _run_handler(
        self, handler: Callable[[AsyncTask], Any], task: AsyncTask
    ) -> Dict[str, Any]:
        result = handler(task)
        if asyncio.iscoroutine(result):
            result = await result
        if result is None:
            return {}
        if isinstance(result, dict):
            return result
        return {"result": result}

    async def _notify_handlers(self, task: AsyncTask) -> None:
        notification = TaskNotification(
            task_id=task.task_id,
            task_type=task.task_type,
            status=task.status,
            data={
                "params": task.params,
                "result": task.result,
                "error": task.error,
                "metadata": task.metadata,
            },
        )

        await self._event_emitter.handle_result(notification)

        for handler in self._result_handlers:
            try:
                await handler.handle_result(notification)
            except Exception as e:
                logger.error(f"Error in result handler: {e}")


_executor: Optional[AsyncTaskExecutor] = None


def get_async_executor() -> AsyncTaskExecutor:
    global _executor
    if _executor is None:
        _executor = AsyncTaskExecutor()
    return _executor
