import re
import threading
import time
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from .storage_tier import StorageTierType
from .policy import LifecyclePolicy, PolicyConfig
from .migrator import DataMigrator
from .archiver import DataArchiver
from .cleaner import DataCleaner


class TaskStatus(Enum):
    PENDING = "pending"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    PAUSED = "paused"


class TaskType(Enum):
    MIGRATION = "migration"
    ARCHIVE = "archive"
    CLEANUP = "cleanup"
    CUSTOM = "custom"


class Trigger(ABC):
    @abstractmethod
    def get_next_run_time(self, last_run_time: Optional[datetime] = None) -> Optional[datetime]:
        pass

    @abstractmethod
    def should_run(self, current_time: datetime, last_run_time: Optional[datetime] = None) -> bool:
        pass

    @abstractmethod
    def to_dict(self) -> Dict[str, Any]:
        pass

    @classmethod
    @abstractmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Trigger":
        pass


class IntervalTrigger(Trigger):
    def __init__(self, interval_seconds: int, start_time: Optional[datetime] = None):
        if interval_seconds <= 0:
            raise ValueError("Interval must be positive")
        self.interval_seconds = interval_seconds
        self.start_time = start_time or datetime.now()

    def get_next_run_time(self, last_run_time: Optional[datetime] = None) -> Optional[datetime]:
        if last_run_time is None:
            return self.start_time
        return last_run_time + timedelta(seconds=self.interval_seconds)

    def should_run(self, current_time: datetime, last_run_time: Optional[datetime] = None) -> bool:
        next_run = self.get_next_run_time(last_run_time)
        if next_run is None:
            return False
        return current_time >= next_run

    def to_dict(self) -> Dict[str, Any]:
        return {
            "type": "interval",
            "interval_seconds": self.interval_seconds,
            "start_time": self.start_time.isoformat(),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "IntervalTrigger":
        return cls(
            interval_seconds=data["interval_seconds"],
            start_time=datetime.fromisoformat(data["start_time"]) if data.get("start_time") else None,
        )


class CronTrigger(Trigger):
    def __init__(self, cron_expression: str, timezone: Optional[str] = None):
        self.cron_expression = cron_expression
        self.timezone = timezone
        self._fields = self._parse_cron_expression(cron_expression)

    def _parse_cron_expression(self, expression: str) -> Dict[str, List[int]]:
        parts = expression.strip().split()
        if len(parts) != 5:
            raise ValueError(f"Invalid cron expression: {expression}. Expected 5 fields.")

        minute_expr, hour_expr, day_expr, month_expr, weekday_expr = parts

        return {
            "minute": self._parse_field(minute_expr, 0, 59),
            "hour": self._parse_field(hour_expr, 0, 23),
            "day": self._parse_field(day_expr, 1, 31),
            "month": self._parse_field(month_expr, 1, 12),
            "weekday": self._parse_field(weekday_expr, 0, 6),
        }

    def _parse_field(self, field_expr: str, min_val: int, max_val: int) -> List[int]:
        if field_expr == "*":
            return list(range(min_val, max_val + 1))

        values: List[int] = []
        parts = field_expr.split(",")

        for part in parts:
            if "-" in part:
                start, end = part.split("-")
                start_int = int(start)
                end_int = int(end)
                if "/" in end:
                    end_str, step = end.split("/")
                    end_int = int(end_str)
                    step_int = int(step)
                    values.extend(range(start_int, end_int + 1, step_int))
                else:
                    values.extend(range(start_int, end_int + 1))
            elif "/" in part:
                base, step = part.split("/")
                base_int = int(base) if base != "*" else min_val
                step_int = int(step)
                values.extend(range(base_int, max_val + 1, step_int))
            else:
                values.append(int(part))

        return sorted(set(v for v in values if min_val <= v <= max_val))

    def get_next_run_time(self, last_run_time: Optional[datetime] = None) -> Optional[datetime]:
        current = last_run_time or datetime.now()
        current = current.replace(second=0, microsecond=0) + timedelta(minutes=1)

        for _ in range(525600):
            if (
                current.minute in self._fields["minute"]
                and current.hour in self._fields["hour"]
                and current.day in self._fields["day"]
                and current.month in self._fields["month"]
                and current.weekday() in self._fields["weekday"]
            ):
                return current
            current += timedelta(minutes=1)

        return None

    def should_run(self, current_time: datetime, last_run_time: Optional[datetime] = None) -> bool:
        if last_run_time is not None:
            next_run = self.get_next_run_time(last_run_time)
            if next_run is None:
                return False
            return current_time >= next_run

        return (
            current_time.minute in self._fields["minute"]
            and current_time.hour in self._fields["hour"]
            and current_time.day in self._fields["day"]
            and current_time.month in self._fields["month"]
            and current_time.weekday() in self._fields["weekday"]
        )

    def to_dict(self) -> Dict[str, Any]:
        return {
            "type": "cron",
            "cron_expression": self.cron_expression,
            "timezone": self.timezone,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "CronTrigger":
        return cls(
            cron_expression=data["cron_expression"],
            timezone=data.get("timezone"),
        )


class ScheduledTask:
    def __init__(
        self,
        name: str,
        task_type: TaskType,
        trigger: Trigger,
        action: Callable[..., Any],
        args: Optional[List[Any]] = None,
        kwargs: Optional[Dict[str, Any]] = None,
        description: Optional[str] = None,
        enabled: bool = True,
        max_retries: int = 0,
        retry_interval_seconds: int = 60,
        priority: int = 0,
    ):
        self.task_id = str(uuid.uuid4())
        self.name = name
        self.task_type = task_type if isinstance(task_type, TaskType) else TaskType(task_type)
        self.trigger = trigger
        self.action = action
        self.args = args or []
        self.kwargs = kwargs or {}
        self.description = description
        self.enabled = enabled
        self.max_retries = max_retries
        self.retry_interval_seconds = retry_interval_seconds
        self.priority = priority
        self.status = TaskStatus.PENDING
        self.last_run_time: Optional[datetime] = None
        self.next_run_time: Optional[datetime] = self.trigger.get_next_run_time()
        self.current_retries = 0
        self.error_messages: List[str] = []
        self.run_count = 0
        self.success_count = 0
        self.failure_count = 0
        self.created_at = datetime.now()
        self.updated_at = self.created_at

    def execute(self) -> Any:
        if not self.enabled:
            return None

        self.status = TaskStatus.RUNNING
        self.last_run_time = datetime.now()
        self.current_retries = 0
        self.updated_at = datetime.now()

        while True:
            try:
                result = self.action(*self.args, **self.kwargs)
                self.status = TaskStatus.COMPLETED
                self.run_count += 1
                self.success_count += 1
                self.next_run_time = self.trigger.get_next_run_time(self.last_run_time)
                self.updated_at = datetime.now()
                return result
            except Exception as e:
                self.current_retries += 1
                self.error_messages.append(str(e))

                if self.current_retries > self.max_retries:
                    self.status = TaskStatus.FAILED
                    self.run_count += 1
                    self.failure_count += 1
                    self.next_run_time = self.trigger.get_next_run_time(self.last_run_time)
                    self.updated_at = datetime.now()
                    raise

                time.sleep(self.retry_interval_seconds)

    def should_execute(self, current_time: Optional[datetime] = None) -> bool:
        if not self.enabled or self.status == TaskStatus.RUNNING:
            return False

        current_time = current_time or datetime.now()
        return self.trigger.should_run(current_time, self.last_run_time)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "name": self.name,
            "task_type": self.task_type.value,
            "trigger": self.trigger.to_dict(),
            "description": self.description,
            "enabled": self.enabled,
            "max_retries": self.max_retries,
            "retry_interval_seconds": self.retry_interval_seconds,
            "priority": self.priority,
            "status": self.status.value,
            "last_run_time": self.last_run_time.isoformat() if self.last_run_time else None,
            "next_run_time": self.next_run_time.isoformat() if self.next_run_time else None,
            "current_retries": self.current_retries,
            "error_messages": self.error_messages.copy(),
            "run_count": self.run_count,
            "success_count": self.success_count,
            "failure_count": self.failure_count,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
        }


class LifecycleScheduler:
    def __init__(
        self,
        migrator: Optional[DataMigrator] = None,
        archiver: Optional[DataArchiver] = None,
        cleaner: Optional[DataCleaner] = None,
        policy: Optional[LifecyclePolicy] = None,
        check_interval_seconds: int = 60,
    ):
        self.migrator = migrator
        self.archiver = archiver
        self.cleaner = cleaner
        self.policy = policy
        self.check_interval_seconds = check_interval_seconds
        self.tasks: Dict[str, ScheduledTask] = {}
        self._lock = threading.RLock()
        self._running = False
        self._scheduler_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._task_execution_lock = threading.Lock()

    def add_task(self, task: ScheduledTask) -> str:
        with self._lock:
            self.tasks[task.task_id] = task
        return task.task_id

    def add_migration_task(
        self,
        name: str,
        trigger: Trigger,
        source_tier: StorageTierType,
        target_tier: StorageTierType,
        description: Optional[str] = None,
        enabled: bool = True,
    ) -> str:
        if self.migrator is None:
            raise RuntimeError("Migrator not configured")

        def migration_action():
            if self.policy is None:
                return None

            def metadata_provider(key, tier):
                return {"age_days": 30, "days_since_last_access": 15}

            return self.migrator.create_migration_task_from_policy(
                policy=self.policy,
                tier_type=source_tier,
                metadata_provider=metadata_provider,
                auto_execute=True,
            )

        task = ScheduledTask(
            name=name,
            task_type=TaskType.MIGRATION,
            trigger=trigger,
            action=migration_action,
            description=description or f"Auto migration from {source_tier.value} to {target_tier.value}",
            enabled=enabled,
        )
        return self.add_task(task)

    def add_cleanup_task(
        self,
        name: str,
        trigger: Trigger,
        tier_type: Optional[StorageTierType] = None,
        description: Optional[str] = None,
        enabled: bool = True,
        dry_run: bool = False,
    ) -> str:
        if self.cleaner is None:
            raise RuntimeError("Cleaner not configured")
        if self.policy is None:
            raise RuntimeError("Policy not configured")

        def cleanup_action():
            return self.cleaner.execute_auto_cleanup(
                policy=self.policy,
                tier_type=tier_type,
                dry_run=dry_run,
            )

        task = ScheduledTask(
            name=name,
            task_type=TaskType.CLEANUP,
            trigger=trigger,
            action=cleanup_action,
            description=description or f"Auto cleanup for {tier_type.value if tier_type else 'all tiers'}",
            enabled=enabled,
        )
        return self.add_task(task)

    def add_archive_task(
        self,
        name: str,
        trigger: Trigger,
        description: Optional[str] = None,
        enabled: bool = True,
    ) -> str:
        if self.archiver is None:
            raise RuntimeError("Archiver not configured")

        def archive_action():
            return self.archiver.cleanup_expired_archives()

        task = ScheduledTask(
            name=name,
            task_type=TaskType.ARCHIVE,
            trigger=trigger,
            action=archive_action,
            description=description or "Archive expired data cleanup",
            enabled=enabled,
        )
        return self.add_task(task)

    def remove_task(self, task_id: str) -> bool:
        with self._lock:
            if task_id in self.tasks:
                del self.tasks[task_id]
                return True
            return False

    def get_task(self, task_id: str) -> Optional[ScheduledTask]:
        with self._lock:
            return self.tasks.get(task_id)

    def list_tasks(
        self,
        status: Optional[TaskStatus] = None,
        task_type: Optional[TaskType] = None,
        enabled_only: bool = False,
    ) -> List[ScheduledTask]:
        with self._lock:
            tasks = list(self.tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]
        if task_type:
            tasks = [t for t in tasks if t.task_type == task_type]
        if enabled_only:
            tasks = [t for t in tasks if t.enabled]

        return sorted(tasks, key=lambda t: (t.priority, t.next_run_time or datetime.max))

    def enable_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task:
            task.enabled = True
            task.updated_at = datetime.now()
            return True
        return False

    def disable_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task:
            task.enabled = False
            task.updated_at = datetime.now()
            return True
        return False

    def trigger_task(self, task_id: str) -> Any:
        task = self.get_task(task_id)
        if not task:
            raise ValueError(f"Task {task_id} not found")

        def execute_thread():
            try:
                task.execute()
            except Exception:
                pass

        thread = threading.Thread(target=execute_thread, daemon=True)
        thread.start()
        return task.task_id

    def _run_scheduler_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self._check_and_run_tasks()
            except Exception:
                pass

            self._stop_event.wait(self.check_interval_seconds)

    def _check_and_run_tasks(self) -> None:
        current_time = datetime.now()
        tasks_to_run = []

        with self._lock:
            for task in self.tasks.values():
                if task.should_execute(current_time):
                    tasks_to_run.append(task)

        tasks_to_run.sort(key=lambda t: t.priority, reverse=True)

        for task in tasks_to_run:
            if self._stop_event.is_set():
                break

            try:
                with self._task_execution_lock:
                    task.execute()
            except Exception:
                pass

    def start(self) -> None:
        if self._running:
            return

        self._running = True
        self._stop_event.clear()
        self._scheduler_thread = threading.Thread(target=self._run_scheduler_loop, daemon=True)
        self._scheduler_thread.start()

    def stop(self) -> None:
        if not self._running:
            return

        self._running = False
        self._stop_event.set()

        if self._scheduler_thread:
            self._scheduler_thread.join(timeout=5)
            self._scheduler_thread = None

    def is_running(self) -> bool:
        return self._running

    def get_scheduler_statistics(self) -> Dict[str, Any]:
        with self._lock:
            tasks = list(self.tasks.values())

        stats = {
            "total_tasks": len(tasks),
            "enabled_tasks": len([t for t in tasks if t.enabled]),
            "disabled_tasks": len([t for t in tasks if not t.enabled]),
            "status_counts": {status.value: 0 for status in TaskStatus},
            "type_counts": {type_.value: 0 for type_ in TaskType},
            "total_runs": sum(t.run_count for t in tasks),
            "total_successes": sum(t.success_count for t in tasks),
            "total_failures": sum(t.failure_count for t in tasks),
            "running": self._running,
        }

        for task in tasks:
            stats["status_counts"][task.status.value] += 1
            stats["type_counts"][task.task_type.value] += 1

        return stats

    def setup_default_schedule(
        self,
        migration_cron: str = "0 2 * * *",
        cleanup_cron: str = "0 3 * * 0",
        archive_cron: str = "0 4 1 * *",
    ) -> Dict[str, str]:
        task_ids = {}

        migration_trigger = CronTrigger(migration_cron)
        task_ids["migration"] = self.add_migration_task(
            name="daily_migration",
            trigger=migration_trigger,
            source_tier=StorageTierType.HOT,
            target_tier=StorageTierType.COLD,
            description="Daily hot to cold tier migration at 2 AM",
        )

        cleanup_trigger = CronTrigger(cleanup_cron)
        task_ids["cleanup"] = self.add_cleanup_task(
            name="weekly_cleanup",
            trigger=cleanup_trigger,
            description="Weekly cleanup of expired data on Sunday at 3 AM",
        )

        archive_trigger = CronTrigger(archive_cron)
        task_ids["archive"] = self.add_archive_task(
            name="monthly_archive_cleanup",
            trigger=archive_trigger,
            description="Monthly cleanup of expired archives on 1st at 4 AM",
        )

        return task_ids
