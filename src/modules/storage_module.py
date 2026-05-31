import os
import io
import json
import gzip
import shutil
import hashlib
import asyncio
from abc import ABC, abstractmethod
from pathlib import Path
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, AsyncIterator, Tuple
from enum import Enum
from dataclasses import dataclass

from .logging_module import get_logger
from .config_module import get_app_config

logger = get_logger(__name__)


class StorageBackendType(str, Enum):
    LOCAL = "local"
    S3 = "s3"
    MEMORY = "memory"


class BackupStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    PARTIAL = "partial"


@dataclass
class BackupInfo:
    backup_id: str
    name: str
    status: BackupStatus
    size_bytes: int
    created_at: datetime
    checksum: Optional[str] = None
    storage_path: Optional[str] = None
    metadata: Dict[str, Any] = None


@dataclass
class StorageObject:
    key: str
    size: int
    last_modified: datetime
    etag: Optional[str] = None
    metadata: Dict[str, Any] = None


class StorageBackend(ABC):
    @abstractmethod
    async def save(self, key: str, data: bytes, metadata: Optional[Dict[str, Any]] = None) -> str:
        pass

    @abstractmethod
    async def load(self, key: str) -> Optional[bytes]:
        pass

    @abstractmethod
    async def delete(self, key: str) -> bool:
        pass

    @abstractmethod
    async def exists(self, key: str) -> bool:
        pass

    @abstractmethod
    async def list_objects(self, prefix: str = "") -> List[StorageObject]:
        pass

    @abstractmethod
    async def get_url(self, key: str, expires_in: int = 3600) -> str:
        pass


class LocalStorageBackend(StorageBackend):
    def __init__(self, base_path: str = "./data"):
        self.base_path = Path(base_path)
        self.base_path.mkdir(parents=True, exist_ok=True)

    def _get_full_path(self, key: str) -> Path:
        safe_key = key.replace("..", "").lstrip("/")
        return self.base_path / safe_key

    async def save(self, key: str, data: bytes, metadata: Optional[Dict[str, Any]] = None) -> str:
        full_path = self._get_full_path(key)
        full_path.parent.mkdir(parents=True, exist_ok=True)

        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, full_path.write_bytes, data)

        if metadata:
            meta_path = full_path.with_suffix(full_path.suffix + ".meta")
            await loop.run_in_executor(
                None,
                meta_path.write_text,
                json.dumps(metadata, indent=2)
            )

        return str(full_path)

    async def load(self, key: str) -> Optional[bytes]:
        full_path = self._get_full_path(key)
        if not full_path.exists():
            return None

        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, full_path.read_bytes)

    async def delete(self, key: str) -> bool:
        full_path = self._get_full_path(key)
        if full_path.exists():
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, full_path.unlink)
            return True
        return False

    async def exists(self, key: str) -> bool:
        return self._get_full_path(key).exists()

    async def list_objects(self, prefix: str = "") -> List[StorageObject]:
        full_prefix = self._get_full_path(prefix)
        objects: List[StorageObject] = []

        if not full_prefix.exists():
            return objects

        loop = asyncio.get_event_loop()

        def scan_directory():
            results = []
            for path in full_prefix.rglob("*"):
                if path.is_file() and not path.name.endswith(".meta"):
                    stat = path.stat()
                    results.append(StorageObject(
                        key=str(path.relative_to(self.base_path)),
                        size=stat.st_size,
                        last_modified=datetime.fromtimestamp(stat.st_mtime),
                        etag=hashlib.md5(path.read_bytes()).hexdigest(),
                    ))
            return results

        objects = await loop.run_in_executor(None, scan_directory)
        return objects

    async def get_url(self, key: str, expires_in: int = 3600) -> str:
        return f"file://{self._get_full_path(key).absolute()}"


class MemoryStorageBackend(StorageBackend):
    def __init__(self):
        self._store: Dict[str, Tuple[bytes, Dict[str, Any], datetime]] = {}

    async def save(self, key: str, data: bytes, metadata: Optional[Dict[str, Any]] = None) -> str:
        self._store[key] = (data, metadata or {}, datetime.utcnow())
        return key

    async def load(self, key: str) -> Optional[bytes]:
        data = self._store.get(key)
        return data[0] if data else None

    async def delete(self, key: str) -> bool:
        if key in self._store:
            del self._store[key]
            return True
        return False

    async def exists(self, key: str) -> bool:
        return key in self._store

    async def list_objects(self, prefix: str = "") -> List[StorageObject]:
        objects = []
        for key, (data, meta, ts) in self._store.items():
            if key.startswith(prefix):
                objects.append(StorageObject(
                    key=key,
                    size=len(data),
                    last_modified=ts,
                ))
        return objects

    async def get_url(self, key: str, expires_in: int = 3600) -> str:
        return f"memory://{key}"


class S3StorageBackend(StorageBackend):
    def __init__(
        self,
        bucket: str,
        region: str = "us-east-1",
        access_key: Optional[str] = None,
        secret_key: Optional[str] = None,
    ):
        try:
            import aioboto3
            self._aioboto3 = aioboto3
        except ImportError:
            logger.warning("aioboto3 not installed, S3 backend may not work")
            self._aioboto3 = None

        self.bucket = bucket
        self.region = region
        self.access_key = access_key
        self.secret_key = secret_key

    async def save(self, key: str, data: bytes, metadata: Optional[Dict[str, Any]] = None) -> str:
        if not self._aioboto3:
            raise ImportError("aioboto3 is required for S3 storage")

        session = self._aioboto3.Session(
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
        )
        async with session.client("s3") as s3:
            extra_args = {"Metadata": metadata} if metadata else {}
            await s3.put_object(
                Bucket=self.bucket,
                Key=key,
                Body=data,
                **extra_args
            )
        return f"s3://{self.bucket}/{key}"

    async def load(self, key: str) -> Optional[bytes]:
        if not self._aioboto3:
            raise ImportError("aioboto3 is required for S3 storage")

        session = self._aioboto3.Session(
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
        )
        try:
            async with session.client("s3") as s3:
                response = await s3.get_object(Bucket=self.bucket, Key=key)
                return await response["Body"].read()
        except Exception as e:
            logger.error("S3 load error", key=key, error=str(e))
            return None

    async def delete(self, key: str) -> bool:
        if not self._aioboto3:
            raise ImportError("aioboto3 is required for S3 storage")

        session = self._aioboto3.Session(
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
        )
        try:
            async with session.client("s3") as s3:
                await s3.delete_object(Bucket=self.bucket, Key=key)
            return True
        except Exception as e:
            logger.error("S3 delete error", key=key, error=str(e))
            return False

    async def exists(self, key: str) -> bool:
        try:
            data = await self.load(key)
            return data is not None
        except Exception:
            return False

    async def list_objects(self, prefix: str = "") -> List[StorageObject]:
        if not self._aioboto3:
            raise ImportError("aioboto3 is required for S3 storage")

        session = self._aioboto3.Session(
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
        )
        objects: List[StorageObject] = []

        async with session.client("s3") as s3:
            paginator = s3.get_paginator("list_objects_v2")
            async for page in paginator.paginate(Bucket=self.bucket, Prefix=prefix):
                for obj in page.get("Contents", []):
                    objects.append(StorageObject(
                        key=obj["Key"],
                        size=obj["Size"],
                        last_modified=obj["LastModified"],
                        etag=obj.get("ETag", "").strip('"'),
                    ))
        return objects

    async def get_url(self, key: str, expires_in: int = 3600) -> str:
        if not self._aioboto3:
            raise ImportError("aioboto3 is required for S3 storage")

        session = self._aioboto3.Session(
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            region_name=self.region,
        )
        async with session.client("s3") as s3:
            url = await s3.generate_presigned_url(
                "get_object",
                Params={"Bucket": self.bucket, "Key": key},
                ExpiresIn=expires_in,
            )
        return url


class StorageManager:
    _instance: Optional['StorageManager'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, backend: Optional[StorageBackend] = None):
        if self._initialized:
            return

        config = get_app_config()
        self.backend_type = config.storage.backend

        if backend:
            self.backend = backend
        else:
            self.backend = self._create_backend(config.storage)

        self.backup_dir = Path(config.storage.local_path) / "backups"
        self.backup_dir.mkdir(parents=True, exist_ok=True)
        self._initialized = True

    def _create_backend(self, config) -> StorageBackend:
        backend_type = config.backend.lower()

        if backend_type == StorageBackendType.LOCAL:
            return LocalStorageBackend(base_path=config.local_path)
        elif backend_type == StorageBackendType.S3:
            return S3StorageBackend(
                bucket=config.s3_bucket,
                region=config.s3_region,
                access_key=config.s3_access_key,
                secret_key=config.s3_secret_key,
            )
        elif backend_type == StorageBackendType.MEMORY:
            return MemoryStorageBackend()
        else:
            raise ValueError(f"Unsupported storage backend: {backend_type}")

    async def save_data(self, key: str, data: Any, serialize: bool = True,
                        compress: bool = False) -> str:
        if serialize:
            if isinstance(data, (dict, list)):
                data = json.dumps(data, default=str).encode('utf-8')
            elif isinstance(data, str):
                data = data.encode('utf-8')

        if compress:
            data = gzip.compress(data)
            key = key + ".gz"

        return await self.backend.save(key, data)

    async def load_data(self, key: str, decompress: bool = False,
                        deserialize: bool = True) -> Optional[Any]:
        if decompress and not key.endswith(".gz"):
            key = key + ".gz"

        data = await self.backend.load(key)
        if data is None:
            return None

        if decompress:
            data = gzip.decompress(data)

        if deserialize:
            try:
                return json.loads(data.decode('utf-8'))
            except (json.JSONDecodeError, UnicodeDecodeError):
                return data

        return data

    async def delete_data(self, key: str) -> bool:
        return await self.backend.delete(key)

    async def exists(self, key: str) -> bool:
        return await self.backend.exists(key)

    async def list_data(self, prefix: str = "") -> List[StorageObject]:
        return await self.backend.list_objects(prefix)

    async def create_backup(
        self,
        source_path: str,
        backup_name: Optional[str] = None,
        include_metadata: bool = True,
    ) -> BackupInfo:
        backup_id = f"backup_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}_{os.urandom(4).hex()}"
        backup_name = backup_name or backup_id

        backup_info = BackupInfo(
            backup_id=backup_id,
            name=backup_name,
            status=BackupStatus.IN_PROGRESS,
            size_bytes=0,
            created_at=datetime.utcnow(),
        )

        try:
            source = Path(source_path)
            if not source.exists():
                raise FileNotFoundError(f"Source path not found: {source_path}")

            backup_file = self.backup_dir / f"{backup_name}.tar.gz"

            loop = asyncio.get_event_loop()

            def create_tar():
                import tarfile
                total_size = 0
                with tarfile.open(backup_file, "w:gz") as tar:
                    if source.is_file():
                        tar.add(source, arcname=source.name)
                        total_size = source.stat().st_size
                    else:
                        for item in source.rglob("*"):
                            if item.is_file():
                                tar.add(item, arcname=item.relative_to(source.parent))
                                total_size += item.stat().st_size
                return total_size

            total_size = await loop.run_in_executor(None, create_tar)
            backup_data = await loop.run_in_executor(None, backup_file.read_bytes)
            checksum = hashlib.sha256(backup_data).hexdigest()

            storage_key = f"backups/{backup_name}.tar.gz"
            storage_path = await self.backend.save(
                storage_key,
                backup_data,
                metadata={"checksum": checksum, "source": source_path} if include_metadata else None
            )

            backup_info.status = BackupStatus.COMPLETED
            backup_info.size_bytes = total_size
            backup_info.checksum = checksum
            backup_info.storage_path = storage_path
            backup_info.metadata = {"source_path": source_path}

            logger.info("Backup created successfully", backup_id=backup_id, size=total_size)

        except Exception as e:
            backup_info.status = BackupStatus.FAILED
            backup_info.error_detail = str(e)
            logger.error("Backup creation failed", backup_id=backup_id, error=str(e))
            raise

        return backup_info

    async def restore_backup(
        self,
        backup_id: str,
        destination_path: str,
        overwrite: bool = False,
    ) -> bool:
        try:
            backup_key = f"backups/{backup_id}.tar.gz"
            backup_data = await self.backend.load(backup_key)

            if not backup_data:
                raise FileNotFoundError(f"Backup not found: {backup_id}")

            dest = Path(destination_path)
            if dest.exists() and not overwrite:
                raise FileExistsError(f"Destination exists and overwrite is False: {destination_path}")

            dest.parent.mkdir(parents=True, exist_ok=True)
            temp_file = dest.parent / f"{backup_id}.temp.tar.gz"

            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, temp_file.write_bytes, backup_data)

            def extract_tar():
                import tarfile
                with tarfile.open(temp_file, "r:gz") as tar:
                    tar.extractall(dest.parent)

            await loop.run_in_executor(None, extract_tar)
            await loop.run_in_executor(None, temp_file.unlink)

            logger.info("Backup restored successfully", backup_id=backup_id, destination=destination_path)
            return True

        except Exception as e:
            logger.error("Backup restoration failed", backup_id=backup_id, error=str(e))
            raise

    async def list_backups(self, limit: int = 100) -> List[BackupInfo]:
        objects = await self.backend.list_objects("backups/")
        backups: List[BackupInfo] = []

        for obj in objects:
            if obj.key.endswith(".tar.gz"):
                name = Path(obj.key).stem.replace(".tar", "")
                backups.append(BackupInfo(
                    backup_id=name,
                    name=name,
                    status=BackupStatus.COMPLETED,
                    size_bytes=obj.size,
                    created_at=obj.last_modified,
                    storage_path=obj.key,
                ))

        backups.sort(key=lambda b: b.created_at, reverse=True)
        return backups[:limit]

    async def delete_backup(self, backup_id: str) -> bool:
        backup_key = f"backups/{backup_id}.tar.gz"
        return await self.backend.delete(backup_key)

    async def cleanup_old_backups(self, retention_days: int = 30) -> int:
        cutoff = datetime.utcnow() - timedelta(days=retention_days)
        backups = await self.list_backups()
        deleted_count = 0

        for backup in backups:
            if backup.created_at < cutoff:
                if await self.delete_backup(backup.backup_id):
                    deleted_count += 1

        logger.info("Old backups cleaned up", deleted=deleted_count, retention_days=retention_days)
        return deleted_count

    async def get_file_url(self, key: str, expires_in: int = 3600) -> str:
        return await self.backend.get_url(key, expires_in)


def get_storage_manager() -> StorageManager:
    return StorageManager()
