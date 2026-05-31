import hashlib
import json
import tarfile
import threading
import uuid
import zlib
from datetime import datetime
from enum import Enum
from io import BytesIO
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

from .storage_tier import StorageTier, ArchiveStorage


class ArchiveStatus(Enum):
    PENDING = "pending"
    ARCHIVING = "archiving"
    COMPLETED = "completed"
    FAILED = "failed"
    RESTORING = "restoring"
    RESTORED = "restored"


class ArchiveMetadata:
    def __init__(
        self,
        archive_id: str,
        source_keys: List[str],
        compression: bool = True,
        encryption: bool = False,
        retention_days: Optional[int] = None,
        custom_metadata: Optional[Dict[str, Any]] = None,
    ):
        self.archive_id = archive_id
        self.source_keys = source_keys
        self.compression = compression
        self.encryption = encryption
        self.retention_days = retention_days
        self.custom_metadata = custom_metadata or {}
        self.created_at = datetime.now()
        self.size_bytes: Optional[int] = None
        self.checksum: Optional[str] = None
        self.storage_path: Optional[str] = None
        self.is_restored = False
        self.restored_at: Optional[datetime] = None

    def is_expired(self) -> bool:
        if self.retention_days is None:
            return False
        age_days = (datetime.now() - self.created_at).days
        return age_days >= self.retention_days

    def to_dict(self) -> Dict[str, Any]:
        return {
            "archive_id": self.archive_id,
            "source_keys": self.source_keys.copy(),
            "compression": self.compression,
            "encryption": self.encryption,
            "retention_days": self.retention_days,
            "custom_metadata": self.custom_metadata.copy(),
            "created_at": self.created_at.isoformat(),
            "size_bytes": self.size_bytes,
            "checksum": self.checksum,
            "storage_path": self.storage_path,
            "is_restored": self.is_restored,
            "restored_at": self.restored_at.isoformat() if self.restored_at else None,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ArchiveMetadata":
        metadata = cls(
            archive_id=data["archive_id"],
            source_keys=data.get("source_keys", []),
            compression=data.get("compression", True),
            encryption=data.get("encryption", False),
            retention_days=data.get("retention_days"),
            custom_metadata=data.get("custom_metadata", {}),
        )
        metadata.created_at = datetime.fromisoformat(data["created_at"])
        metadata.size_bytes = data.get("size_bytes")
        metadata.checksum = data.get("checksum")
        metadata.storage_path = data.get("storage_path")
        metadata.is_restored = data.get("is_restored", False)
        if data.get("restored_at"):
            metadata.restored_at = datetime.fromisoformat(data["restored_at"])
        return metadata


class RestoreTask:
    def __init__(
        self,
        archive_id: str,
        target_tier: StorageTier,
        keys_to_restore: Optional[List[str]] = None,
        description: Optional[str] = None,
    ):
        self.task_id = str(uuid.uuid4())
        self.archive_id = archive_id
        self.target_tier = target_tier
        self.keys_to_restore = keys_to_restore
        self.description = description
        self.status = ArchiveStatus.PENDING
        self.progress = 0.0
        self.restored_keys: List[str] = []
        self.failed_keys: List[str] = []
        self.error_messages: List[str] = []
        self.created_at = datetime.now()
        self.completed_at: Optional[datetime] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "archive_id": self.archive_id,
            "target_tier": self.target_tier.name,
            "keys_to_restore": self.keys_to_restore,
            "description": self.description,
            "status": self.status.value,
            "progress": self.progress,
            "restored_keys": self.restored_keys.copy(),
            "failed_keys": self.failed_keys.copy(),
            "error_messages": self.error_messages.copy(),
            "created_at": self.created_at.isoformat(),
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
        }


class DataArchiver:
    def __init__(
        self,
        archive_storage: ArchiveStorage,
        source_tiers: Optional[Dict[str, StorageTier]] = None,
        metadata_store_path: str = "./data/archive_metadata",
    ):
        self.archive_storage = archive_storage
        self.source_tiers = source_tiers or {}
        self.metadata_store_path = Path(metadata_store_path)
        self.metadata_store_path.mkdir(parents=True, exist_ok=True)
        self._archives: Dict[str, ArchiveMetadata] = {}
        self._restore_tasks: Dict[str, RestoreTask] = {}
        self._lock = threading.RLock()
        self._load_metadata()

    def _load_metadata(self) -> None:
        for metadata_file in self.metadata_store_path.glob("*.json"):
            try:
                with open(metadata_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    metadata = ArchiveMetadata.from_dict(data)
                    self._archives[metadata.archive_id] = metadata
            except Exception:
                continue

    def _save_metadata(self, metadata: ArchiveMetadata) -> None:
        metadata_file = self.metadata_store_path / f"{metadata.archive_id}.json"
        with open(metadata_file, "w", encoding="utf-8") as f:
            json.dump(metadata.to_dict(), f, indent=2)

    def _calculate_checksum(self, data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()

    def _compress_data(self, data: bytes) -> bytes:
        return zlib.compress(data, level=9)

    def _decompress_data(self, data: bytes) -> bytes:
        return zlib.decompress(data)

    def _create_tar_archive(self, files: Dict[str, bytes]) -> bytes:
        buffer = BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as tar:
            for key, data in files.items():
                info = tarfile.TarInfo(name=key)
                info.size = len(data)
                info.mtime = datetime.now().timestamp()
                tar.addfile(info, BytesIO(data))
        return buffer.getvalue()

    def _extract_tar_archive(self, archive_data: bytes) -> Dict[str, bytes]:
        files: Dict[str, bytes] = {}
        buffer = BytesIO(archive_data)
        with tarfile.open(fileobj=buffer, mode="r") as tar:
            for member in tar.getmembers():
                if member.isfile():
                    f = tar.extractfile(member)
                    if f:
                        files[member.name] = f.read()
        return files

    def create_archive(
        self,
        source_tier: StorageTier,
        keys: List[str],
        compression: bool = True,
        encryption: bool = False,
        retention_days: Optional[int] = None,
        custom_metadata: Optional[Dict[str, Any]] = None,
        delete_source: bool = False,
    ) -> ArchiveMetadata:
        archive_id = str(uuid.uuid4())

        metadata = ArchiveMetadata(
            archive_id=archive_id,
            source_keys=keys,
            compression=compression,
            encryption=encryption,
            retention_days=retention_days,
            custom_metadata=custom_metadata,
        )

        files: Dict[str, bytes] = {}
        for key in keys:
            data = source_tier.get(key)
            if data is None:
                raise ValueError(f"Key {key} not found in source tier")
            files[key] = data

        archive_data = self._create_tar_archive(files)

        if compression:
            archive_data = self._compress_data(archive_data)

        checksum = self._calculate_checksum(archive_data)
        metadata.checksum = checksum
        metadata.size_bytes = len(archive_data)

        archive_key = f"archives/{archive_id}.tar"
        if compression:
            archive_key += ".gz"

        success = self.archive_storage.put(archive_key, archive_data)
        if not success:
            raise RuntimeError(f"Failed to store archive {archive_id}")

        metadata.storage_path = archive_key

        with self._lock:
            self._archives[archive_id] = metadata
            self._save_metadata(metadata)

        if delete_source:
            for key in keys:
                try:
                    source_tier.delete(key)
                except Exception:
                    pass

        return metadata

    def create_archive_async(
        self,
        source_tier: StorageTier,
        keys: List[str],
        compression: bool = True,
        encryption: bool = False,
        retention_days: Optional[int] = None,
        custom_metadata: Optional[Dict[str, Any]] = None,
        delete_source: bool = False,
        callback: Optional[Callable[[ArchiveMetadata, Optional[Exception]], None]] = None,
    ) -> str:
        archive_id = str(uuid.uuid4())

        def _archive_thread():
            try:
                metadata = self.create_archive(
                    source_tier=source_tier,
                    keys=keys,
                    compression=compression,
                    encryption=encryption,
                    retention_days=retention_days,
                    custom_metadata=custom_metadata,
                    delete_source=delete_source,
                )
                if callback:
                    try:
                        callback(metadata, None)
                    except Exception:
                        pass
            except Exception as e:
                if callback:
                    try:
                        callback(None, e)
                    except Exception:
                        pass

        thread = threading.Thread(target=_archive_thread, daemon=True)
        thread.start()
        return archive_id

    def get_archive(self, archive_id: str) -> Optional[ArchiveMetadata]:
        with self._lock:
            return self._archives.get(archive_id)

    def list_archives(
        self,
        include_expired: bool = True,
        source_tier: Optional[str] = None,
    ) -> List[ArchiveMetadata]:
        with self._lock:
            archives = list(self._archives.values())

        if not include_expired:
            archives = [a for a in archives if not a.is_expired()]

        return sorted(archives, key=lambda a: a.created_at, reverse=True)

    def get_archive_data(self, archive_id: str) -> Optional[Dict[str, bytes]]:
        metadata = self.get_archive(archive_id)
        if not metadata or not metadata.storage_path:
            return None

        archive_data = self.archive_storage.get(metadata.storage_path)
        if archive_data is None:
            return None

        if metadata.checksum:
            actual_checksum = self._calculate_checksum(archive_data)
            if actual_checksum != metadata.checksum:
                raise ValueError(f"Checksum mismatch for archive {archive_id}")

        if metadata.compression:
            archive_data = self._decompress_data(archive_data)

        return self._extract_tar_archive(archive_data)

    def restore_archive(
        self,
        archive_id: str,
        target_tier: StorageTier,
        keys_to_restore: Optional[List[str]] = None,
    ) -> RestoreTask:
        metadata = self.get_archive(archive_id)
        if not metadata:
            raise ValueError(f"Archive {archive_id} not found")

        task = RestoreTask(
            archive_id=archive_id,
            target_tier=target_tier,
            keys_to_restore=keys_to_restore,
            description=f"Restore archive {archive_id} to {target_tier.name}",
        )

        with self._lock:
            self._restore_tasks[task.task_id] = task

        task.status = ArchiveStatus.RESTORING

        try:
            files = self.get_archive_data(archive_id)
            if files is None:
                raise ValueError(f"Failed to retrieve archive data for {archive_id}")

            restore_keys = keys_to_restore or list(files.keys())
            total_keys = len(restore_keys)

            for i, key in enumerate(restore_keys):
                if key in files:
                    success = target_tier.put(key, files[key])
                    if success:
                        task.restored_keys.append(key)
                    else:
                        task.failed_keys.append(key)
                        task.error_messages.append(f"Failed to restore {key}")
                else:
                    task.failed_keys.append(key)
                    task.error_messages.append(f"Key {key} not found in archive")

                task.progress = ((i + 1) / total_keys) * 100 if total_keys > 0 else 100

            task.status = ArchiveStatus.RESTORED
            metadata.is_restored = True
            metadata.restored_at = datetime.now()
            with self._lock:
                self._save_metadata(metadata)

        except Exception as e:
            task.status = ArchiveStatus.FAILED
            task.error_messages.append(str(e))
        finally:
            task.completed_at = datetime.now()

        return task

    def restore_archive_async(
        self,
        archive_id: str,
        target_tier: StorageTier,
        keys_to_restore: Optional[List[str]] = None,
        callback: Optional[Callable[[RestoreTask], None]] = None,
    ) -> str:
        task = RestoreTask(
            archive_id=archive_id,
            target_tier=target_tier,
            keys_to_restore=keys_to_restore,
            description=f"Restore archive {archive_id} to {target_tier.name}",
        )

        with self._lock:
            self._restore_tasks[task.task_id] = task

        def _restore_thread():
            try:
                result = self.restore_archive(archive_id, target_tier, keys_to_restore)
                task.status = result.status
                task.restored_keys = result.restored_keys
                task.failed_keys = result.failed_keys
                task.error_messages = result.error_messages
                task.progress = result.progress
                task.completed_at = result.completed_at
            except Exception as e:
                task.status = ArchiveStatus.FAILED
                task.error_messages.append(str(e))
                task.completed_at = datetime.now()
            finally:
                if callback:
                    try:
                        callback(task)
                    except Exception:
                        pass

        thread = threading.Thread(target=_restore_thread, daemon=True)
        thread.start()
        return task.task_id

    def get_restore_task(self, task_id: str) -> Optional[RestoreTask]:
        with self._lock:
            return self._restore_tasks.get(task_id)

    def list_restore_tasks(
        self,
        status: Optional[ArchiveStatus] = None,
        archive_id: Optional[str] = None,
    ) -> List[RestoreTask]:
        with self._lock:
            tasks = list(self._restore_tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]
        if archive_id:
            tasks = [t for t in tasks if t.archive_id == archive_id]

        return sorted(tasks, key=lambda t: t.created_at, reverse=True)

    def delete_archive(self, archive_id: str) -> bool:
        metadata = self.get_archive(archive_id)
        if not metadata:
            return False

        if not self.archive_storage.can_delete(metadata.archive_id):
            return False

        if metadata.storage_path:
            self.archive_storage.delete(metadata.storage_path)

        metadata_file = self.metadata_store_path / f"{archive_id}.json"
        if metadata_file.exists():
            metadata_file.unlink()

        with self._lock:
            if archive_id in self._archives:
                del self._archives[archive_id]

        return True

    def get_expired_archives(self) -> List[ArchiveMetadata]:
        return [a for a in self._archives.values() if a.is_expired()]

    def cleanup_expired_archives(self) -> int:
        expired = self.get_expired_archives()
        deleted_count = 0
        for archive in expired:
            if self.delete_archive(archive.archive_id):
                deleted_count += 1
        return deleted_count

    def get_archive_statistics(self) -> Dict[str, Any]:
        with self._lock:
            archives = list(self._archives.values())

        total_size = sum(a.size_bytes or 0 for a in archives)
        expired_count = len([a for a in archives if a.is_expired()])
        restored_count = len([a for a in archives if a.is_restored])

        return {
            "total_archives": len(archives),
            "total_size_bytes": total_size,
            "expired_count": expired_count,
            "restored_count": restored_count,
            "by_source_key_count": {},
            "average_size_bytes": total_size / len(archives) if archives else 0,
        }

    def verify_archive_integrity(self, archive_id: str) -> Tuple[bool, Optional[str]]:
        metadata = self.get_archive(archive_id)
        if not metadata or not metadata.storage_path or not metadata.checksum:
            return False, "Archive metadata incomplete"

        archive_data = self.archive_storage.get(metadata.storage_path)
        if archive_data is None:
            return False, "Archive data not found"

        actual_checksum = self._calculate_checksum(archive_data)
        if actual_checksum != metadata.checksum:
            return False, f"Checksum mismatch: expected {metadata.checksum}, got {actual_checksum}"

        try:
            if metadata.compression:
                decompressed = self._decompress_data(archive_data)
            else:
                decompressed = archive_data
            self._extract_tar_archive(decompressed)
            return True, None
        except Exception as e:
            return False, f"Archive structure corrupted: {str(e)}"
