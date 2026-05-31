from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Optional

from streamsql.core.models import generate_id


class TaskStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass
class ScheduledTask:
    task_id: str = field(default_factory=lambda: generate_id("task"))
    name: str = ""
    cron_expression: str = ""
    interval_seconds: int = 0
    task_func: Optional[Callable[..., Any]] = None
    args: tuple[Any, ...] = ()
    kwargs: dict[str, Any] = field(default_factory=dict)
    status: TaskStatus = TaskStatus.PENDING
    last_run_at: float = 0.0
    next_run_at: float = 0.0
    run_count: int = 0
    error_count: int = 0
    last_error: str = ""
    enabled: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "task_id": self.task_id,
            "name": self.name,
            "cron_expression": self.cron_expression,
            "interval_seconds": self.interval_seconds,
            "status": self.status.value,
            "last_run_at": self.last_run_at,
            "next_run_at": self.next_run_at,
            "run_count": self.run_count,
            "error_count": self.error_count,
            "last_error": self.last_error,
            "enabled": self.enabled,
        }


class ValidationScheduler:
    def __init__(self):
        self._tasks: dict[str, ScheduledTask] = {}
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

    def add_interval_task(
        self,
        name: str,
        interval_seconds: int,
        task_func: Callable[..., Any],
        *args: Any,
        **kwargs: Any,
    ) -> str:
        with self._lock:
            task = ScheduledTask(
                name=name,
                interval_seconds=interval_seconds,
                task_func=task_func,
                args=args,
                kwargs=kwargs,
                next_run_at=time.time() + interval_seconds,
            )
            self._tasks[task.task_id] = task
            return task.task_id

    def add_cron_task(
        self,
        name: str,
        cron_expression: str,
        task_func: Callable[..., Any],
        *args: Any,
        **kwargs: Any,
    ) -> str:
        with self._lock:
            next_run = self._parse_cron_next(cron_expression)
            task = ScheduledTask(
                name=name,
                cron_expression=cron_expression,
                task_func=task_func,
                args=args,
                kwargs=kwargs,
                next_run_at=next_run,
            )
            self._tasks[task.task_id] = task
            return task.task_id

    def _parse_cron_next(self, cron_expr: str) -> float:
        parts = cron_expr.split()
        if len(parts) != 5:
            return time.time() + 3600

        try:
            minute = int(parts[0]) if parts[0] != "*" else None
            hour = int(parts[1]) if parts[1] != "*" else None
            day = int(parts[2]) if parts[2] != "*" else None
            month = int(parts[3]) if parts[3] != "*" else None
            weekday = int(parts[4]) if parts[4] != "*" else None

            now = time.time()
            import datetime

            dt = datetime.datetime.fromtimestamp(now)
            next_dt = dt + datetime.timedelta(minutes=1)
            next_dt = next_dt.replace(second=0, microsecond=0)

            for _ in range(525600):
                match = True
                if minute is not None and next_dt.minute != minute:
                    match = False
                if hour is not None and next_dt.hour != hour:
                    match = False
                if day is not None and next_dt.day != day:
                    match = False
                if month is not None and next_dt.month != month:
                    match = False
                if weekday is not None and next_dt.weekday() != weekday:
                    match = False

                if match:
                    return next_dt.timestamp()

                next_dt += datetime.timedelta(minutes=1)
        except (ValueError, IndexError):
            pass

        return time.time() + 3600

    def remove_task(self, task_id: str) -> bool:
        with self._lock:
            if task_id in self._tasks:
                del self._tasks[task_id]
                return True
            return False

    def get_task(self, task_id: str) -> Optional[ScheduledTask]:
        with self._lock:
            return self._tasks.get(task_id)

    def list_tasks(self) -> list[ScheduledTask]:
        with self._lock:
            return list(self._tasks.values())

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return

        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            now = time.time()

            with self._lock:
                tasks_to_run = []
                for task in self._tasks.values():
                    if task.enabled and task.next_run_at <= now:
                        tasks_to_run.append(task)

            for task in tasks_to_run:
                self._execute_task(task)

            self._stop_event.wait(timeout=1)

    def _execute_task(self, task: ScheduledTask) -> None:
        task.status = TaskStatus.RUNNING
        task.last_run_at = time.time()

        try:
            if task.task_func:
                task.task_func(*task.args, **task.kwargs)
            task.status = TaskStatus.COMPLETED
            task.error_count = 0
            task.last_error = ""
        except Exception as e:
            task.status = TaskStatus.FAILED
            task.error_count += 1
            task.last_error = str(e)
        finally:
            task.run_count += 1

        if task.interval_seconds > 0:
            task.next_run_at = time.time() + task.interval_seconds
        elif task.cron_expression:
            task.next_run_at = self._parse_cron_next(task.cron_expression)

    def run_task_now(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task and task.task_func:
            threading.Thread(target=self._execute_task, args=(task,), daemon=True).start()
            return True
        return False

    def enable_task(self, task_id: str) -> bool:
        with self._lock:
            task = self._tasks.get(task_id)
            if task:
                task.enabled = True
                return True
            return False

    def disable_task(self, task_id: str) -> bool:
        with self._lock:
            task = self._tasks.get(task_id)
            if task:
                task.enabled = False
                return True
            return False

    def get_status_summary(self) -> dict[str, Any]:
        with self._lock:
            return {
                "total_tasks": len(self._tasks),
                "running": sum(1 for t in self._tasks.values() if t.status == TaskStatus.RUNNING),
                "completed": sum(1 for t in self._tasks.values() if t.status == TaskStatus.COMPLETED),
                "failed": sum(1 for t in self._tasks.values() if t.status == TaskStatus.FAILED),
                "enabled": sum(1 for t in self._tasks.values() if t.enabled),
                "is_running": self._thread is not None and self._thread.is_alive(),
            }
