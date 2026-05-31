"""Storage module orchestrator."""
from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, Optional

from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus, LifecyclePolicy
from ...domain.errors.common import ValidationError
from ...infrastructure.cache.redis_cache import RedisCache
from ...infrastructure.config.settings import Settings
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.messaging.kafka_producer import KafkaMessagePublisher
from ...infrastructure.storage.storage_factory import StorageBackendFactory
from .lifecycle_manager import LifecycleManager
from .storage_service import StorageService


class StorageModule:
    def __init__(
        self,
        settings: Settings,
        cache: Optional[RedisCache] = None,
        publisher: Optional[KafkaMessagePublisher] = None,
    ) -> None:
        self._settings = settings
        self._storage_factory = StorageBackendFactory(settings.storage)
        self._storage_service = StorageService(
            settings=settings,
            storage_factory=self._storage_factory,
            cache=cache,
            publisher=publisher,
        )
        self._lifecycle_manager = LifecycleManager(
            settings=settings,
            storage_factory=self._storage_factory,
            storage_service=self._storage_service,
        )
        self._logger = LogManager().get_logger(__name__)
        self._logger.info("Storage module initialized")

    @property
    def storage_service(self) -> StorageService:
        return self._storage_service

    @property
    def lifecycle_manager(self) -> LifecycleManager:
        return self._lifecycle_manager

    @property
    def storage_factory(self) -> StorageBackendFactory:
        return self._storage_factory

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type in ["storage.upload", "file.upload"]:
                upload_result = await self._handle_upload(payload)
                result.results = [upload_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "File uploaded successfully"

            elif event_type in ["storage.download", "file.download"]:
                download_result = await self._handle_download(payload)
                result.results = [download_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "File downloaded successfully"

            elif event_type in ["storage.list", "file.list"]:
                list_result = await self._handle_list(payload)
                result.results = [list_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Files listed successfully"

            elif event_type in ["storage.delete", "file.delete"]:
                delete_result = await self._handle_delete(payload)
                result.results = [delete_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "File deleted successfully"

            elif event_type == "lifecycle.policy.create":
                policy_result = await self._handle_create_policy(payload)
                result.results = [policy_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Lifecycle policy created successfully"

            elif event_type in ["lifecycle.apply", "lifecycle.check"]:
                apply_result = await self._handle_apply_lifecycle(payload)
                result.results = [apply_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Lifecycle policies applied successfully"

            else:
                service_result = await self._storage_service.process_event(event)
                return service_result

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Storage event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Storage event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    async def _handle_upload(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        file_path = payload.get("file_path")
        content = payload.get("content")
        content_type = payload.get("content_type", "application/octet-stream")
        metadata = payload.get("metadata", {})

        if not file_path or content is None:
            raise ValidationError(
                message="file_path and content are required",
                suggestion="Provide 'file_path' and 'content' in the payload.",
            )

        file_meta = await self._storage_service.upload_file(
            file_path=file_path,
            content=content,
            content_type=content_type,
            metadata=metadata,
        )

        return {
            "file_id": str(file_meta.id),
            "file_name": file_meta.file_name,
            "file_size": file_meta.file_size,
            "storage_tier": file_meta.storage_tier.value,
            "checksum": file_meta.checksum,
        }

    async def _handle_download(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        file_id = payload.get("file_id")

        if not file_id:
            raise ValidationError(
                message="file_id is required",
                suggestion="Provide 'file_id' in the payload.",
            )

        from uuid import UUID
        content, file_meta = await self._storage_service.download_file(UUID(file_id))

        return {
            "content": content,
            "file_name": file_meta.file_name,
            "content_type": file_meta.content_type,
            "file_size": file_meta.file_size,
        }

    async def _handle_list(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        prefix = payload.get("prefix")
        limit = payload.get("limit", 100)
        offset = payload.get("offset", 0)

        files = await self._storage_service.list_files(prefix=prefix, limit=limit, offset=offset)

        return {
            "total": len(files),
            "limit": limit,
            "offset": offset,
            "files": [
                {
                    "file_id": str(f.id),
                    "file_name": f.file_name,
                    "file_path": f.file_path,
                    "file_size": f.file_size,
                    "storage_tier": f.storage_tier.value,
                    "status": f.status.value,
                    "last_accessed_at": f.last_accessed_at.isoformat(),
                }
                for f in files
            ],
        }

    async def _handle_delete(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        file_id = payload.get("file_id")

        if not file_id:
            raise ValidationError(
                message="file_id is required",
                suggestion="Provide 'file_id' in the payload.",
            )

        from uuid import UUID
        deleted = await self._storage_service.delete_file(UUID(file_id))

        return {
            "file_id": file_id,
            "deleted": deleted,
        }

    async def _handle_create_policy(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        name = payload.get("name")
        if not name:
            raise ValidationError(
                message="Policy name is required",
                suggestion="Provide 'name' in the payload.",
            )

        policy = LifecyclePolicy(
            name=name,
            description=payload.get("description"),
            hot_to_cold_days=payload.get("hot_to_cold_days", 30),
            cold_to_archive_days=payload.get("cold_to_archive_days", 90),
            archive_retention_days=payload.get("archive_retention_days", 365),
            enabled=payload.get("enabled", True),
            tags=payload.get("tags", {}),
        )

        self._storage_service._policies[policy.id] = policy

        return {
            "policy_id": str(policy.id),
            "name": policy.name,
            "hot_to_cold_days": policy.hot_to_cold_days,
            "cold_to_archive_days": policy.cold_to_archive_days,
            "archive_retention_days": policy.archive_retention_days,
        }

    async def _handle_apply_lifecycle(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        results = await self._lifecycle_manager.apply_policies()

        return {
            "files_processed": len(results),
            "moved_to_cold": sum(1 for r in results if r.get("action") == "move_to_cold"),
            "moved_to_archive": sum(1 for r in results if r.get("action") == "move_to_archive"),
            "deleted": sum(1 for r in results if r.get("action") == "delete"),
            "details": results,
        }

    async def start(self) -> None:
        self._logger.info("Starting storage module")

    async def stop(self) -> None:
        self._logger.info("Stopping storage module")

    async def get_status(self) -> dict:
        usage = await self._lifecycle_manager.get_storage_usage()
        return {
            "module": "storage",
            "status": "running",
            "storage_usage": {tier.value: data for tier, data in usage.items()},
        }
