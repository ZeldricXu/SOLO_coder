from __future__ import annotations

import json
from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.lifecycle_manager.lifecycle import LifecycleManager
from streamsql.modules.lifecycle_manager.tiered_storage import StorageTier


class LifecycleService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()
        self.manager = LifecycleManager(
            archive_dir="./data/lifecycle/archives",
            context=ProcessingContext(trace_id="lifecycle_service"),
        )

    def create_policy(
        self,
        table_name: str,
        hot_ttl_days: int = 30,
        cold_ttl_days: int = 90,
        archive_ttl_days: int = 365,
        auto_cleanup: bool = True,
    ) -> dict[str, Any]:
        policy_id = self.manager.add_tier_policy(
            table_name=table_name,
            hot_ttl_seconds=hot_ttl_days * 86400,
            cold_ttl_seconds=cold_ttl_days * 86400,
            archive_ttl_seconds=archive_ttl_days * 86400,
        )

        return {
            "policy_id": policy_id,
            "table_name": table_name,
            "hot_ttl_days": hot_ttl_days,
            "cold_ttl_days": cold_ttl_days,
            "archive_ttl_days": archive_ttl_days,
            "auto_cleanup": auto_cleanup,
        }

    def get_policy(self, table_name: str) -> Optional[dict[str, Any]]:
        policy = self.manager.tiered_storage.policies.get(table_name)
        if not policy:
            return None
        return {
            "table_name": table_name,
            "hot_ttl_seconds": policy.hot_ttl_seconds,
            "cold_ttl_seconds": policy.cold_ttl_seconds,
            "archive_ttl_seconds": policy.archive_ttl_seconds,
        }

    def list_policies(self) -> list[dict[str, Any]]:
        return [
            {"table_name": table, "hot_ttl_seconds": policy.hot_ttl_seconds}
            for table, policy in self.manager.tiered_storage.policies.items()
        ]

    def migrate_data(
        self,
        table_name: str,
        current_time_ms: Optional[int] = None,
    ) -> dict[str, Any]:
        import time

        current_time = current_time_ms or int(time.time() * 1000)
        result = self.manager.run_lifecycle_cycle(current_time)
        return result

    def archive_table(
        self,
        table_name: str,
        data: list[dict[str, Any]],
        format_type: str = "json",
    ) -> dict[str, Any]:
        filepath = self.manager.archive(
            table_name=table_name,
            data=data,
            format_type=format_type,
            metadata={"source": "service_archive"},
        )

        return {
            "table_name": table_name,
            "filepath": filepath,
            "format": format_type,
            "record_count": len(data),
        }

    def restore_archive(self, archive_path: str) -> list[dict[str, Any]]:
        import os

        if not os.path.exists(archive_path):
            return []

        with open(archive_path, "r") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []

    def cleanup_expired(
        self,
        table_name: Optional[str] = None,
        current_time_ms: Optional[int] = None,
    ) -> dict[str, Any]:
        import time

        current_time = current_time_ms or int(time.time() * 1000)
        removed = self.manager.cleanup(current_time, table_name)
        return removed

    def get_storage_summary(self) -> dict[str, Any]:
        stats = self.manager.get_storage_stats()
        return stats

    def get_table_tier(self, table_name: str, timestamp_ms: int) -> str:
        tier = self.manager.tiered_storage.get_tier(table_name, timestamp_ms)
        return tier.value

    def list_tiered_tables(self, tier: Optional[str] = None) -> list[str]:
        tables = list(self.manager.tiered_storage._storage.keys())
        if tier:
            return [t for t in tables if self.manager.tiered_storage.get_tier(t, __import__("time").time() * 1000).value == tier]
        return tables
