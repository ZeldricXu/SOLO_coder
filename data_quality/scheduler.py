from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Set
from datetime import datetime, timedelta
import threading
import time
import uuid
from croniter import croniter
import pandas as pd
from .checker import DataQualityChecker, CheckResult


class ScheduleType(Enum):
    CRON = "cron"
    INTERVAL = "interval"
    ONCE = "once"


@dataclass
class TaskDependency:
    task_id: str
    wait_for_success: bool = True
    timeout: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "wait_for_success": self.wait_for_success,
            "timeout": self.timeout,
        }

    @staticmethod
    def from_dict(data: Dict[str, Any]) -> "TaskDependency":
        return TaskDependency(
            task_id=data["task_id"],
            wait_for_success=data.get("wait_for_success", True),
            timeout=data.get("timeout"),
        )


@dataclass
class TaskResult:
    task_id: str
    task_name: str
    success: bool
    start_time: datetime
    end_time: datetime
    check_result: Optional[CheckResult] = None
    error: Optional[str] = None
    output: Dict[str, Any] = field(default_factory=dict)

    @property
    def duration(self) -> timedelta:
        return self.end_time - self.start_time

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "task_name": self.task_name,
            "success": self.success,
            "start_time": self.start_time.isoformat(),
            "end_time": self.end_time.isoformat(),
            "duration_seconds": self.duration.total_seconds(),
            "check_result": self.check_result.to_dict() if self.check_result else None,
            "error": self.error,
            "output": self.output,
        }


@dataclass
class Task:
    name: str
    func: Callable[[], Any]
    schedule_type: ScheduleType = ScheduleType.ONCE
    cron_expression: Optional[str] = None
    interval_seconds: Optional[int] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    max_retries: int = 0
    retry_delay: int = 5
    dependencies: List[TaskDependency] = field(default_factory=list)
    enabled: bool = True
    description: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        self.id = str(uuid.uuid4())
        self.next_run_time: Optional[datetime] = None
        self.last_run_time: Optional[datetime] = None
        self.last_result: Optional[TaskResult] = None
        self.run_count: int = 0
        self.is_running: bool = False
        self.consecutive_failures: int = 0

        self._calculate_next_run()

    def _calculate_next_run(self) -> None:
        now = datetime.now()

        if self.end_time and now > self.end_time:
            self.next_run_time = None
            return

        if self.schedule_type == ScheduleType.ONCE:
            if self.last_run_time is None:
                self.next_run_time = self.start_time or now
            else:
                self.next_run_time = None
        elif self.schedule_type == ScheduleType.INTERVAL:
            if not self.interval_seconds:
                raise ValueError("间隔调度需要配置 interval_seconds")
            if self.last_run_time is None:
                self.next_run_time = self.start_time or now
            else:
                self.next_run_time = self.last_run_time + timedelta(seconds=self.interval_seconds)
        elif self.schedule_type == ScheduleType.CRON:
            if not self.cron_expression:
                raise ValueError("Cron调度需要配置 cron_expression")
            base_time = self.last_run_time or self.start_time or now
            iter = croniter(self.cron_expression, base_time)
            self.next_run_time = iter.get_next(datetime)

    def should_run(self, now: Optional[datetime] = None) -> bool:
        if not self.enabled:
            return False
        if self.is_running:
            return False
        if self.next_run_time is None:
            return False
        if self.end_time and datetime.now() > self.end_time:
            return False

        now = now or datetime.now()
        return now >= self.next_run_time

    async def run(self) -> TaskResult:
        start_time = datetime.now()
        self.is_running = True
        self.run_count += 1

        success = False
        error = None
        check_result = None
        output = {}

        try:
            for retry in range(self.max_retries + 1):
                try:
                    result = self.func()
                    if isinstance(result, CheckResult):
                        check_result = result
                    elif isinstance(result, dict):
                        output = result
                    success = True
                    self.consecutive_failures = 0
                    break
                except Exception as e:
                    error = str(e)
                    if retry < self.max_retries:
                        time.sleep(self.retry_delay)
                    else:
                        self.consecutive_failures += 1
                        success = False
        except Exception as e:
            error = str(e)
            success = False
            self.consecutive_failures += 1
        finally:
            end_time = datetime.now()
            self.last_run_time = end_time
            self.is_running = False
            self._calculate_next_run()

        task_result = TaskResult(
            task_id=self.id,
            task_name=self.name,
            success=success,
            start_time=start_time,
            end_time=end_time,
            check_result=check_result,
            error=error,
            output=output,
        )

        self.last_result = task_result
        return task_result

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "schedule_type": self.schedule_type.value,
            "cron_expression": self.cron_expression,
            "interval_seconds": self.interval_seconds,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "max_retries": self.max_retries,
            "retry_delay": self.retry_delay,
            "dependencies": [d.to_dict() for d in self.dependencies],
            "enabled": self.enabled,
            "description": self.description,
            "next_run_time": self.next_run_time.isoformat() if self.next_run_time else None,
            "last_run_time": self.last_run_time.isoformat() if self.last_run_time else None,
            "run_count": self.run_count,
            "is_running": self.is_running,
            "consecutive_failures": self.consecutive_failures,
            "metadata": self.metadata,
        }


class QualityScheduler:
    def __init__(
        self,
        checker: Optional[DataQualityChecker] = None,
        sleep_interval: int = 1,
    ):
        self.checker = checker or DataQualityChecker()
        self.sleep_interval = sleep_interval
        self.tasks: Dict[str, Task] = {}
        self.task_results: List[TaskResult] = []
        self._running: bool = False
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

    def add_task(
        self,
        task: Task,
    ) -> str:
        with self._lock:
            self.tasks[task.id] = task
            return task.id

    def add_quality_check_task(
        self,
        name: str,
        df_provider: Callable[[], pd.DataFrame],
        schedule_type: ScheduleType = ScheduleType.INTERVAL,
        cron_expression: Optional[str] = None,
        interval_seconds: Optional[int] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        max_retries: int = 0,
        retry_delay: int = 5,
        dependencies: Optional[List[TaskDependency]] = None,
        description: str = "",
        callback: Optional[Callable[[TaskResult], None]] = None,
    ) -> str:
        def check_func():
            df = df_provider()
            return self.checker.check_batch(df)

        task = Task(
            name=name,
            func=check_func,
            schedule_type=schedule_type,
            cron_expression=cron_expression,
            interval_seconds=interval_seconds,
            start_time=start_time,
            end_time=end_time,
            max_retries=max_retries,
            retry_delay=retry_delay,
            dependencies=dependencies or [],
            description=description,
            metadata={"callback": callback},
        )

        return self.add_task(task)

    def remove_task(self, task_id: str) -> bool:
        with self._lock:
            if task_id in self.tasks:
                del self.tasks[task_id]
                return True
            return False

    def get_task(self, task_id: str) -> Optional[Task]:
        return self.tasks.get(task_id)

    def list_tasks(self) -> List[Task]:
        return sorted(self.tasks.values(), key=lambda t: t.name)

    def _check_dependencies(self, task: Task) -> bool:
        for dep in task.dependencies:
            dep_task = self.get_task(dep.task_id)
            if not dep_task:
                return False

            if dep_task.last_result is None:
                return False

            if dep.wait_for_success and not dep_task.last_result.success:
                return False

            if dep.timeout:
                if dep_task.last_result.end_time + timedelta(seconds=dep.timeout) < datetime.now():
                    return False

        return True

    def _run_once(self) -> None:
        now = datetime.now()
        tasks_to_run = []

        with self._lock:
            for task in self.tasks.values():
                if task.should_run(now) and self._check_dependencies(task):
                    tasks_to_run.append(task)

        for task in tasks_to_run:
            try:
                result = task.run()
                self.task_results.append(result)

                callback = task.metadata.get("callback")
                if callback and callable(callback):
                    try:
                        callback(result)
                    except Exception:
                        pass

                if len(self.task_results) > 1000:
                    self.task_results = self.task_results[-1000:]
            except Exception as e:
                pass

    def start(self, block: bool = False) -> None:
        if self._running:
            return

        self._running = True

        if block:
            self._run_loop()
        else:
            self._thread = threading.Thread(target=self._run_loop, daemon=True)
            self._thread.start()

    def _run_loop(self) -> None:
        while self._running:
            try:
                self._run_once()
            except Exception:
                pass
            time.sleep(self.sleep_interval)

    def stop(self, wait: bool = True) -> None:
        self._running = False
        if wait and self._thread and self._thread.is_alive():
            self._thread.join(timeout=10)

    def run_all(self) -> List[TaskResult]:
        results = []
        for task in self.tasks.values():
            if task.enabled:
                result = task.run()
                results.append(result)
                self.task_results.append(result)
        return results

    def run_task(self, task_id: str) -> Optional[TaskResult]:
        task = self.get_task(task_id)
        if not task:
            return None

        result = task.run()
        self.task_results.append(result)
        return result

    def get_task_results(
        self,
        task_id: Optional[str] = None,
        limit: Optional[int] = None,
        success_only: bool = False,
    ) -> List[TaskResult]:
        results = sorted(self.task_results, key=lambda r: r.start_time, reverse=True)

        if task_id:
            results = [r for r in results if r.task_id == task_id]

        if success_only:
            results = [r for r in results if r.success]

        if limit:
            results = results[:limit]

        return results

    def get_schedule_summary(self) -> Dict[str, Any]:
        total_tasks = len(self.tasks)
        enabled_tasks = sum(1 for t in self.tasks.values() if t.enabled)
        running_tasks = sum(1 for t in self.tasks.values() if t.is_running)
        scheduled_tasks = sum(1 for t in self.tasks.values() if t.next_run_time is not None)

        next_runs = []
        for task in self.tasks.values():
            if task.next_run_time:
                next_runs.append({
                    "task_id": task.id,
                    "task_name": task.name,
                    "next_run": task.next_run_time.isoformat(),
                })
        next_runs.sort(key=lambda x: x["next_run"])

        return {
            "total_tasks": total_tasks,
            "enabled_tasks": enabled_tasks,
            "running_tasks": running_tasks,
            "scheduled_tasks": scheduled_tasks,
            "is_running": self._running,
            "total_results": len(self.task_results),
            "next_runs": next_runs[:10],
            "recent_results": [r.to_dict() for r in self.get_task_results(limit=5)],
        }

    def pause_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task:
            task.enabled = False
            return True
        return False

    def resume_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task:
            task.enabled = True
            task._calculate_next_run()
            return True
        return False
