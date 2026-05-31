import threading
import uuid
from datetime import datetime
from enum import Enum
from typing import Any, Callable, Dict, List, Optional
from pathlib import Path

from .storage_tier import StorageTier, StorageTierType
from .policy import LifecyclePolicy, PolicyConfig


class MigrationStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class MigrationProgress:
    def __init__(self, total_items: int = 0):
        self.total_items = total_items
        self.completed_items = 0
        self.failed_items = 0
        self.current_item: Optional[str] = None
        self.start_time: Optional[datetime] = None
        self.end_time: Optional[datetime] = None
        self.error_messages: List[str] = []

    @property
    def percentage(self) -> float:
        if self.total_items == 0:
            return 0.0
        return (self.completed_items / self.total_items) * 100

    @property
    def elapsed_time(self) -> Optional[float]:
        if self.start_time is None:
            return None
        end_time = self.end_time or datetime.now()
        return (end_time - self.start_time).total_seconds()

    @property
    def estimated_remaining_time(self) -> Optional[float]:
        if self.elapsed_time is None or self.completed_items == 0:
            return None
        rate = self.completed_items / self.elapsed_time
        remaining_items = self.total_items - self.completed_items
        if rate > 0:
            return remaining_items / rate
        return None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_items": self.total_items,
            "completed_items": self.completed_items,
            "failed_items": self.failed_items,
            "current_item": self.current_item,
            "percentage": self.percentage,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "elapsed_time": self.elapsed_time,
            "estimated_remaining_time": self.estimated_remaining_time,
            "error_messages": self.error_messages.copy(),
        }


class MigrationTask:
    def __init__(
        self,
        source_tier: StorageTier,
        target_tier: StorageTier,
        keys: List[str],
        auto_cleanup_source: bool = True,
        description: Optional[str] = None,
        priority: int = 0,
    ):
        self.task_id = str(uuid.uuid4())
        self.source_tier = source_tier
        self.target_tier = target_tier
        self.keys = keys
        self.auto_cleanup_source = auto_cleanup_source
        self.description = description
        self.priority = priority
        self.status = MigrationStatus.PENDING
        self.progress = MigrationProgress(total_items=len(keys))
        self.created_at = datetime.now()
        self.updated_at = self.created_at
        self._pause_event = threading.Event()
        self._cancel_event = threading.Event()

    def pause(self) -> None:
        if self.status == MigrationStatus.RUNNING:
            self.status = MigrationStatus.PAUSED
            self._pause_event.set()
            self.updated_at = datetime.now()

    def resume(self) -> None:
        if self.status == MigrationStatus.PAUSED:
            self.status = MigrationStatus.RUNNING
            self._pause_event.clear()
            self.updated_at = datetime.now()

    def cancel(self) -> None:
        if self.status in [MigrationStatus.RUNNING, MigrationStatus.PAUSED, MigrationStatus.PENDING]:
            self.status = MigrationStatus.CANCELLED
            self._cancel_event.set()
            self.updated_at = datetime.now()

    def is_paused(self) -> bool:
        return self._pause_event.is_set()

    def is_cancelled(self) -> bool:
        return self._cancel_event.is_set()

    def wait_if_paused(self, timeout: Optional[float] = None) -> bool:
        if self._pause_event.is_set():
            return self._pause_event.wait(timeout=timeout)
        return True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "source_tier": self.source_tier.name,
            "source_tier_type": self.source_tier.tier_type.value,
            "target_tier": self.target_tier.name,
            "target_tier_type": self.target_tier.tier_type.value,
            "keys_count": len(self.keys),
            "auto_cleanup_source": self.auto_cleanup_source,
            "description": self.description,
            "priority": self.priority,
            "status": self.status.value,
            "progress": self.progress.to_dict(),
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
        }


class DataMigrator:
    def __init__(
        self,
        storage_tiers: Dict[StorageTierType, StorageTier],
        max_concurrent_tasks: int = 3,
        progress_callback: Optional[Callable[[MigrationTask], None]] = None,
    ):
        self.storage_tiers = storage_tiers
        self.max_concurrent_tasks = max_concurrent_tasks
        self.progress_callback = progress_callback
        self.tasks: Dict[str, MigrationTask] = {}
        self._lock = threading.RLock()
        self._active_threads: Dict[str, threading.Thread] = {}

    def create_migration_task(
        self,
        source_tier_type: StorageTierType,
        target_tier_type: StorageTierType,
        keys: List[str],
        auto_cleanup_source: bool = True,
        description: Optional[str] = None,
        priority: int = 0,
    ) -> MigrationTask:
        if source_tier_type not in self.storage_tiers:
            raise ValueError(f"Source tier {source_tier_type.value} not found")
        if target_tier_type not in self.storage_tiers:
            raise ValueError(f"Target tier {target_tier_type.value} not found")
        if source_tier_type == target_tier_type:
            raise ValueError("Source and target tiers must be different")

        source_tier = self.storage_tiers[source_tier_type]
        target_tier = self.storage_tiers[target_tier_type]

        task = MigrationTask(
            source_tier=source_tier,
            target_tier=target_tier,
            keys=keys,
            auto_cleanup_source=auto_cleanup_source,
            description=description,
            priority=priority,
        )

        with self._lock:
            self.tasks[task.task_id] = task

        return task

    def create_migration_task_from_policy(
        self,
        policy: LifecyclePolicy,
        tier_type: StorageTierType,
        metadata_provider: Callable[[str, StorageTierType], Dict[str, Any]],
        auto_execute: bool = False,
    ) -> List[MigrationTask]:
        if tier_type not in self.storage_tiers:
            raise ValueError(f"Tier {tier_type.value} not found")

        tier = self.storage_tiers[tier_type]
        all_keys = tier.list_keys()

        migration_keys: Dict[StorageTierType, List[str]] = {}

        for key in all_keys:
            metadata = metadata_provider(key, tier_type)
            result = policy.evaluate(metadata, tier_type)

            if result["tier_action"]:
                target_tier = StorageTierType(result["tier_action"]["target_tier"])
                if target_tier not in migration_keys:
                    migration_keys[target_tier] = []
                migration_keys[target_tier].append(key)

        tasks = []
        for target_tier, keys in migration_keys.items():
            if keys:
                task = self.create_migration_task(
                    source_tier_type=tier_type,
                    target_tier_type=target_tier,
                    keys=keys,
                    description=f"Auto-migration from {tier_type.value} to {target_tier.value}",
                    priority=10,
                )
                tasks.append(task)

                if auto_execute:
                    self.execute_task_async(task.task_id)

        return tasks

    def execute_task(self, task_id: str) -> MigrationTask:
        with self._lock:
            if task_id not in self.tasks:
                raise ValueError(f"Task {task_id} not found")

            task = self.tasks[task_id]
            if task.status in [MigrationStatus.RUNNING, MigrationStatus.COMPLETED]:
                return task

            task.status = MigrationStatus.RUNNING
            task.progress.start_time = datetime.now()
            task.updated_at = datetime.now()

        try:
            self._execute_migration(task)
        except Exception as e:
            task.status = MigrationStatus.FAILED
            task.progress.error_messages.append(str(e))
        finally:
            task.progress.end_time = datetime.now()
            task.updated_at = datetime.now()
            if task.status == MigrationStatus.RUNNING:
                task.status = MigrationStatus.COMPLETED

        return task

    def execute_task_async(self, task_id: str) -> str:
        with self._lock:
            if task_id not in self.tasks:
                raise ValueError(f"Task {task_id} not found")

            if len(self._active_threads) >= self.max_concurrent_tasks:
                raise RuntimeError("Maximum concurrent tasks reached")

            if task_id in self._active_threads:
                return task_id

        thread = threading.Thread(target=self._execute_task_thread, args=(task_id,), daemon=True)
        with self._lock:
            self._active_threads[task_id] = thread
        thread.start()
        return task_id

    def _execute_task_thread(self, task_id: str) -> None:
        try:
            self.execute_task(task_id)
        finally:
            with self._lock:
                if task_id in self._active_threads:
                    del self._active_threads[task_id]

    def _execute_migration(self, task: MigrationTask) -> None:
        for i, key in enumerate(task.keys):
            if task.is_cancelled():
                break

            task.wait_if_paused()
            if task.is_cancelled():
                break

            task.progress.current_item = key
            task.updated_at = datetime.now()

            try:
                data = task.source_tier.get(key)
                if data is None:
                    raise ValueError(f"Key {key} not found in source tier")

                success = task.target_tier.put(key, data)
                if not success:
                    raise ValueError(f"Failed to put key {key} in target tier")

                if task.auto_cleanup_source:
                    delete_success = task.source_tier.delete(key)
                    if not delete_success:
                        task.progress.error_messages.append(f"Failed to delete {key} from source")

                task.progress.completed_items += 1

            except Exception as e:
                task.progress.failed_items += 1
                task.progress.error_messages.append(f"Error migrating {key}: {str(e)}")

            task.updated_at = datetime.now()

            if self.progress_callback:
                try:
                    self.progress_callback(task)
                except Exception:
                    pass

    def get_task(self, task_id: str) -> Optional[MigrationTask]:
        with self._lock:
            return self.tasks.get(task_id)

    def list_tasks(
        self,
        status: Optional[MigrationStatus] = None,
        source_tier: Optional[StorageTierType] = None,
        target_tier: Optional[StorageTierType] = None,
    ) -> List[MigrationTask]:
        with self._lock:
            tasks = list(self.tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]
        if source_tier:
            tasks = [t for t in tasks if t.source_tier.tier_type == source_tier]
        if target_tier:
            tasks = [t for t in tasks if t.target_tier.tier_type == target_tier]

        return sorted(tasks, key=lambda t: t.created_at, reverse=True)

    def pause_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task and task.status == MigrationStatus.RUNNING:
            task.pause()
            return True
        return False

    def resume_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task and task.status == MigrationStatus.PAUSED:
            task.resume()
            return True
        return False

    def cancel_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task and task.status in [MigrationStatus.RUNNING, MigrationStatus.PAUSED, MigrationStatus.PENDING]:
            task.cancel()
            return True
        return False

    def get_active_tasks_count(self) -> int:
        with self._lock:
            return len(self._active_threads)

    def wait_for_completion(self, task_id: str, timeout: Optional[float] = None) -> bool:
        thread = None
        with self._lock:
            thread = self._active_threads.get(task_id)

        if thread:
            thread.join(timeout=timeout)
            return not thread.is_alive()

        task = self.get_task(task_id)
        return task is not None and task.status in [MigrationStatus.COMPLETED, MigrationStatus.FAILED, MigrationStatus.CANCELLED]

    def get_migration_statistics(self) -> Dict[str, Any]:
        with self._lock:
            tasks = list(self.tasks.values())

        stats = {
            "total_tasks": len(tasks),
            "status_counts": {status.value: 0 for status in MigrationStatus},
            "total_keys_migrated": 0,
            "total_keys_failed": 0,
            "by_source_tier": {},
            "by_target_tier": {},
        }

        for task in tasks:
            stats["status_counts"][task.status.value] += 1
            stats["total_keys_migrated"] += task.progress.completed_items
            stats["total_keys_failed"] += task.progress.failed_items

            source_type = task.source_tier.tier_type.value
            target_type = task.target_tier.tier_type.value

            if source_type not in stats["by_source_tier"]:
                stats["by_source_tier"][source_type] = 0
            stats["by_source_tier"][source_type] += len(task.keys)

            if target_type not in stats["by_target_tier"]:
                stats["by_target_tier"][target_type] = 0
            stats["by_target_tier"][target_type] += task.progress.completed_items

        return stats
