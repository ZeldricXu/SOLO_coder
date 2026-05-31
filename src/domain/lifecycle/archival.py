import logging
import gc
import weakref
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional

from src.infrastructure.storage.cold_storage import ColdStorage
from src.infrastructure.storage.archive_storage import ArchiveStorage

logger = logging.getLogger(__name__)


@dataclass
class ArchiveTask:
    task_id: str
    database_name: str
    table_name: str
    cutoff_date: datetime
    row_count: int = 0
    status: str = "pending"
    archive_path: Optional[str] = None
    error_message: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class DataArchiver:
    def __init__(self, cold_storage: ColdStorage, archive_storage: ArchiveStorage):
        self._cold_storage = cold_storage
        self._archive_storage = archive_storage
        self._pre_archive_hooks: List[weakref.ReferenceType] = []
        self._post_archive_hooks: List[weakref.ReferenceType] = []
        self._closed = False

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._pre_archive_hooks.clear()
        self._post_archive_hooks.clear()
        gc.collect()

    def add_pre_archive_hook(self, hook: Callable) -> None:
        if self._closed:
            raise RuntimeError("DataArchiver has been closed")
        try:
            if hasattr(hook, '__self__'):
                self._pre_archive_hooks.append(weakref.WeakMethod(hook))
            else:
                self._pre_archive_hooks.append(weakref.ref(hook))
        except TypeError:
            self._pre_archive_hooks.append(weakref.ref(hook))

    def add_post_archive_hook(self, hook: Callable) -> None:
        if self._closed:
            raise RuntimeError("DataArchiver has been closed")
        try:
            if hasattr(hook, '__self__'):
                self._post_archive_hooks.append(weakref.WeakMethod(hook))
            else:
                self._post_archive_hooks.append(weakref.ref(hook))
        except TypeError:
            self._post_archive_hooks.append(weakref.ref(hook))

    def _execute_hooks(self, hooks: List[weakref.ReferenceType], task: ArchiveTask) -> None:
        expired_indices = []
        for idx, hook_ref in enumerate(hooks):
            hook = hook_ref()
            if hook is None:
                expired_indices.append(idx)
                continue
            try:
                hook(task)
            except Exception as e:
                logger.warning(f"Archive hook failed: {e}")

        for idx in reversed(expired_indices):
            del hooks[idx]

    def archive_table_data(
        self,
        database_name: str,
        table_name: str,
        records: List[Dict[str, Any]],
        cutoff_date: Optional[datetime] = None,
        target_tier: str = "cold",
    ) -> ArchiveTask:
        if self._closed:
            raise RuntimeError("DataArchiver has been closed")

        import uuid
        task = ArchiveTask(
            task_id=str(uuid.uuid4()),
            database_name=database_name,
            table_name=table_name,
            cutoff_date=cutoff_date or datetime.utcnow(),
            row_count=len(records),
            started_at=datetime.utcnow(),
        )

        self._execute_hooks(self._pre_archive_hooks, task)

        df = None
        try:
            import pandas as pd
            df = pd.DataFrame(records)
            date_str = task.cutoff_date.strftime("%Y%m%d")

            if target_tier == "archive":
                path = self._archive_storage.archive_data(database_name, table_name, df, date_str)
            else:
                path = self._cold_storage.write_parquet(database_name, table_name, df, date_str)

            task.archive_path = path
            task.status = "completed"
            task.completed_at = datetime.utcnow()
            logger.info(f"Archived {len(records)} rows from {database_name}.{table_name} to {path}")

        except Exception as e:
            task.status = "failed"
            task.error_message = str(e)
            task.completed_at = datetime.utcnow()
            logger.error(f"Archive task {task.task_id} failed: {e}")

        finally:
            if df is not None:
                del df
            gc.collect()

        self._execute_hooks(self._post_archive_hooks, task)

        return task

    def archive_from_hot(
        self,
        database_name: str,
        table_name: str,
        records: List[Dict[str, Any]],
        cutoff_date: Optional[datetime] = None,
    ) -> ArchiveTask:
        return self.archive_table_data(database_name, table_name, records, cutoff_date, "cold")

    def migrate_cold_to_archive(
        self,
        database_name: str,
        table_name: str,
        date_str: Optional[str] = None,
    ) -> ArchiveTask:
        if self._closed:
            raise RuntimeError("DataArchiver has been closed")

        import uuid
        task = ArchiveTask(
            task_id=str(uuid.uuid4()),
            database_name=database_name,
            table_name=table_name,
            cutoff_date=datetime.utcnow(),
            started_at=datetime.utcnow(),
        )

        df = None
        try:
            df = self._cold_storage.read_parquet(database_name, table_name, date_str)
            if df.empty:
                task.status = "no_data"
                task.completed_at = datetime.utcnow()
                return task

            task.row_count = len(df)
            path = self._archive_storage.archive_data(database_name, table_name, df, date_str)
            task.archive_path = path
            task.status = "completed"
            task.completed_at = datetime.utcnow()

            if date_str:
                self._cold_storage.delete_partition(database_name, table_name, date_str)

            logger.info(f"Migrated {len(df)} rows from cold to archive for {database_name}.{table_name}")

        except Exception as e:
            task.status = "failed"
            task.error_message = str(e)
            task.completed_at = datetime.utcnow()
            logger.error(f"Cold-to-archive migration failed: {e}")

        finally:
            if df is not None:
                del df
            gc.collect()

        return task

    def restore_from_archive(
        self,
        database_name: str,
        table_name: str,
        date_str: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        if self._closed:
            raise RuntimeError("DataArchiver has been closed")

        df = None
        try:
            df = self._archive_storage.read_archive(database_name, table_name, date_str)
            if df.empty:
                return []
            return df.to_dict(orient="records")
        finally:
            if df is not None:
                del df
            gc.collect()

    def list_archives(self, database_name: str, table_name: str) -> List[Dict[str, Any]]:
        return self._archive_storage.list_archives(database_name, table_name)
