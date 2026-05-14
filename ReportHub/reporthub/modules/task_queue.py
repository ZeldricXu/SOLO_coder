import asyncio
import uuid
import time
from typing import Dict, Any, Optional, List, Callable
from dataclasses import dataclass, field
from enum import Enum
from datetime import datetime
from collections import deque


class TaskStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    RETRYING = "retrying"


@dataclass
class AsyncTask:
    task_id: str
    task_type: str
    payload: Dict[str, Any]
    status: TaskStatus = TaskStatus.PENDING
    progress: float = 0.0
    message: str = ""
    result: Optional[Any] = None
    error: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 3
    created_at: datetime = field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: float = 0.0


class TaskQueue:
    def __init__(self, max_workers: int = 5):
        self._tasks: Dict[str, AsyncTask] = {}
        self._pending_queue = deque()
        self._completed_tasks: Dict[str, AsyncTask] = {}
        self._max_workers = max_workers
        self._running_count = 0
        self._lock = asyncio.Lock()
        self._task_handlers: Dict[str, Callable] = {}

    def register_handler(self, task_type: str, handler: Callable) -> None:
        self._task_handlers[task_type] = handler

    async def submit_task(self, task_type: str, payload: Dict[str, Any],
                          max_retries: int = 3) -> str:
        task_id = f"task_{uuid.uuid4().hex[:12]}"
        task = AsyncTask(
            task_id=task_id,
            task_type=task_type,
            payload=payload,
            max_retries=max_retries
        )
        self._tasks[task_id] = task
        self._pending_queue.append(task_id)
        asyncio.create_task(self._process_queue())
        return task_id

    def get_task_status(self, task_id: str) -> Optional[AsyncTask]:
        if task_id in self._tasks:
            return self._tasks[task_id]
        if task_id in self._completed_tasks:
            return self._completed_tasks[task_id]
        return None

    def get_task_progress(self, task_id: str) -> Dict[str, Any]:
        task = self.get_task_status(task_id)
        if not task:
            return {"exists": False}
        return {
            "exists": True,
            "task_id": task.task_id,
            "status": task.status.value,
            "progress": task.progress,
            "message": task.message,
            "retry_count": task.retry_count,
            "created_at": task.created_at.isoformat() if task.created_at else None,
            "started_at": task.started_at.isoformat() if task.started_at else None,
            "completed_at": task.completed_at.isoformat() if task.completed_at else None,
            "duration_ms": task.duration_ms
        }

    def cancel_task(self, task_id: str) -> bool:
        task = self.get_task_status(task_id)
        if task and task.status in [TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.RETRYING]:
            task.status = TaskStatus.FAILED
            task.message = "Task cancelled"
            return True
        return False

    def list_tasks(self, status: Optional[TaskStatus] = None,
                   limit: int = 100) -> List[AsyncTask]:
        tasks = list(self._tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return tasks[:limit]

    async def _process_queue(self) -> None:
        async with self._lock:
            if self._running_count >= self._max_workers:
                return
            if not self._pending_queue:
                return
            self._running_count += 1

        try:
            while self._pending_queue:
                task_id = self._pending_queue.popleft()
                task = self._tasks.get(task_id)
                if not task:
                    continue
                await self._execute_task(task)
        finally:
            async with self._lock:
                self._running_count -= 1

    async def _execute_task(self, task: AsyncTask) -> None:
        start_time = time.time()
        task.started_at = datetime.utcnow()
        task.status = TaskStatus.RUNNING
        task.progress = 0.1
        task.message = f"Starting {task.task_type} task"

        handler = self._task_handlers.get(task.task_type)
        if not handler:
            task.status = TaskStatus.FAILED
            task.error = f"No handler registered for task type: {task.task_type}"
            task.completed_at = datetime.utcnow()
            task.duration_ms = (time.time() - start_time) * 1000
            return

        try:
            if asyncio.iscoroutinefunction(handler):
                result = await handler(task.payload)
            else:
                result = handler(task.payload)
            task.status = TaskStatus.COMPLETED
            task.result = result
            task.progress = 1.0
            task.message = "Task completed successfully"
        except Exception as e:
            task.retry_count += 1
            if task.retry_count < task.max_retries:
                task.status = TaskStatus.RETRYING
                task.progress = 0.5
                task.message = f"Retry attempt {task.retry_count}/{task.max_retries}: {str(e)}"
                await asyncio.sleep(1 * task.retry_count)
                await self._execute_task(task)
                return
            else:
                task.status = TaskStatus.FAILED
                task.error = f"Failed after {task.max_retries} retries: {str(e)}"
        finally:
            task.completed_at = datetime.utcnow()
            task.duration_ms = (time.time() - start_time) * 1000
            self._completed_tasks[task.task_id] = task
            if task.task_id in self._tasks:
                del self._tasks[task.task_id]

    def get_queue_stats(self) -> Dict[str, Any]:
        pending = len([t for t in self._tasks.values() if t.status == TaskStatus.PENDING])
        running = len([t for t in self._tasks.values() if t.status == TaskStatus.RUNNING])
        retrying = len([t for t in self._tasks.values() if t.status == TaskStatus.RETRYING])
        failed = len([t for t in self._completed_tasks.values() if t.status == TaskStatus.FAILED])
        completed = len([t for t in self._completed_tasks.values() if t.status == TaskStatus.COMPLETED])
        return {
            "pending": pending,
            "running": running,
            "retrying": retrying,
            "failed": failed,
            "completed": completed,
            "max_workers": self._max_workers,
            "active_workers": self._running_count
        }
