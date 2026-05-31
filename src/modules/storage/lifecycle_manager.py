"""Lifecycle manager for automated file tiering and cleanup."""
from __future__ import annotations

from datetime import datetime
from typing import Dict, List, Optional, Tuple
from uuid import UUID

from ...domain.contracts.storage import ILifecycleManager, IStorageBackend
from ...domain.models.common import (
    FileMetadata,
    FileStatus,
    LifecycleAction,
    LifecyclePolicy,
    ProcessingResult,
    ProcessingStatus,
    StorageTier,
)
from ...infrastructure.config.settings import Settings
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.storage.storage_factory import StorageBackendFactory
from .storage_service import StorageService


class LifecycleManager(ILifecycleManager):
    def __init__(
        self,
        settings: Settings,
        storage_factory: StorageBackendFactory,
        storage_service: StorageService,
    ) -> None:
        self._settings = settings
        self._storage_factory = storage_factory
        self._storage_service = storage_service
        self._logger = LogManager().get_logger(__name__)

    async def apply_lifecycle_policy(
        self,
        file_metadata: FileMetadata,
        policy: LifecyclePolicy,
    ) -> FileMetadata:
        if not policy.enabled:
            return file_metadata

        if file_metadata.status != FileStatus.ACTIVE:
            return file_metadata

        if file_metadata.should_move_to_cold(policy):
            self._logger.info(
                f"Moving file to cold storage: {file_metadata.file_name}",
                file_id=str(file_metadata.id),
            )
            return await self.move_file_between_tiers(file_metadata.id, StorageTier.COLD)

        if file_metadata.should_move_to_archive(policy):
            self._logger.info(
                f"Moving file to archive: {file_metadata.file_name}",
                file_id=str(file_metadata.id),
            )
            return await self.move_file_between_tiers(file_metadata.id, StorageTier.ARCHIVE)

        if file_metadata.should_delete(policy):
            self._logger.info(
                f"Deleting expired file: {file_metadata.file_name}",
                file_id=str(file_metadata.id),
            )
            await self._storage_service.delete_file(file_metadata.id)
            file_metadata.status = FileStatus.DELETED
            return file_metadata

        return file_metadata

    async def run_lifecycle_check(self) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        self._logger.info("Starting lifecycle check")

        try:
            all_files = self._storage_service.get_all_files()
            self._logger.info(f"Found {len(all_files)} files to check")

            for file_meta in all_files:
                try:
                    policy = self._storage_service.get_policy(file_meta.lifecycle_policy_id)
                    original_tier = file_meta.storage_tier

                    updated_meta = await self.apply_lifecycle_policy(file_meta, policy)

                    if updated_meta.storage_tier != original_tier:
                        result.results.append({
                            "file_id": str(file_meta.id),
                            "file_name": file_meta.file_name,
                            "action": "move",
                            "from_tier": original_tier.value,
                            "to_tier": updated_meta.storage_tier.value,
                        })
                    elif updated_meta.status == FileStatus.DELETED:
                        result.results.append({
                            "file_id": str(file_meta.id),
                            "file_name": file_meta.file_name,
                            "action": "delete",
                        })

                except Exception as e:
                    result.errors.append({
                        "file_id": str(file_meta.id),
                        "error": str(e),
                    })
                    self._logger.error(
                        f"Error processing file {file_meta.file_name}",
                        file_id=str(file_meta.id),
                        error=str(e),
                    )

            result.status = ProcessingStatus.SUCCESS
            result.message = f"Lifecycle check completed. Processed {len(all_files)} files."

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Lifecycle check failed: {str(e)}"
            self._logger.error(f"Lifecycle check failed: {str(e)}")

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        self._logger.info(
            f"Lifecycle check completed",
            duration_ms=result.duration_ms,
            files_processed=len(result.results),
            errors=len(result.errors),
        )

        return result

    async def move_file_between_tiers(
        self,
        file_id: UUID,
        target_tier: StorageTier,
    ) -> FileMetadata:
        file_meta = await self._storage_service.get_file_metadata(file_id)

        if file_meta.storage_tier == target_tier:
            return file_meta

        if file_meta.status != FileStatus.ACTIVE:
            raise ValueError(f"Cannot move file with status: {file_meta.status}")

        source_backend = self._storage_factory.get_backend(file_meta.storage_tier)
        target_backend = self._storage_factory.get_backend(target_tier)

        self._logger.info(
            f"Moving file between tiers",
            file_id=str(file_id),
            file_name=file_meta.file_name,
            from_tier=file_meta.storage_tier.value,
            to_tier=target_tier.value,
        )

        content, metadata = await source_backend.get_file(file_meta.file_path)

        new_file_path = file_meta.file_path
        if target_tier == StorageTier.COLD and not new_file_path.endswith(".zst"):
            new_file_path = file_meta.file_path + ".zst"
        elif target_tier == StorageTier.ARCHIVE and not new_file_path.endswith(".zst"):
            new_file_path = file_meta.file_path + ".zst"

        await target_backend.save_file(new_file_path, content, metadata)

        await source_backend.delete_file(file_meta.file_path)

        updated_meta = await self._storage_service.update_file_metadata(
            file_id,
            storage_tier=target_tier,
            file_path=new_file_path,
        )

        self._logger.info(
            f"File moved successfully",
            file_id=str(file_id),
            to_tier=target_tier.value,
        )

        return updated_meta

    async def restore_file(
        self,
        file_id: UUID,
        restore_days: int = 7,
    ) -> FileMetadata:
        file_meta = await self._storage_service.get_file_metadata(file_id)

        if file_meta.storage_tier not in [StorageTier.COLD, StorageTier.ARCHIVE]:
            return file_meta

        self._logger.info(
            f"Restoring file from {file_meta.storage_tier.value} to hot storage",
            file_id=str(file_id),
            file_name=file_meta.file_name,
            restore_days=restore_days,
        )

        restored_meta = await self.move_file_between_tiers(file_id, StorageTier.HOT)

        custom_meta = dict(restored_meta.custom_metadata)
        custom_meta["restored_at"] = datetime.utcnow().isoformat()
        custom_meta["restore_expires_at"] = datetime.fromtimestamp(
            datetime.utcnow().timestamp() + restore_days * 86400
        ).isoformat()

        return await self._storage_service.update_file_metadata(
            file_id,
            custom_metadata=custom_meta,
        )

    async def delete_expired_files(self) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        self._logger.info("Starting expired files cleanup")

        try:
            all_files = self._storage_service.get_all_files()
            deleted_count = 0

            for file_meta in all_files:
                try:
                    policy = self._storage_service.get_policy(file_meta.lifecycle_policy_id)

                    if file_meta.should_delete(policy):
                        success = await self._storage_service.delete_file(file_meta.id)
                        if success:
                            deleted_count += 1
                            result.results.append({
                                "file_id": str(file_meta.id),
                                "file_name": file_meta.file_name,
                                "action": "delete_expired",
                            })

                except Exception as e:
                    result.errors.append({
                        "file_id": str(file_meta.id),
                        "error": str(e),
                    })

            result.status = ProcessingStatus.SUCCESS
            result.message = f"Expired files cleanup completed. Deleted {deleted_count} files."

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Expired files cleanup failed: {str(e)}"

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    async def get_storage_usage(self) -> Dict[StorageTier, Dict]:
        usage: Dict[StorageTier, Dict] = {}

        for tier in StorageTier:
            backend = self._storage_factory.get_backend(tier)
            available = await backend.get_available_space()

            total_size = 0
            file_count = 0
            all_files = self._storage_service.get_all_files()

            for file_meta in all_files:
                if file_meta.storage_tier == tier:
                    total_size += file_meta.file_size
                    file_count += 1

            usage[tier] = {
                "tier": tier.value,
                "file_count": file_count,
                "used_bytes": total_size,
                "available_bytes": available,
                "total_bytes": total_size + available,
            }

        return usage
