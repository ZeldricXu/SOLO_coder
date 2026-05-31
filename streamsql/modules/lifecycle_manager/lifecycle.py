from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from streamsql.core.context import ProcessingContext
from streamsql.core.events import Event, EventBus, EventType
from streamsql.core.models import generate_id

from streamsql.modules.lifecycle_manager.tiered_storage import (
    StorageTier,
    TieredStorage,
    TierPolicy,
)
from streamsql.modules.lifecycle_manager.archive_manager import ArchiveManager
from streamsql.modules.lifecycle_manager.cleanup import CleanupManager, CleanupPolicy


class LifecycleManager:
    def __init__(
        self,
        tier_policy: Optional[TierPolicy] = None,
        cleanup_policy: Optional[CleanupPolicy] = None,
        archive_dir: str = "./archives",
        context: Optional[ProcessingContext] = None,
    ):
        self.context = context or ProcessingContext(trace_id=generate_id("trace"))
        self.event_bus = EventBus()

        self.tiered_storage = TieredStorage(tier_policy)
        self.archive_manager = ArchiveManager(archive_dir)
        self.cleanup_manager = CleanupManager(cleanup_policy)

        self._migrations: list[dict[str, Any]] = []
        self._archives: list[str] = []

    async def run_lifecycle_cycle(self) -> dict[str, Any]:
        self.event_bus.emit(
            Event(EventType.TASK_STARTED, {"module": "lifecycle_manager"})
        )

        results: dict[str, Any] = {}

        try:
            migration_results = self.tiered_storage.scan_and_migrate()
            results["migrations"] = [
                {
                    "data_id": data_id,
                    "from_tier": from_tier.value,
                    "to_tier": to_tier.value,
                }
                for data_id, from_tier, to_tier in migration_results
            ]
            results["migration_count"] = len(migration_results)

            for data_id, from_tier, to_tier in migration_results:
                if to_tier == StorageTier.ARCHIVE:
                    data = self.tiered_storage.retrieve(data_id)
                    if data:
                        archive_id = self.archive_manager.archive(
                            {"data_id": data_id, "content": data},
                            name=f"tiered_{data_id}",
                            metadata={"source_tier": from_tier.value},
                        )
                        self._archives.append(archive_id)

            results["archive_count"] = len(self._archives)

            cleanup_result = self.cleanup_manager.cleanup_directory("./data")
            results["cleanup"] = cleanup_result

            tier_stats = self.tiered_storage.get_tier_stats()
            results["tier_stats"] = {
                tier.value: stats for tier, stats in tier_stats.items()
            }

            self.event_bus.emit(
                Event(
                    EventType.LIFECYCLE_MIGRATION,
                    {"migrations": len(migration_results), "archives": len(self._archives)},
                )
            )

            self.event_bus.emit(
                Event(EventType.TASK_COMPLETED, {"module": "lifecycle_manager"})
            )

            return results

        except Exception as e:
            self.event_bus.emit(
                Event(EventType.TASK_FAILED, {"module": "lifecycle_manager", "error": str(e)})
            )
            raise

    def store_data(self, data_id: str, data: dict[str, Any]) -> StorageTier:
        tier = self.tiered_storage.store(data_id, data)
        self.context.add_metric(f"stored_{tier.value}", 1)
        return tier

    def retrieve_data(self, data_id: str) -> Optional[dict[str, Any]]:
        return self.tiered_storage.retrieve(data_id)

    def migrate_data(self, data_id: str, target_tier: StorageTier) -> bool:
        success = self.tiered_storage.migrate(data_id, target_tier)
        if success:
            self.event_bus.emit(
                Event(
                    EventType.LIFECYCLE_MIGRATION,
                    {"data_id": data_id, "target_tier": target_tier.value},
                )
            )
        return success

    def archive_data(self, data_id: str, data: dict[str, Any], metadata: Optional[dict[str, Any]] = None) -> str:
        archive_id = self.archive_manager.archive(
            data, name=f"data_{data_id}", metadata=metadata
        )
        self._archives.append(archive_id)
        return archive_id

    def restore_archive(self, archive_id: str) -> Optional[dict[str, Any]]:
        return self.archive_manager.restore(archive_id)

    def cleanup_expired_data(self, items: list[dict[str, Any]]) -> list[str]:
        return self.cleanup_manager.cleanup_expired(items)

    def get_status(self) -> dict[str, Any]:
        tier_stats = self.tiered_storage.get_tier_stats()
        return {
            "tier_storage": {
                tier.value: stats for tier, stats in tier_stats.items()
            },
            "archive": {
                "total_archives": len(self.archive_manager._index),
                "total_size_bytes": self.archive_manager.get_total_size(),
            },
            "cleanup": {
                "history_count": len(self.cleanup_manager._cleanup_history),
                "last_cleanup": self.cleanup_manager._cleanup_history[-1]
                if self.cleanup_manager._cleanup_history
                else None,
            },
            "pending_migrations": len(self._migrations),
            "pending_archives": len(self._archives),
        }

    def list_archives(self) -> list[dict[str, Any]]:
        return self.archive_manager.list_archives()

    def get_data_info(self, data_id: str) -> Optional[dict[str, Any]]:
        return self.tiered_storage.get_item_info(data_id)
