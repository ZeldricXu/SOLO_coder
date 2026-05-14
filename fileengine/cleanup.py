import time
import threading
from pathlib import Path
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, List

from .config import settings, cleanup_strategies
from .storage import storage
from .metadata import metadata
from .logger import logger


class CleanupPolicy:
    SCHEDULED = "scheduled"
    CAPACITY = "capacity"


class CleanupManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._setup()
        return cls._instance

    def _setup(self):
        self.enabled = settings.enable_scheduled_cleanup
        self.check_interval = settings.cleanup_check_interval_seconds
        self.last_check_time = 0
        self._scheduler_thread: Optional[threading.Thread] = None
        self._is_running = False
        self._lock = threading.Lock()

    def _get_file_type_category(self, file_ext: str) -> str:
        ext = file_ext.lower()
        if ext in ["jpg", "jpeg", "png", "webp", "gif", "tiff", "bmp", "svg"]:
            return "image"
        elif ext in ["mp4", "webm", "avi", "mkv", "mov", "wmv", "flv"]:
            return "video"
        elif ext == "pdf":
            return "pdf"
        elif ext in ["zip", "rar", "tar", "gz", "bz2", "7z"]:
            return "archive"
        else:
            return "default"

    def _get_file_expire_days(self, file_ext: str) -> int:
        category = self._get_file_type_category(file_ext)
        return cleanup_strategies.get_expire_days(category)

    def _should_cleanup_by_trigger(self, file_type: str, current_storage_gb: float) -> bool:
        strategy = cleanup_strategies.get_strategy(file_type)
        trigger_condition = strategy.get("trigger_condition", "scheduled")

        if trigger_condition == CleanupPolicy.SCHEDULED:
            return True
        elif trigger_condition == CleanupPolicy.CAPACITY:
            max_storage_gb = strategy.get("max_storage_gb", 100)
            cleanup_percentage = strategy.get("cleanup_percentage", 80) / 100
            return current_storage_gb > (max_storage_gb * cleanup_percentage)

        return False

    def _get_current_storage_usage_gb(self) -> float:
        total_size = 0
        try:
            for dir_path in [settings.upload_dir, settings.result_dir]:
                if dir_path.exists():
                    for path in dir_path.rglob("*"):
                        if path.is_file():
                            total_size += path.stat().st_size
        except Exception as e:
            logger.warning(f"Failed to calculate storage usage: {e}")

        return total_size / (1024 * 1024 * 1024)

    def _cleanup_by_file_type(self, file_type: str) -> int:
        deleted_count = 0
        now = datetime.utcnow()
        expire_days = cleanup_strategies.get_expire_days(file_type)
        expire_cutoff = now - timedelta(days=expire_days)

        for file_info in metadata.list_files():
            try:
                file_category = self._get_file_type_category(file_info.file_type)

                if file_type != "default" and file_category != file_type:
                    continue

                upload_time_str = file_info.upload_time.replace("Z", "")
                upload_time = datetime.fromisoformat(upload_time_str)

                if upload_time < expire_cutoff:
                    storage.delete_file(file_info.file_id)
                    deleted_count += 1
            except Exception as e:
                logger.error(f"Error checking file {file_info.file_id}: {e}")

        return deleted_count

    def run_cleanup(self) -> Dict[str, Any]:
        with self._lock:
            logger.info("Starting cleanup process...")
            start_time = time.time()

            current_storage_gb = self._get_current_storage_usage_gb()
            total_deleted = 0
            cleanup_details = {}

            for file_type in cleanup_strategies.list_strategies():
                if self._should_cleanup_by_trigger(file_type, current_storage_gb):
                    deleted = self._cleanup_by_file_type(file_type)
                    total_deleted += deleted
                    cleanup_details[file_type] = {
                        "deleted": deleted,
                        "trigger": cleanup_strategies.get_trigger_condition(file_type),
                    }

            elapsed = time.time() - start_time
            self.last_check_time = time.time()

            result = {
                "success": True,
                "total_deleted": total_deleted,
                "elapsed_seconds": round(elapsed, 2),
                "current_storage_gb": round(current_storage_gb, 2),
                "details": cleanup_details,
            }

            logger.info(
                f"Cleanup completed: {total_deleted} files deleted in {elapsed:.2f}s"
            )
            return result

    def run_cleanup_for_type(self, file_type: str) -> Dict[str, Any]:
        with self._lock:
            logger.info(f"Starting cleanup for file type: {file_type}")
            start_time = time.time()

            if file_type not in cleanup_strategies.list_strategies():
                return {
                    "success": False,
                    "message": f"Unknown file type: {file_type}",
                }

            deleted = self._cleanup_by_file_type(file_type)
            elapsed = time.time() - start_time

            result = {
                "success": True,
                "file_type": file_type,
                "deleted": deleted,
                "elapsed_seconds": round(elapsed, 2),
                "strategy": cleanup_strategies.get_strategy(file_type),
            }

            logger.info(
                f"Cleanup for {file_type} completed: {deleted} files deleted in {elapsed:.2f}s"
            )
            return result

    def get_cleanup_status(self) -> Dict[str, Any]:
        with self._lock:
            current_storage_gb = self._get_current_storage_usage_gb()

            strategies_info = []
            for file_type in cleanup_strategies.list_strategies():
                strategy = cleanup_strategies.get_strategy(file_type)
                strategies_info.append({
                    "file_type": file_type,
                    "expire_days": cleanup_strategies.get_expire_days(file_type),
                    "trigger_condition": cleanup_strategies.get_trigger_condition(file_type),
                    "schedule_interval_hours": cleanup_strategies.get_schedule_interval(file_type),
                    "max_storage_gb": cleanup_strategies.get_max_storage_gb(file_type),
                    "cleanup_percentage": cleanup_strategies.get_cleanup_percentage(file_type),
                    "should_cleanup_now": self._should_cleanup_by_trigger(file_type, current_storage_gb),
                })

            return {
                "scheduler_enabled": self.enabled,
                "is_running": self._is_running,
                "check_interval_seconds": self.check_interval,
                "last_check_time": datetime.fromtimestamp(self.last_check_time).isoformat() + "Z" if self.last_check_time else None,
                "current_storage_gb": round(current_storage_gb, 2),
                "strategies": strategies_info,
            }

    def _scheduler_loop(self):
        logger.info("Cleanup scheduler started")

        while self._is_running:
            try:
                time.sleep(self.check_interval)

                if not self.enabled:
                    continue

                logger.info("Running scheduled cleanup...")
                self.run_cleanup()

            except Exception as e:
                logger.error(f"Cleanup scheduler error: {e}")
                time.sleep(60)

        logger.info("Cleanup scheduler stopped")

    def start_scheduler(self):
        if not self.enabled:
            logger.info("Cleanup scheduler is disabled by configuration")
            return

        if self._is_running:
            logger.warning("Cleanup scheduler already running")
            return

        self._is_running = True
        self._scheduler_thread = threading.Thread(
            target=self._scheduler_loop,
            daemon=True,
        )
        self._scheduler_thread.start()
        logger.info("Cleanup scheduler thread started")

    def stop_scheduler(self):
        if not self._is_running:
            return

        self._is_running = False
        if self._scheduler_thread:
            self._scheduler_thread.join(timeout=10)
            self._scheduler_thread = None

        logger.info("Cleanup scheduler stopped")


cleanup_manager = CleanupManager()
