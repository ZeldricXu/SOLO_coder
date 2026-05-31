"""Storage backend factory for creating tiered storage instances."""
from __future__ import annotations

from typing import Dict, Optional

from ...domain.contracts.storage import IStorageBackend
from ...domain.errors.storage import InvalidStorageTierError
from ...domain.models.common import StorageTier
from ..config.settings import StorageConfig
from .archive_storage import ArchiveStorageBackend
from .cold_storage import ColdStorageBackend
from .hot_storage import HotStorageBackend


class StorageBackendFactory:
    def __init__(self, config: StorageConfig) -> None:
        self._config = config
        self._backends: Dict[StorageTier, IStorageBackend] = {}
        self._initialize_backends()

    def _initialize_backends(self) -> None:
        self._backends[StorageTier.HOT] = HotStorageBackend(
            base_path=self._config.hot.path,
            max_size_gb=self._config.hot.max_size_gb,
        )
        self._backends[StorageTier.COLD] = ColdStorageBackend(
            base_path=self._config.cold.path,
            max_size_gb=self._config.cold.max_size_gb,
        )
        self._backends[StorageTier.ARCHIVE] = ArchiveStorageBackend(
            base_path=self._config.archive.path,
            max_size_gb=self._config.archive.max_size_gb,
        )

    def get_backend(self, tier: StorageTier) -> IStorageBackend:
        if tier not in self._backends:
            raise InvalidStorageTierError(
                tier=tier.value,
                valid_tiers=[t.value for t in StorageTier],
            )
        return self._backends[tier]

    def get_all_backends(self) -> Dict[StorageTier, IStorageBackend]:
        return dict(self._backends)

    def get_backend_by_name(self, tier_name: str) -> IStorageBackend:
        try:
            tier = StorageTier(tier_name.lower())
            return self.get_backend(tier)
        except ValueError:
            raise InvalidStorageTierError(
                tier=tier_name,
                valid_tiers=[t.value for t in StorageTier],
            )
