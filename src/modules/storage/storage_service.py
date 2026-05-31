"""Storage service implementation for file storage and lifecycle management."""
from __future__ import annotations

import asyncio
import hashlib
from datetime import datetime
from typing import AsyncIterator, Dict, List, Optional, Tuple
from uuid import UUID, uuid4

from ...domain.contracts.storage import IStorageBackend, IStorageService
from ...domain.errors.common import MissingRequiredFieldError, ValidationError
from ...domain.errors.storage import (
    ChecksumMismatchError,
    FileNotFoundError,
    FileAlreadyExistsError,
)
from ...domain.models.common import (
    EventMessage,
    FileMetadata,
    FileStatus,
    LifecyclePolicy,
    ProcessingResult,
    ProcessingStatus,
    StorageTier,
)
from ...infrastructure.cache.redis_cache import RedisCache
from ...infrastructure.config.settings import Settings
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.messaging.kafka_producer import KafkaMessagePublisher
from ...infrastructure.storage.storage_factory import StorageBackendFactory


class StorageService(IStorageService):
    def __init__(
        self,
        settings: Settings,
        storage_factory: StorageBackendFactory,
        cache: Optional[RedisCache] = None,
        publisher: Optional[KafkaMessagePublisher] = None,
    ) -> None:
        self._settings = settings
        self._storage_factory = storage_factory
        self._cache = cache
        self._publisher = publisher
        self._logger = LogManager().get_logger(__name__)
        self._metadata_store: Dict[UUID, FileMetadata] = {}
        self._default_policy = LifecyclePolicy(
            name="default",
            hot_to_cold_days=settings.lifecycle.hot_to_cold_days,
            cold_to_archive_days=settings.lifecycle.cold_to_archive_days,
            archive_retention_days=settings.lifecycle.archive_retention_days,
        )
        self._policies: Dict[UUID, LifecyclePolicy] = {self._default_policy.id: self._default_policy}

    def calculate_checksum(self, data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()

    async def validate_event(self, event: EventMessage) -> None:
        if not event.event_type:
            raise MissingRequiredFieldError("event_type")
        if not event.source:
            raise MissingRequiredFieldError("source")
        if not event.payload:
            raise ValidationError(
                message="Event payload cannot be empty",
                field="payload",
                suggestion="Provide a valid payload with required fields for the operation.",
            )

        valid_event_types = [
            "file.upload",
            "file.download",
            "file.delete",
            "file.move",
            "lifecycle.check",
        ]
        if event.event_type not in valid_event_types:
            raise ValidationError(
                message=f"Invalid event type: {event.event_type}",
                field="event_type",
                suggestion=f"Use one of the valid event types: {', '.join(valid_event_types)}",
            )

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            await self.validate_event(event)
            self._logger.info(
                f"Processing event: {event.event_type}",
                event_id=str(event.event_id),
                correlation_id=event.correlation_id,
            )

            handler_map = {
                "file.upload": self._handle_upload_event,
                "file.download": self._handle_download_event,
                "file.delete": self._handle_delete_event,
                "file.move": self._handle_move_event,
                "lifecycle.check": self._handle_lifecycle_check_event,
            }

            handler = handler_map.get(event.event_type)
            if handler:
                handler_result = await handler(event.payload)
                result.results = handler_result
                result.status = ProcessingStatus.SUCCESS
                result.message = f"Successfully processed event: {event.event_type}"
            else:
                result.status = ProcessingStatus.FAILED
                result.message = f"No handler found for event type: {event.event_type}"

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Error processing event: {str(e)}"
            result.errors.append({
                "error": str(e),
                "error_code": getattr(e, "code", "INTERNAL_ERROR"),
            })
            self._logger.error(
                f"Failed to process event: {event.event_type}",
                error=str(e),
                event_id=str(event.event_id),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        await self._publish_event_result(event, result)

        return result

    async def _handle_upload_event(self, payload: Dict) -> List[Dict]:
        file_name = payload.get("file_name")
        content = payload.get("content")
        content_type = payload.get("content_type", "application/octet-stream")
        metadata = payload.get("metadata")

        if not file_name:
            raise MissingRequiredFieldError("file_name")
        if content is None:
            raise MissingRequiredFieldError("content")

        if isinstance(content, str):
            content = content.encode("utf-8")

        file_meta = await self.upload_file(file_name, content, content_type, metadata)
        return [{"file_id": str(file_meta.id), "file_name": file_meta.file_name}]

    async def _handle_download_event(self, payload: Dict) -> List[Dict]:
        file_id_str = payload.get("file_id")
        if not file_id_str:
            raise MissingRequiredFieldError("file_id")

        file_id = UUID(file_id_str)
        content, metadata = await self.download_file(file_id)
        return [{
            "file_id": str(file_id),
            "file_name": metadata.file_name,
            "content_size": len(content),
            "content_type": metadata.content_type,
        }]

    async def _handle_delete_event(self, payload: Dict) -> List[Dict]:
        file_id_str = payload.get("file_id")
        if not file_id_str:
            raise MissingRequiredFieldError("file_id")

        file_id = UUID(file_id_str)
        success = await self.delete_file(file_id)
        return [{"file_id": str(file_id), "deleted": success}]

    async def _handle_move_event(self, payload: Dict) -> List[Dict]:
        file_id_str = payload.get("file_id")
        target_tier_str = payload.get("target_tier")

        if not file_id_str:
            raise MissingRequiredFieldError("file_id")
        if not target_tier_str:
            raise MissingRequiredFieldError("target_tier")

        file_id = UUID(file_id_str)
        target_tier = StorageTier(target_tier_str)

        from .lifecycle_manager import LifecycleManager
        lifecycle_manager = LifecycleManager(
            self._settings,
            self._storage_factory,
            self,
        )
        file_meta = await lifecycle_manager.move_file_between_tiers(file_id, target_tier)
        return [{"file_id": str(file_id), "new_tier": file_meta.storage_tier.value}]

    async def _handle_lifecycle_check_event(self, payload: Dict) -> List[Dict]:
        from .lifecycle_manager import LifecycleManager
        lifecycle_manager = LifecycleManager(
            self._settings,
            self._storage_factory,
            self,
        )
        result = await lifecycle_manager.run_lifecycle_check()
        return [{
            "files_processed": len(result.results),
            "files_moved": sum(1 for r in result.results if r.get("action") == "move"),
            "files_deleted": sum(1 for r in result.results if r.get("action") == "delete"),
        }]

    async def _publish_event_result(self, event: EventMessage, result: ProcessingResult) -> None:
        if self._publisher is None:
            return

        try:
            result_event = EventMessage(
                event_type=f"{event.event_type}.result",
                source="storage.service",
                payload={
                    "original_event_id": str(event.event_id),
                    "status": result.status.value,
                    "message": result.message,
                    "results": result.results,
                    "duration_ms": result.duration_ms,
                },
                correlation_id=event.correlation_id,
            )
            await self._publisher.publish(
                self._settings.messaging.kafka.topics.storage_events,
                result_event,
            )
        except Exception as e:
            self._logger.warning(f"Failed to publish result event: {e}")

    async def upload_file(
        self,
        file_name: str,
        content: bytes,
        content_type: str,
        metadata: Optional[Dict] = None,
    ) -> FileMetadata:
        checksum = self.calculate_checksum(content)
        file_id = uuid4()
        storage_path = f"{file_id}/{file_name}"

        backend = self._storage_factory.get_backend(StorageTier.HOT)

        if await backend.file_exists(storage_path):
            raise FileAlreadyExistsError(storage_path)

        full_path, stored_checksum = await backend.save_file(
            storage_path,
            content,
            metadata,
        )

        if stored_checksum != checksum:
            await backend.delete_file(storage_path)
            raise ChecksumMismatchError(storage_path, checksum, stored_checksum)

        file_meta = FileMetadata(
            id=file_id,
            file_name=file_name,
            file_path=storage_path,
            file_size=len(content),
            content_type=content_type,
            storage_tier=StorageTier.HOT,
            status=FileStatus.ACTIVE,
            checksum=checksum,
            lifecycle_policy_id=self._default_policy.id,
            custom_metadata=metadata or {},
        )

        self._metadata_store[file_id] = file_meta

        if self._cache is not None:
            cache_key = f"file:meta:{file_id}"
            await self._cache.set(cache_key, file_meta.model_dump())

        self._logger.info(
            f"File uploaded successfully: {file_name}",
            file_id=str(file_id),
            size=len(content),
            checksum=checksum,
        )

        return file_meta

    async def download_file(self, file_id: UUID) -> Tuple[bytes, FileMetadata]:
        file_meta = await self.get_file_metadata(file_id)

        if file_meta.status in [FileStatus.DELETING, FileStatus.DELETED]:
            raise FileNotFoundError(str(file_id), suggestion="The file has been deleted.")

        backend = self._storage_factory.get_backend(file_meta.storage_tier)
        content, metadata = await backend.get_file(file_meta.file_path)

        actual_checksum = self.calculate_checksum(content)
        if actual_checksum != file_meta.checksum:
            raise ChecksumMismatchError(file_meta.file_path, file_meta.checksum, actual_checksum)

        file_meta.access_count += 1
        file_meta.last_accessed_at = datetime.utcnow()
        self._metadata_store[file_id] = file_meta

        if self._cache is not None:
            cache_key = f"file:meta:{file_id}"
            await self._cache.set(cache_key, file_meta.model_dump())

        return content, file_meta

    async def get_file_metadata(self, file_id: UUID) -> FileMetadata:
        cache_key = f"file:meta:{file_id}"

        if self._cache is not None:
            cached = await self._cache.get(cache_key)
            if cached:
                return FileMetadata.model_validate(cached)

        if file_id not in self._metadata_store:
            raise FileNotFoundError(
                str(file_id),
                suggestion="Check that the file ID is correct and the file has not been deleted.",
            )

        file_meta = self._metadata_store[file_id]

        if self._cache is not None:
            await self._cache.set(cache_key, file_meta.model_dump())

        return file_meta

    async def list_files(
        self,
        tier: Optional[StorageTier] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> Tuple[List[FileMetadata], int]:
        files = list(self._metadata_store.values())

        if tier is not None:
            files = [f for f in files if f.storage_tier == tier]

        files = [f for f in files if f.status != FileStatus.DELETED]
        files.sort(key=lambda f: f.created_at, reverse=True)

        total = len(files)
        paginated = files[offset:offset + limit]

        return paginated, total

    async def delete_file(self, file_id: UUID) -> bool:
        try:
            file_meta = await self.get_file_metadata(file_id)
        except FileNotFoundError:
            return False

        file_meta.status = FileStatus.DELETING
        self._metadata_store[file_id] = file_meta

        try:
            backend = self._storage_factory.get_backend(file_meta.storage_tier)
            success = await backend.delete_file(file_meta.file_path)

            if success:
                file_meta.status = FileStatus.DELETED
                self._metadata_store[file_id] = file_meta

                if self._cache is not None:
                    cache_key = f"file:meta:{file_id}"
                    await self._cache.delete(cache_key)

                self._logger.info(
                    f"File deleted successfully",
                    file_id=str(file_id),
                    file_name=file_meta.file_name,
                )
                return True

        except Exception as e:
            file_meta.status = FileStatus.ERROR
            self._metadata_store[file_id] = file_meta
            self._logger.error(
                f"Failed to delete file",
                file_id=str(file_id),
                error=str(e),
            )

        return False

    async def stream_file(
        self,
        file_id: UUID,
        chunk_size: int = 8192,
    ) -> AsyncIterator[bytes]:
        content, file_meta = await self.download_file(file_id)

        for i in range(0, len(content), chunk_size):
            yield content[i:i + chunk_size]

    def get_policy(self, policy_id: Optional[UUID] = None) -> LifecyclePolicy:
        if policy_id is None:
            return self._default_policy
        return self._policies.get(policy_id, self._default_policy)

    def add_policy(self, policy: LifecyclePolicy) -> None:
        self._policies[policy.id] = policy

    async def update_file_metadata(self, file_id: UUID, **kwargs) -> FileMetadata:
        file_meta = await self.get_file_metadata(file_id)

        for key, value in kwargs.items():
            if hasattr(file_meta, key):
                setattr(file_meta, key, value)

        file_meta.updated_at = datetime.utcnow()
        self._metadata_store[file_id] = file_meta

        if self._cache is not None:
            cache_key = f"file:meta:{file_id}"
            await self._cache.set(cache_key, file_meta.model_dump())

        return file_meta

    def get_all_files(self) -> List[FileMetadata]:
        return [
            f for f in self._metadata_store.values()
            if f.status != FileStatus.DELETED
        ]
