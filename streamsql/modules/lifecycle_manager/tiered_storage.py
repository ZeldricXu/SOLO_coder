from __future__ import annotations

from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Optional

from streamsql.core.config import ConfigManager


class StorageTier(str, Enum):
    HOT = "hot"
    COLD = "cold"
    ARCHIVE = "archive"
    PURGE = "purge"


class TierPolicy:
    def __init__(
        self,
        hot_threshold_days: int = 7,
        cold_threshold_days: int = 30,
        archive_threshold_days: int = 90,
    ):
        self.hot_threshold = timedelta(days=hot_threshold_days)
        self.cold_threshold = timedelta(days=cold_threshold_days)
        self.archive_threshold = timedelta(days=archive_threshold_days)

    def determine_tier(self, last_accessed: datetime, created_at: datetime) -> StorageTier:
        now = datetime.utcnow()
        age = now - created_at
        time_since_access = now - last_accessed

        if time_since_access <= self.hot_threshold and age <= self.hot_threshold:
            return StorageTier.HOT
        elif time_since_access <= self.cold_threshold and age <= self.cold_threshold:
            return StorageTier.COLD
        elif age <= self.archive_threshold:
            return StorageTier.ARCHIVE
        else:
            return StorageTier.PURGE

    def get_tier_for_age(self, age_days: int) -> StorageTier:
        if age_days <= self.hot_threshold.days:
            return StorageTier.HOT
        elif age_days <= self.cold_threshold.days:
            return StorageTier.COLD
        elif age_days <= self.archive_threshold.days:
            return StorageTier.ARCHIVE
        else:
            return StorageTier.PURGE


class TieredStorage:
    def __init__(self, policy: Optional[TierPolicy] = None):
        config = ConfigManager.get()
        self.policy = policy or TierPolicy(
            hot_threshold_days=config.modules.lifecycle_manager.hot_threshold_days,
            cold_threshold_days=config.modules.lifecycle_manager.cold_threshold_days,
            archive_threshold_days=config.modules.lifecycle_manager.archive_threshold_days,
        )

        self._storage: dict[StorageTier, dict[str, dict[str, Any]]] = {
            StorageTier.HOT: {},
            StorageTier.COLD: {},
            StorageTier.ARCHIVE: {},
        }

    def store(self, data_id: str, data: dict[str, Any],
              created_at: Optional[datetime] = None) -> StorageTier:
        created = created_at or datetime.utcnow()
        tier = self.policy.determine_tier(created, created)

        self._storage[tier][data_id] = {
            "data": data,
            "created_at": created,
            "last_accessed": created,
            "current_tier": tier,
            "access_count": 0,
        }

        return tier

    def retrieve(self, data_id: str) -> Optional[dict[str, Any]]:
        for tier in [StorageTier.HOT, StorageTier.COLD, StorageTier.ARCHIVE]:
            if data_id in self._storage[tier]:
                item = self._storage[tier][data_id]
                item["last_accessed"] = datetime.utcnow()
                item["access_count"] += 1

                if tier != StorageTier.HOT:
                    self._promote_to_hot(data_id, item)

                return item["data"]
        return None

    def _promote_to_hot(self, data_id: str, item: dict[str, Any]) -> None:
        old_tier = item["current_tier"]
        if old_tier in self._storage and data_id in self._storage[old_tier]:
            del self._storage[old_tier][data_id]

        item["current_tier"] = StorageTier.HOT
        self._storage[StorageTier.HOT][data_id] = item

    def migrate(self, data_id: str, target_tier: StorageTier) -> bool:
        source_tier: Optional[StorageTier] = None
        item: Optional[dict[str, Any]] = None

        for tier in [StorageTier.HOT, StorageTier.COLD, StorageTier.ARCHIVE]:
            if data_id in self._storage[tier]:
                source_tier = tier
                item = self._storage[tier][data_id]
                break

        if source_tier is None or item is None:
            return False

        if source_tier == target_tier:
            return True

        del self._storage[source_tier][data_id]
        item["current_tier"] = target_tier
        self._storage[target_tier][data_id] = item

        return True

    def scan_and_migrate(self) -> list[tuple[str, StorageTier, StorageTier]]:
        migrations: list[tuple[str, StorageTier, StorageTier]] = []
        now = datetime.utcnow()

        for tier in list(StorageTier):
            if tier == StorageTier.PURGE:
                continue

            for data_id, item in list(self._storage[tier].items()):
                target_tier = self.policy.determine_tier(
                    item["last_accessed"], item["created_at"]
                )

                if target_tier != tier and target_tier != StorageTier.PURGE:
                    self.migrate(data_id, target_tier)
                    migrations.append((data_id, tier, target_tier))
                elif target_tier == StorageTier.PURGE:
                    del self._storage[tier][data_id]
                    migrations.append((data_id, tier, StorageTier.PURGE))

        return migrations

    def get_tier_stats(self) -> dict[StorageTier, dict[str, Any]]:
        stats: dict[StorageTier, dict[str, Any]] = {}
        for tier in StorageTier:
            if tier == StorageTier.PURGE:
                continue
            items = self._storage[tier]
            total_size = sum(len(str(item.get("data", {}))) for item in items.values())
            stats[tier] = {
                "count": len(items),
                "total_size_bytes": total_size,
                "avg_age_days": self._avg_age(items),
            }
        return stats

    def _avg_age(self, items: dict[str, dict[str, Any]]) -> float:
        if not items:
            return 0.0
        now = datetime.utcnow()
        ages = [(now - item["created_at"]).total_seconds() for item in items.values()]
        return sum(ages) / len(ages) / 86400

    def list_tier(self, tier: StorageTier) -> list[str]:
        return list(self._storage.get(tier, {}).keys())

    def get_item_info(self, data_id: str) -> Optional[dict[str, Any]]:
        for tier in StorageTier:
            if tier == StorageTier.PURGE:
                continue
            if data_id in self._storage[tier]:
                item = self._storage[tier][data_id]
                return {
                    "data_id": data_id,
                    "tier": tier.value,
                    "created_at": item["created_at"].isoformat(),
                    "last_accessed": item["last_accessed"].isoformat(),
                    "access_count": item["access_count"],
                }
        return None
