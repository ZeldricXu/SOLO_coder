from typing import Dict, List, Optional, Any
from datetime import datetime, timedelta
from pathlib import Path
import os
import shutil
import hashlib
import gzip
import asyncio
import aiofiles
from .types import (
    StorageType,
    BackupStatus,
    RestoreStatus,
    BackupPolicy,
    BackupRecord,
    RestoreRequest,
    RestoreRecord,
    StorageConfig,
    StorageUsage,
)
from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    NotFoundError,
    ValidationError,
    PlatformError,
    generate_id,
    settings,
)
import logging

logger = logging.getLogger(__name__)


class StorageManagerService:
    def __init__(self):
        self._policies: Dict[str, BackupPolicy] = {}
        self._backups: Dict[str, BackupRecord] = {}
        self._restores: Dict[str, RestoreRecord] = {}
        self._configs: Dict[str, StorageConfig] = {}
        self._metrics = get_metrics_collector()

        Path(settings.storage_backup_path).mkdir(parents=True, exist_ok=True)
        Path(settings.storage_data_path).mkdir(parents=True, exist_ok=True)

    async def add_storage_config(
        self,
        config: StorageConfig,
        trace_id: Optional[str] = None,
    ) -> StorageConfig:
        with init_context(trace_id, operation="add_storage_config"):
            try:
                config.config_id = config.config_id or generate_id("scfg")
                self._configs[config.config_id] = config
                emit_event(
                    "storage.config.added",
                    {"config_id": config.config_id, "type": config.storage_type.value},
                    source="storage_manager",
                )
                return config
            except Exception as e:
                logger.error(f"Failed to add storage config: {e}")
                raise PlatformError(f"存储配置添加失败: {str(e)}")

    async def create_backup_policy(
        self,
        policy: BackupPolicy,
        trace_id: Optional[str] = None,
    ) -> BackupPolicy:
        with init_context(trace_id, operation="create_backup_policy"):
            try:
                policy.policy_id = policy.policy_id or generate_id("bpol")

                source_path = Path(policy.source_path)
                if not source_path.exists():
                    raise ValidationError(f"Source path does not exist: {policy.source_path}")

                self._policies[policy.policy_id] = policy
                emit_event(
                    "storage.policy.created",
                    {"policy_id": policy.policy_id, "name": policy.name},
                    source="storage_manager",
                )
                self._metrics.increment("storage_backup_policies_created")
                return policy

            except ValidationError:
                raise
            except Exception as e:
                logger.error(f"Failed to create backup policy: {e}")
                raise PlatformError(f"备份策略创建失败: {str(e)}")

    async def execute_backup(
        self,
        policy_id: str,
        trace_id: Optional[str] = None,
    ) -> BackupRecord:
        with init_context(trace_id, operation="execute_backup"):
            policy = self._policies.get(policy_id)
            if not policy:
                raise NotFoundError(f"Backup policy not found: {policy_id}")

            if not policy.enabled:
                raise ValidationError(f"Backup policy is disabled: {policy_id}")

            backup_id = generate_id("backup")
            backup_record = BackupRecord(
                backup_id=backup_id,
                policy_id=policy_id,
                source_path=policy.source_path,
                destination=policy.destination,
                storage_type=policy.storage_type,
                status=BackupStatus.IN_PROGRESS,
                started_at=datetime.utcnow(),
            )
            self._backups[backup_id] = backup_record

            emit_event(
                "storage.backup.started",
                {"backup_id": backup_id, "policy_id": policy_id},
                source="storage_manager",
            )
            self._metrics.increment("storage_backups_started")

            try:
                size_bytes, file_count, checksum = await self._perform_backup(
                    policy.source_path,
                    policy.destination,
                    policy.compression,
                    policy.encryption,
                )

                backup_record.size_bytes = size_bytes
                backup_record.file_count = file_count
                backup_record.checksum = checksum
                backup_record.status = BackupStatus.COMPLETED
                backup_record.completed_at = datetime.utcnow()
                backup_record.compression_ratio = size_bytes / max(size_bytes + 1, 1) if size_bytes > 0 else 0.0

                self._backups[backup_id] = backup_record

                emit_event(
                    "storage.backup.completed",
                    {"backup_id": backup_id, "size_bytes": size_bytes},
                    source="storage_manager",
                )
                self._metrics.increment("storage_backups_completed")

                return backup_record

            except Exception as e:
                backup_record.status = BackupStatus.FAILED
                backup_record.error_message = str(e)
                backup_record.completed_at = datetime.utcnow()
                self._backups[backup_id] = backup_record

                logger.error(f"Backup failed: {e}")
                emit_event(
                    "storage.backup.failed",
                    {"backup_id": backup_id, "error": str(e)},
                    source="storage_manager",
                )
                self._metrics.increment("storage_backups_failed")
                raise PlatformError(f"备份执行失败: {str(e)}")

    async def _perform_backup(
        self,
        source_path: str,
        destination: str,
        compression: bool,
        encryption: bool,
    ) -> tuple:
        source = Path(source_path)
        dest = Path(destination)
        dest.mkdir(parents=True, exist_ok=True)

        total_size = 0
        file_count = 0
        checksum = hashlib.sha256()

        async def backup_file(src_file: Path, dest_file: Path):
            nonlocal total_size, file_count, checksum
            file_size = src_file.stat().st_size
            total_size += file_size
            file_count += 1

            dest_file.parent.mkdir(parents=True, exist_ok=True)

            async with aiofiles.open(src_file, "rb") as f:
                content = await f.read()
                checksum.update(content)

                if compression:
                    compressed = gzip.compress(content)
                    async with aiofiles.open(str(dest_file) + ".gz", "wb") as out_f:
                        await out_f.write(compressed)
                else:
                    async with aiofiles.open(dest_file, "wb") as out_f:
                        await out_f.write(content)

        tasks = []
        for src_file in source.rglob("*"):
            if src_file.is_file():
                relative = src_file.relative_to(source)
                dest_file = dest / relative
                tasks.append(backup_file(src_file, dest_file))

        if tasks:
            await asyncio.gather(*tasks)

        return total_size, file_count, checksum.hexdigest()

    async def execute_restore(
        self,
        request: RestoreRequest,
        trace_id: Optional[str] = None,
    ) -> RestoreRecord:
        with init_context(trace_id, operation="execute_restore"):
            backup = self._backups.get(request.backup_id)
            if not backup:
                raise NotFoundError(f"Backup not found: {request.backup_id}")

            if backup.status != BackupStatus.COMPLETED:
                raise ValidationError(f"Backup is not in completed state: {backup.status.value}")

            target_path = Path(request.target_path)
            if target_path.exists() and not request.overwrite:
                raise ValidationError(f"Target path exists and overwrite is not allowed")

            restore_id = generate_id("restore")
            restore_record = RestoreRecord(
                restore_id=restore_id,
                backup_id=request.backup_id,
                target_path=request.target_path,
                status=RestoreStatus.IN_PROGRESS,
                started_at=datetime.utcnow(),
            )
            self._restores[restore_id] = restore_record

            emit_event(
                "storage.restore.started",
                {"restore_id": restore_id, "backup_id": request.backup_id},
                source="storage_manager",
            )
            self._metrics.increment("storage_restores_started")

            try:
                file_count, size_bytes = await self._perform_restore(
                    backup.destination,
                    request.target_path,
                )

                restore_record.file_count = file_count
                restore_record.size_bytes = size_bytes
                restore_record.status = RestoreStatus.COMPLETED
                restore_record.completed_at = datetime.utcnow()
                self._restores[restore_id] = restore_record

                emit_event(
                    "storage.restore.completed",
                    {"restore_id": restore_id, "file_count": file_count},
                    source="storage_manager",
                )
                self._metrics.increment("storage_restores_completed")

                return restore_record

            except Exception as e:
                restore_record.status = RestoreStatus.FAILED
                restore_record.error_message = str(e)
                restore_record.completed_at = datetime.utcnow()
                self._restores[restore_id] = restore_record

                logger.error(f"Restore failed: {e}")
                emit_event(
                    "storage.restore.failed",
                    {"restore_id": restore_id, "error": str(e)},
                    source="storage_manager",
                )
                self._metrics.increment("storage_restores_failed")
                raise PlatformError(f"恢复执行失败: {str(e)}")

    async def _perform_restore(
        self,
        source_path: str,
        target_path: str,
    ) -> tuple:
        source = Path(source_path)
        target = Path(target_path)
        target.mkdir(parents=True, exist_ok=True)

        total_size = 0
        file_count = 0

        async def restore_file(src_file: Path, dest_file: Path, is_compressed: bool):
            nonlocal total_size, file_count
            dest_file.parent.mkdir(parents=True, exist_ok=True)

            async with aiofiles.open(src_file, "rb") as f:
                content = await f.read()
                if is_compressed:
                    content = gzip.decompress(content)
                async with aiofiles.open(dest_file, "wb") as out_f:
                    await out_f.write(content)
                total_size += len(content)
                file_count += 1

        tasks = []
        for src_file in source.rglob("*"):
            if src_file.is_file():
                relative = src_file.relative_to(source)
                is_compressed = str(src_file).endswith(".gz")
                dest_name = relative.name[:-3] if is_compressed else relative.name
                dest_file = target / relative.parent / dest_name
                tasks.append(restore_file(src_file, dest_file, is_compressed))

        if tasks:
            await asyncio.gather(*tasks)

        return file_count, total_size

    async def verify_backup(
        self,
        backup_id: str,
        trace_id: Optional[str] = None,
    ) -> bool:
        with init_context(trace_id, operation="verify_backup"):
            backup = self._backups.get(backup_id)
            if not backup:
                raise NotFoundError(f"Backup not found: {backup_id}")

            try:
                dest = Path(backup.destination)
                if not dest.exists():
                    backup.status = BackupStatus.FAILED
                    self._backups[backup_id] = backup
                    return False

                current_checksum = hashlib.sha256()
                for f in dest.rglob("*"):
                    if f.is_file():
                        async with aiofiles.open(f, "rb") as file:
                            current_checksum.update(await file.read())

                is_valid = current_checksum.hexdigest() == backup.checksum
                if is_valid:
                    backup.status = BackupStatus.VERIFIED
                    self._backups[backup_id] = backup

                return is_valid

            except Exception as e:
                logger.error(f"Backup verification failed: {e}")
                raise PlatformError(f"备份验证失败: {str(e)}")

    async def get_storage_usage(
        self,
        config_id: Optional[str] = None,
        trace_id: Optional[str] = None,
    ) -> StorageUsage:
        with init_context(trace_id, operation="get_storage_usage"):
            path = Path(settings.storage_data_path)
            total, used, free = shutil.disk_usage(path)

            file_count = 0
            for _ in path.rglob("*"):
                if _.is_file():
                    file_count += 1

            return StorageUsage(
                total_bytes=total,
                used_bytes=used,
                free_bytes=free,
                file_count=file_count,
            )

    async def cleanup_expired_backups(
        self,
        trace_id: Optional[str] = None,
    ) -> int:
        with init_context(trace_id, operation="cleanup_expired_backups"):
            deleted_count = 0
            now = datetime.utcnow()

            for backup_id, backup in list(self._backups.items()):
                policy = self._policies.get(backup.policy_id)
                if policy and backup.completed_at:
                    age = (now - backup.completed_at).days
                    if age > policy.retention_days:
                        backup_path = Path(backup.destination)
                        if backup_path.exists():
                            shutil.rmtree(backup_path)
                        del self._backups[backup_id]
                        deleted_count += 1
                        logger.info(f"Deleted expired backup: {backup_id}")

            if deleted_count > 0:
                emit_event(
                    "storage.backups.cleaned",
                    {"count": deleted_count},
                    source="storage_manager",
                )

            return deleted_count

    async def list_backups(
        self,
        policy_id: Optional[str] = None,
        status: Optional[BackupStatus] = None,
        limit: int = 100,
        trace_id: Optional[str] = None,
    ) -> List[BackupRecord]:
        with init_context(trace_id, operation="list_backups"):
            backups = list(self._backups.values())
            if policy_id:
                backups = [b for b in backups if b.policy_id == policy_id]
            if status:
                backups = [b for b in backups if b.status == status]
            return sorted(backups, key=lambda b: b.started_at or datetime.utcnow(), reverse=True)[:limit]

    async def list_policies(
        self,
        trace_id: Optional[str] = None,
    ) -> List[BackupPolicy]:
        with init_context(trace_id, operation="list_policies"):
            return list(self._policies.values())

    async def get_restore(
        self,
        restore_id: str,
        trace_id: Optional[str] = None,
    ) -> RestoreRecord:
        with init_context(trace_id, operation="get_restore"):
            restore = self._restores.get(restore_id)
            if not restore:
                raise NotFoundError(f"Restore not found: {restore_id}")
            return restore
