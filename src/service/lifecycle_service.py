import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from src.domain.lifecycle.tiering import DataTieringManager, DataTier, TieringPolicy, TieringAction
from src.domain.lifecycle.archival import DataArchiver, ArchiveTask
from src.domain.lifecycle.cleanup import DataCleanupManager, CleanupPolicy, CleanupTask
from src.infrastructure.config.settings import LifecycleConfig
from src.infrastructure.storage.cold_storage import ColdStorage
from src.infrastructure.storage.archive_storage import ArchiveStorage

logger = logging.getLogger(__name__)


class LifecycleService:
    def __init__(
        self,
        config: LifecycleConfig,
        cold_storage: ColdStorage,
        archive_storage: ArchiveStorage,
    ):
        self._config = config
        self._tiering_manager = DataTieringManager(config)
        self._archiver = DataArchiver(cold_storage, archive_storage)
        self._cleanup_manager = DataCleanupManager(config, archive_storage)

        self._tiering_manager.register_callback(
            "hot_to_cold",
            lambda action: logger.info(f"Migrating {action.database_name}.{action.table_name} from hot to cold"),
        )
        self._tiering_manager.register_callback(
            "cold_to_archive",
            lambda action: logger.info(f"Migrating {action.database_name}.{action.table_name} from cold to archive"),
        )

    def evaluate_tiering(self, database_name: str, table_name: str, table_stats: Dict[str, Any]) -> List[Dict[str, Any]]:
        actions = self._tiering_manager.evaluate(database_name, table_name, table_stats)
        return [
            {
                "database_name": a.database_name,
                "table_name": a.table_name,
                "source_tier": a.source_tier.value,
                "target_tier": a.target_tier.value,
                "row_count": a.row_count,
                "status": a.status,
                "error_message": a.error_message,
            }
            for a in actions
        ]

    def execute_tiering(self, database_name: str, table_name: str, table_stats: Dict[str, Any]) -> List[Dict[str, Any]]:
        actions = self._tiering_manager.evaluate(database_name, table_name, table_stats)
        results = []
        for action in actions:
            executed = self._tiering_manager.execute_tiering(action)
            results.append({
                "database_name": executed.database_name,
                "table_name": executed.table_name,
                "source_tier": executed.source_tier.value,
                "target_tier": executed.target_tier.value,
                "status": executed.status,
                "error_message": executed.error_message,
            })
        return results

    def archive_data(
        self,
        database_name: str,
        table_name: str,
        records: List[Dict[str, Any]],
        cutoff_date: Optional[datetime] = None,
        target_tier: str = "cold",
    ) -> Dict[str, Any]:
        task = self._archiver.archive_table_data(database_name, table_name, records, cutoff_date, target_tier)
        return {
            "task_id": task.task_id,
            "status": task.status,
            "row_count": task.row_count,
            "archive_path": task.archive_path,
            "error_message": task.error_message,
        }

    def migrate_cold_to_archive(self, database_name: str, table_name: str, date_str: Optional[str] = None) -> Dict[str, Any]:
        task = self._archiver.migrate_cold_to_archive(database_name, table_name, date_str)
        return {
            "task_id": task.task_id,
            "status": task.status,
            "row_count": task.row_count,
            "archive_path": task.archive_path,
            "error_message": task.error_message,
        }

    def cleanup_expired(self, retention_days: Optional[int] = None) -> Dict[str, Any]:
        deleted = self._cleanup_manager.cleanup_expired_archives(retention_days)
        return {
            "deleted_count": len(deleted),
            "deleted_paths": deleted,
        }

    def evaluate_cleanup(self, database_name: str, table_name: str, table_stats: Dict[str, Any]) -> List[Dict[str, Any]]:
        tasks = self._cleanup_manager.evaluate_cleanup(database_name, table_name, table_stats)
        return [
            {
                "task_id": t.task_id,
                "policy_name": t.policy_name,
                "retention_days": t.retention_days,
                "status": t.status,
            }
            for t in tasks
        ]

    def get_tiering_policies(self) -> List[Dict[str, Any]]:
        return self._tiering_manager.get_policies()

    def add_tiering_policy(self, source_tier: str, target_tier: str, age_days: int, priority: int = 0) -> None:
        policy = TieringPolicy(
            source_tier=DataTier[source_tier.upper()],
            target_tier=DataTier[target_tier.upper()],
            age_threshold_days=age_days,
            priority=priority,
        )
        self._tiering_manager.add_policy(policy)

    def get_cleanup_policies(self) -> List[Dict[str, Any]]:
        return self._cleanup_manager.get_policies()

    def schedule_periodic_cleanup(self) -> None:
        self._cleanup_manager.schedule_periodic_cleanup()
