import logging
import gc
import weakref
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, List, Optional

from src.infrastructure.config.settings import LifecycleConfig
from src.infrastructure.storage.archive_storage import ArchiveStorage

logger = logging.getLogger(__name__)


@dataclass
class CleanupPolicy:
    name: str
    database_pattern: str = "*"
    table_pattern: str = "*"
    retention_days: int = 365
    cleanup_action: str = "archive_delete"
    enabled: bool = True
    priority: int = 0


@dataclass
class CleanupTask:
    task_id: str
    database_name: str
    table_name: str
    policy_name: str
    retention_days: int
    rows_deleted: int = 0
    rows_archived: int = 0
    status: str = "pending"
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class DataCleanupManager:
    def __init__(self, config: LifecycleConfig, archive_storage: Optional[ArchiveStorage] = None):
        self._config = config
        self._archive_storage = archive_storage
        self._policies: List[CleanupPolicy] = []
        self._cleanup_callbacks: Dict[str, weakref.ReferenceType] = {}
        self._scheduler = None
        self._closed = False
        self._setup_default_policies()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True

        if self._scheduler:
            try:
                self._scheduler.shutdown(wait=False)
            except Exception:
                pass
            self._scheduler = None

        self._cleanup_callbacks.clear()
        self._policies.clear()
        gc.collect()

    def _setup_default_policies(self) -> None:
        self._policies = [
            CleanupPolicy(
                name="default_archive_cleanup",
                retention_days=self._config.archive_retention_days,
                cleanup_action="archive_delete",
                priority=100,
            ),
        ]

    def add_policy(self, policy: CleanupPolicy) -> None:
        if self._closed:
            raise RuntimeError("DataCleanupManager has been closed")
        self._policies.append(policy)
        self._policies.sort(key=lambda p: p.priority)

    def remove_policy(self, name: str) -> None:
        if self._closed:
            raise RuntimeError("DataCleanupManager has been closed")
        self._policies = [p for p in self._policies if p.name != name]
        self._cleanup_callbacks.pop(name, None)

    def register_callback(self, action_type: str, callback: Callable) -> None:
        if self._closed:
            raise RuntimeError("DataCleanupManager has been closed")
        try:
            if hasattr(callback, '__self__'):
                self._cleanup_callbacks[action_type] = weakref.WeakMethod(callback)
            else:
                self._cleanup_callbacks[action_type] = weakref.ref(callback)
        except TypeError:
            self._cleanup_callbacks[action_type] = weakref.ref(callback)

    def evaluate_cleanup(
        self,
        database_name: str,
        table_name: str,
        table_stats: Dict[str, Any],
    ) -> List[CleanupTask]:
        import uuid
        import fnmatch

        tasks = []
        now = datetime.utcnow()
        oldest_record = table_stats.get("oldest_record_date")

        if not oldest_record:
            return tasks

        if isinstance(oldest_record, str):
            oldest_record = datetime.fromisoformat(oldest_record)

        for policy in self._policies:
            if not policy.enabled:
                continue

            if not fnmatch.fnmatch(database_name, policy.database_pattern):
                continue
            if not fnmatch.fnmatch(table_name, policy.table_pattern):
                continue

            age_days = (now - oldest_record).days
            if age_days >= policy.retention_days:
                task = CleanupTask(
                    task_id=str(uuid.uuid4()),
                    database_name=database_name,
                    table_name=table_name,
                    policy_name=policy.name,
                    retention_days=policy.retention_days,
                    started_at=now,
                )
                tasks.append(task)

        return tasks

    def execute_cleanup(self, task: CleanupTask) -> CleanupTask:
        if self._closed:
            raise RuntimeError("DataCleanupManager has been closed")

        try:
            callback_ref = self._cleanup_callbacks.get(task.policy_name)
            if callback_ref:
                callback = callback_ref()
                if callback:
                    result = callback(task)
                    if isinstance(result, dict):
                        task.rows_deleted = result.get("rows_deleted", 0)
                        task.rows_archived = result.get("rows_archived", 0)
                else:
                    del self._cleanup_callbacks[task.policy_name]
            elif self._archive_storage:
                self._execute_archive_cleanup(task)
            else:
                logger.warning(f"No cleanup handler for policy: {task.policy_name}")
                task.status = "no_handler"

            if task.status == "pending":
                task.status = "completed"
            task.completed_at = datetime.utcnow()

        except Exception as e:
            task.status = "failed"
            task.error_message = str(e)
            task.completed_at = datetime.utcnow()
            logger.error(f"Cleanup task {task.task_id} failed: {e}")

        finally:
            gc.collect()

        return task

    def _execute_archive_cleanup(self, task: CleanupTask) -> None:
        cutoff = datetime.utcnow() - timedelta(days=task.retention_days)
        date_str = cutoff.strftime("%Y%m%d")
        deleted = self._archive_storage.delete_archive(
            task.database_name, task.table_name, date_str
        )
        if deleted:
            task.rows_deleted = 1
            task.status = "completed"
        else:
            task.status = "no_data"

    def cleanup_expired_archives(self, retention_days: Optional[int] = None) -> List[str]:
        days = retention_days or self._config.archive_retention_days
        if self._archive_storage:
            return self._archive_storage.cleanup_expired(days)
        return []

    def schedule_periodic_cleanup(self) -> None:
        if self._closed:
            raise RuntimeError("DataCleanupManager has been closed")

        from apscheduler.schedulers.background import BackgroundScheduler

        if self._scheduler is None:
            self._scheduler = BackgroundScheduler()
            interval_hours = self._config.cleanup_interval_hours
            self._scheduler.add_job(
                self._run_scheduled_cleanup,
                "interval",
                hours=interval_hours,
                id="data_cleanup_job",
                replace_existing=True,
            )
            self._scheduler.start()
            logger.info(f"Scheduled periodic cleanup every {interval_hours} hours")

    def _run_scheduled_cleanup(self) -> None:
        logger.info("Running scheduled data cleanup")
        deleted = self.cleanup_expired_archives()
        logger.info(f"Cleanup completed, removed {len(deleted)} expired archives")

    def get_policies(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": p.name,
                "database_pattern": p.database_pattern,
                "table_pattern": p.table_pattern,
                "retention_days": p.retention_days,
                "cleanup_action": p.cleanup_action,
                "enabled": p.enabled,
                "priority": p.priority,
            }
            for p in self._policies
        ]
