import json
import os
import threading
import uuid
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from .storage_tier import StorageTier, StorageTierType
from .policy import CleanupRule, LifecyclePolicy


class CleanupStatus(Enum):
    PENDING = "pending"
    SCANNING = "scanning"
    DELETING = "deleting"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class CleanupAuditLog:
    def __init__(
        self,
        task_id: str,
        key: str,
        tier_type: StorageTierType,
        action: str,
        success: bool,
        size_bytes: Optional[int] = None,
        error_message: Optional[str] = None,
        custom_metadata: Optional[Dict[str, Any]] = None,
    ):
        self.log_id = str(uuid.uuid4())
        self.task_id = task_id
        self.key = key
        self.tier_type = tier_type
        self.action = action
        self.success = success
        self.size_bytes = size_bytes
        self.error_message = error_message
        self.custom_metadata = custom_metadata or {}
        self.timestamp = datetime.now()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "log_id": self.log_id,
            "task_id": self.task_id,
            "key": self.key,
            "tier_type": self.tier_type.value,
            "action": self.action,
            "success": self.success,
            "size_bytes": self.size_bytes,
            "error_message": self.error_message,
            "custom_metadata": self.custom_metadata.copy(),
            "timestamp": self.timestamp.isoformat(),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "CleanupAuditLog":
        log = cls(
            task_id=data["task_id"],
            key=data["key"],
            tier_type=StorageTierType(data["tier_type"]),
            action=data["action"],
            success=data["success"],
            size_bytes=data.get("size_bytes"),
            error_message=data.get("error_message"),
            custom_metadata=data.get("custom_metadata", {}),
        )
        log.log_id = data["log_id"]
        log.timestamp = datetime.fromisoformat(data["timestamp"])
        return log


class CleanupTask:
    def __init__(
        self,
        tier_type: StorageTierType,
        rule: Optional[CleanupRule] = None,
        keys: Optional[List[str]] = None,
        description: Optional[str] = None,
        dry_run: bool = False,
    ):
        self.task_id = str(uuid.uuid4())
        self.tier_type = tier_type
        self.rule = rule
        self.keys = keys or []
        self.description = description
        self.dry_run = dry_run
        self.status = CleanupStatus.PENDING
        self.scanned_count = 0
        self.identified_count = 0
        self.deleted_count = 0
        self.failed_count = 0
        self.total_size_saved = 0
        self.error_messages: List[str] = []
        self.audit_logs: List[CleanupAuditLog] = []
        self.created_at = datetime.now()
        self.started_at: Optional[datetime] = None
        self.completed_at: Optional[datetime] = None
        self._cancel_event = threading.Event()

    def cancel(self) -> None:
        if self.status in [CleanupStatus.SCANNING, CleanupStatus.DELETING, CleanupStatus.PENDING]:
            self.status = CleanupStatus.CANCELLED
            self._cancel_event.set()

    def is_cancelled(self) -> bool:
        return self._cancel_event.is_set()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "tier_type": self.tier_type.value,
            "rule_name": self.rule.name if self.rule else None,
            "keys_count": len(self.keys),
            "description": self.description,
            "dry_run": self.dry_run,
            "status": self.status.value,
            "scanned_count": self.scanned_count,
            "identified_count": self.identified_count,
            "deleted_count": self.deleted_count,
            "failed_count": self.failed_count,
            "total_size_saved": self.total_size_saved,
            "error_messages": self.error_messages.copy(),
            "audit_logs_count": len(self.audit_logs),
            "created_at": self.created_at.isoformat(),
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
        }


class DataCleaner:
    def __init__(
        self,
        storage_tiers: Dict[StorageTierType, StorageTier],
        audit_log_path: str = "./data/cleanup_audit",
        secure_delete_passes: int = 3,
    ):
        self.storage_tiers = storage_tiers
        self.audit_log_path = Path(audit_log_path)
        self.audit_log_path.mkdir(parents=True, exist_ok=True)
        self.secure_delete_passes = secure_delete_passes
        self.tasks: Dict[str, CleanupTask] = {}
        self._lock = threading.RLock()
        self._active_threads: Dict[str, threading.Thread] = {}

    def _secure_delete(self, file_path: Path, passes: int = 3) -> None:
        if not file_path.exists():
            return

        file_size = file_path.stat().st_size
        for _ in range(passes):
            with open(file_path, "wb") as f:
                f.write(os.urandom(file_size))
                f.flush()
                os.fsync(f.fileno())
        file_path.unlink()

    def _save_audit_log(self, log: CleanupAuditLog) -> None:
        log_file = self.audit_log_path / f"{log.task_id}.jsonl"
        with open(log_file, "a", encoding="utf-8") as f:
            f.write(json.dumps(log.to_dict()) + "\n")

    def _get_age_days(self, tier: StorageTier, key: str) -> Optional[int]:
        mtime = tier.get_modification_time(key)
        if mtime:
            return (datetime.now() - mtime).days
        return None

    def _get_days_since_last_access(self, tier: StorageTier, key: str) -> Optional[int]:
        atime = tier.get_access_time(key)
        if atime:
            return (datetime.now() - atime).days
        return None

    def _build_metadata(self, tier: StorageTier, key: str) -> Dict[str, Any]:
        metadata = tier.get_metadata(key) or {}
        age_days = self._get_age_days(tier, key)
        days_since_access = self._get_days_since_last_access(tier, key)

        if age_days is not None:
            metadata["age_days"] = age_days
        if days_since_access is not None:
            metadata["days_since_last_access"] = days_since_access

        return metadata

    def identify_expired_data(
        self,
        tier: StorageTier,
        policy: Optional[LifecyclePolicy] = None,
        rule: Optional[CleanupRule] = None,
        custom_filter: Optional[Callable[[str, Dict[str, Any]], bool]] = None,
    ) -> List[str]:
        expired_keys: List[str] = []
        all_keys = tier.list_keys()

        for key in all_keys:
            metadata = self._build_metadata(tier, key)
            should_cleanup = False

            if rule and rule.matches(metadata):
                should_cleanup = True
            elif policy:
                cleanup_action = policy.evaluate(metadata, tier.tier_type)
                if cleanup_action["cleanup_action"]:
                    should_cleanup = True
            elif custom_filter:
                should_cleanup = custom_filter(key, metadata)

            if should_cleanup:
                expired_keys.append(key)

        return expired_keys

    def create_cleanup_task(
        self,
        tier_type: StorageTierType,
        rule: Optional[CleanupRule] = None,
        keys: Optional[List[str]] = None,
        description: Optional[str] = None,
        dry_run: bool = False,
    ) -> CleanupTask:
        if tier_type not in self.storage_tiers:
            raise ValueError(f"Tier {tier_type.value} not found")

        task = CleanupTask(
            tier_type=tier_type,
            rule=rule,
            keys=keys,
            description=description,
            dry_run=dry_run,
        )

        with self._lock:
            self.tasks[task.task_id] = task

        return task

    def execute_task(self, task_id: str) -> CleanupTask:
        with self._lock:
            if task_id not in self.tasks:
                raise ValueError(f"Task {task_id} not found")

            task = self.tasks[task_id]
            if task.status in [CleanupStatus.DELETING, CleanupStatus.COMPLETED, CleanupStatus.SCANNING]:
                return task

            task.started_at = datetime.now()
            task.status = CleanupStatus.SCANNING

        tier = self.storage_tiers[task.tier_type]

        try:
            if not task.keys and task.rule:
                task.status = CleanupStatus.SCANNING
                all_keys = tier.list_keys()
                task.scanned_count = len(all_keys)

                for key in all_keys:
                    if task.is_cancelled():
                        break

                    metadata = self._build_metadata(tier, key)
                    if task.rule.matches(metadata):
                        task.keys.append(key)
                        task.identified_count += 1

            if task.is_cancelled():
                return task

            task.status = CleanupStatus.DELETING
            for key in task.keys:
                if task.is_cancelled():
                    break

                size_bytes = tier.get_size(key)
                success = False
                error_msg = None

                try:
                    if not task.dry_run:
                        if task.rule and task.rule.secure_delete:
                            file_path = tier._get_file_path(key)
                            self._secure_delete(file_path, self.secure_delete_passes)
                        else:
                            success = tier.delete(key)
                    else:
                        success = True

                    if success:
                        task.deleted_count += 1
                        if size_bytes:
                            task.total_size_saved += size_bytes
                except Exception as e:
                    task.failed_count += 1
                    error_msg = str(e)
                    task.error_messages.append(f"Error deleting {key}: {error_msg}")

                audit_log = CleanupAuditLog(
                    task_id=task_id,
                    key=key,
                    tier_type=task.tier_type,
                    action="secure_delete" if (task.rule and task.rule.secure_delete) else "delete",
                    success=success,
                    size_bytes=size_bytes,
                    error_message=error_msg,
                    custom_metadata={"dry_run": task.dry_run},
                )
                task.audit_logs.append(audit_log)
                self._save_audit_log(audit_log)

            if not task.is_cancelled():
                task.status = CleanupStatus.COMPLETED

        except Exception as e:
            task.status = CleanupStatus.FAILED
            task.error_messages.append(str(e))
        finally:
            task.completed_at = datetime.now()

        return task

    def execute_task_async(self, task_id: str) -> str:
        with self._lock:
            if task_id not in self.tasks:
                raise ValueError(f"Task {task_id} not found")

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

    def get_task(self, task_id: str) -> Optional[CleanupTask]:
        with self._lock:
            return self.tasks.get(task_id)

    def list_tasks(
        self,
        status: Optional[CleanupStatus] = None,
        tier_type: Optional[StorageTierType] = None,
    ) -> List[CleanupTask]:
        with self._lock:
            tasks = list(self.tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]
        if tier_type:
            tasks = [t for t in tasks if t.tier_type == tier_type]

        return sorted(tasks, key=lambda t: t.created_at, reverse=True)

    def cancel_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task and task.status in [CleanupStatus.SCANNING, CleanupStatus.DELETING, CleanupStatus.PENDING]:
            task.cancel()
            return True
        return False

    def get_audit_logs(
        self,
        task_id: Optional[str] = None,
        tier_type: Optional[StorageTierType] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        success_only: bool = False,
    ) -> List[CleanupAuditLog]:
        logs: List[CleanupAuditLog] = []

        log_files = list(self.audit_log_path.glob("*.jsonl"))
        if task_id:
            log_files = [f for f in log_files if f.stem == task_id]

        for log_file in log_files:
            try:
                with open(log_file, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            data = json.loads(line)
                            log = CleanupAuditLog.from_dict(data)

                            if tier_type and log.tier_type != tier_type:
                                continue
                            if start_time and log.timestamp < start_time:
                                continue
                            if end_time and log.timestamp > end_time:
                                continue
                            if success_only and not log.success:
                                continue

                            logs.append(log)
                        except Exception:
                            continue
            except Exception:
                continue

        return sorted(logs, key=lambda l: l.timestamp, reverse=True)

    def get_cleanup_statistics(
        self,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        logs = self.get_audit_logs(start_time=start_time, end_time=end_time)

        stats = {
            "total_operations": len(logs),
            "successful_operations": len([l for l in logs if l.success]),
            "failed_operations": len([l for l in logs if not l.success]),
            "total_size_saved_bytes": sum(l.size_bytes or 0 for l in logs if l.success),
            "by_tier": {},
            "by_action": {},
            "by_task": {},
        }

        for log in logs:
            tier_value = log.tier_type.value
            if tier_value not in stats["by_tier"]:
                stats["by_tier"][tier_value] = {"count": 0, "size_saved": 0}
            stats["by_tier"][tier_value]["count"] += 1
            if log.success and log.size_bytes:
                stats["by_tier"][tier_value]["size_saved"] += log.size_bytes

            if log.action not in stats["by_action"]:
                stats["by_action"][log.action] = 0
            stats["by_action"][log.action] += 1

            if log.task_id not in stats["by_task"]:
                stats["by_task"][log.task_id] = {"count": 0, "size_saved": 0}
            stats["by_task"][log.task_id]["count"] += 1
            if log.success and log.size_bytes:
                stats["by_task"][log.task_id]["size_saved"] += log.size_bytes

        return stats

    def execute_auto_cleanup(
        self,
        policy: LifecyclePolicy,
        tier_type: Optional[StorageTierType] = None,
        dry_run: bool = False,
    ) -> List[CleanupTask]:
        tiers_to_check = list(self.storage_tiers.keys())
        if tier_type:
            tiers_to_check = [tier_type]

        tasks = []
        for tier in tiers_to_check:
            storage_tier = self.storage_tiers[tier]
            all_keys = storage_tier.list_keys()

            keys_to_cleanup: List[str] = []
            for key in all_keys:
                metadata = self._build_metadata(storage_tier, key)
                result = policy.evaluate(metadata, tier)
                if result["cleanup_action"]:
                    keys_to_cleanup.append(key)

            if keys_to_cleanup:
                task = self.create_cleanup_task(
                    tier_type=tier,
                    keys=keys_to_cleanup,
                    description=f"Auto-cleanup for {tier.value}",
                    dry_run=dry_run,
                )
                tasks.append(task)
                self.execute_task_async(task.task_id)

        return tasks
