from __future__ import annotations

import shutil
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Optional

from streamsql.core.models import generate_id


class CleanupPolicy:
    def __init__(
        self,
        max_age_days: int = 365,
        max_size_bytes: int = 1024 * 1024 * 1024 * 100,
        max_files: int = 10000,
    ):
        self.max_age = timedelta(days=max_age_days)
        self.max_size_bytes = max_size_bytes
        self.max_files = max_files


class CleanupManager:
    def __init__(
        self,
        policy: Optional[CleanupPolicy] = None,
        dry_run: bool = False,
    ):
        self.policy = policy or CleanupPolicy()
        self.dry_run = dry_run
        self._cleanup_history: list[dict[str, Any]] = []

    def cleanup_directory(
        self,
        directory: str,
        file_pattern: str = "*",
        recursive: bool = True,
    ) -> dict[str, Any]:
        path = Path(directory)
        if not path.exists():
            return {"deleted": 0, "freed_bytes": 0, "skipped": 0}

        files = list(path.rglob(file_pattern) if recursive else path.glob(file_pattern))
        files = [f for f in files if f.is_file()]

        deleted_count = 0
        freed_bytes = 0
        skipped_count = 0

        for file_path in files:
            if self._should_delete(file_path):
                if not self.dry_run:
                    try:
                        file_size = file_path.stat().st_size
                        file_path.unlink()
                        deleted_count += 1
                        freed_bytes += file_size
                    except Exception:
                        skipped_count += 1
                else:
                    deleted_count += 1
                    try:
                        freed_bytes += file_path.stat().st_size
                    except Exception:
                        pass
            else:
                skipped_count += 1

        result = {
            "deleted": deleted_count,
            "freed_bytes": freed_bytes,
            "skipped": skipped_count,
            "directory": directory,
            "timestamp": datetime.utcnow().isoformat(),
            "dry_run": self.dry_run,
        }

        self._cleanup_history.append(result)
        return result

    def cleanup_expired(
        self,
        items: list[dict[str, Any]],
        date_key: str = "created_at",
    ) -> list[str]:
        now = datetime.utcnow()
        deleted_ids: list[str] = []

        for item in items:
            item_date = item.get(date_key)
            if isinstance(item_date, str):
                item_date = datetime.fromisoformat(item_date)

            if item_date and now - item_date > self.policy.max_age:
                deleted_ids.append(str(item.get("id", generate_id("del"))))

        return deleted_ids

    def enforce_size_limit(
        self,
        directory: str,
        get_size_func: Optional[Callable[[], int]] = None,
    ) -> dict[str, Any]:
        if get_size_func:
            current_size = get_size_func()
        else:
            current_size = self._get_directory_size(directory)

        if current_size <= self.policy.max_size_bytes:
            return {"action": "none", "current_size": current_size, "limit": self.policy.max_size_bytes}

        overage = current_size - self.policy.max_size_bytes
        path = Path(directory)
        files = sorted(
            path.rglob("*"),
            key=lambda f: f.stat().st_mtime
        )
        files = [f for f in files if f.is_file()]

        freed = 0
        deleted = 0

        for file_path in files:
            if freed >= overage:
                break
            try:
                size = file_path.stat().st_size
                if not self.dry_run:
                    file_path.unlink()
                freed += size
                deleted += 1
            except Exception:
                continue

        return {
            "action": "enforced",
            "current_size": current_size,
            "limit": self.policy.max_size_bytes,
            "overage": overage,
            "freed_bytes": freed,
            "deleted_files": deleted,
        }

    def cleanup_empty_directories(self, directory: str) -> int:
        path = Path(directory)
        removed_count = 0

        for dir_path in sorted(path.rglob("*"), key=lambda p: len(str(p)), reverse=True):
            if dir_path.is_dir() and not any(dir_path.iterdir()):
                try:
                    if not self.dry_run:
                        dir_path.rmdir()
                    removed_count += 1
                except Exception:
                    pass

        return removed_count

    def _should_delete(self, file_path: Path) -> bool:
        try:
            stat = file_path.stat()
            mtime = datetime.fromtimestamp(stat.st_mtime)
            age = datetime.utcnow() - mtime

            if age > self.policy.max_age:
                return True

            return False
        except Exception:
            return False

    def _get_directory_size(self, directory: str) -> int:
        total = 0
        path = Path(directory)
        for f in path.rglob("*"):
            if f.is_file():
                try:
                    total += f.stat().st_size
                except Exception:
                    pass
        return total

    def get_history(self, limit: int = 100) -> list[dict[str, Any]]:
        return self._cleanup_history[-limit:]

    def clear_history(self) -> None:
        self._cleanup_history.clear()

    def plan_cleanup(
        self,
        directory: str,
        file_pattern: str = "*",
        recursive: bool = True,
    ) -> dict[str, Any]:
        original_dry_run = self.dry_run
        self.dry_run = True
        try:
            return self.cleanup_directory(directory, file_pattern, recursive)
        finally:
            self.dry_run = original_dry_run

    def schedule_cleanup(
        self,
        directory: str,
        interval_hours: int = 24,
        file_pattern: str = "*",
        recursive: bool = True,
    ) -> str:
        from apscheduler.schedulers.background import BackgroundScheduler

        job_id = generate_id("job")

        try:
            scheduler = BackgroundScheduler()
            scheduler.add_job(
                self.cleanup_directory,
                "interval",
                hours=interval_hours,
                args=[directory, file_pattern, recursive],
                id=job_id,
            )
            scheduler.start()
        except ImportError:
            pass

        return job_id
