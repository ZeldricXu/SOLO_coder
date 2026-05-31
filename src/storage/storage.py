import io
import json
import os
import pickle
import shutil
import sqlite3
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, AsyncGenerator, BinaryIO, Dict, Iterator, List, Optional, Union

import boto3
from botocore.client import Config as BotoConfig
from minio import Minio

from src.config import get_settings
from src.logging_ import get_logger
from src.utils.errors import StorageError
from src.utils.helpers import generate_id

logger = get_logger(__name__)


@dataclass
class StorageObject:
    object_id: str = field(default_factory=lambda: generate_id("obj"))
    key: str
    bucket: str
    size: int = 0
    content_type: str = "application/octet-stream"
    etag: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)
    tags: Dict[str, str] = field(default_factory=dict)
    version_id: Optional[str] = None


class ObjectStorageAdapter(ABC):
    @abstractmethod
    async def upload(
        self,
        key: str,
        data: Union[bytes, str, BinaryIO],
        metadata: Optional[Dict[str, Any]] = None,
        content_type: Optional[str] = None,
    ) -> StorageObject:
        pass

    @abstractmethod
    async def download(self, key: str) -> bytes:
        pass

    @abstractmethod
    async def delete(self, key: str) -> bool:
        pass

    @abstractmethod
    async def exists(self, key: str) -> bool:
        pass

    @abstractmethod
    async def list_objects(
        self,
        prefix: Optional[str] = None,
        limit: int = 1000,
    ) -> List[StorageObject]:
        pass

    @abstractmethod
    async def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        pass

    @abstractmethod
    async def generate_presigned_url(
        self,
        key: str,
        expires_in: int = 3600,
    ) -> str:
        pass


class S3StorageAdapter(ObjectStorageAdapter):
    def __init__(
        self,
        endpoint_url: str,
        access_key: str,
        secret_key: str,
        bucket: str,
        region: str = "us-east-1",
    ):
        self.endpoint_url = endpoint_url
        self.bucket = bucket
        self.region = region
        self._client = boto3.client(
            "s3",
            endpoint_url=endpoint_url,
            aws_access_key_id=access_key,
            aws_secret_access_key=secret_key,
            region_name=region,
            config=BotoConfig(signature_version="s3v4"),
        )
        self._ensure_bucket_exists()

    def _ensure_bucket_exists(self) -> None:
        try:
            self._client.head_bucket(Bucket=self.bucket)
        except Exception:
            logger.info("Creating bucket: %s", self.bucket)
            self._client.create_bucket(
                Bucket=self.bucket,
                CreateBucketConfiguration={"LocationConstraint": self.region},
            )

    async def upload(
        self,
        key: str,
        data: Union[bytes, str, BinaryIO],
        metadata: Optional[Dict[str, Any]] = None,
        content_type: Optional[str] = None,
    ) -> StorageObject:
        try:
            if isinstance(data, str):
                data = data.encode("utf-8")

            if isinstance(data, bytes):
                fileobj = io.BytesIO(data)
                size = len(data)
            else:
                fileobj = data
                fileobj.seek(0, 2)
                size = fileobj.tell()
                fileobj.seek(0)

            extra_args = {}
            if metadata:
                extra_args["Metadata"] = {k: str(v) for k, v in metadata.items()}
            if content_type:
                extra_args["ContentType"] = content_type

            self._client.upload_fileobj(
                Fileobj=fileobj,
                Bucket=self.bucket,
                Key=key,
                ExtraArgs=extra_args if extra_args else None,
            )

            response = self._client.head_object(Bucket=self.bucket, Key=key)

            return StorageObject(
                key=key,
                bucket=self.bucket,
                size=size,
                content_type=content_type or "application/octet-stream",
                etag=response.get("ETag", "").strip('"'),
                metadata=metadata or {},
            )

        except Exception as e:
            raise StorageError(f"Failed to upload {key}: {e}") from e

    async def download(self, key: str) -> bytes:
        try:
            buffer = io.BytesIO()
            self._client.download_fileobj(Bucket=self.bucket, Key=key, Fileobj=buffer)
            return buffer.getvalue()
        except Exception as e:
            raise StorageError(f"Failed to download {key}: {e}") from e

    async def delete(self, key: str) -> bool:
        try:
            self._client.delete_object(Bucket=self.bucket, Key=key)
            return True
        except Exception as e:
            raise StorageError(f"Failed to delete {key}: {e}") from e

    async def exists(self, key: str) -> bool:
        try:
            self._client.head_object(Bucket=self.bucket, Key=key)
            return True
        except Exception:
            return False

    async def list_objects(
        self,
        prefix: Optional[str] = None,
        limit: int = 1000,
    ) -> List[StorageObject]:
        try:
            kwargs = {"Bucket": self.bucket, "MaxKeys": limit}
            if prefix:
                kwargs["Prefix"] = prefix

            response = self._client.list_objects_v2(**kwargs)
            objects: List[StorageObject] = []

            for obj in response.get("Contents", []):
                objects.append(
                    StorageObject(
                        key=obj["Key"],
                        bucket=self.bucket,
                        size=obj["Size"],
                        etag=obj.get("ETag", "").strip('"'),
                        created_at=obj["LastModified"],
                    )
                )

            return objects

        except Exception as e:
            raise StorageError(f"Failed to list objects: {e}") from e

    async def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        try:
            response = self._client.head_object(Bucket=self.bucket, Key=key)
            return {
                "size": response["ContentLength"],
                "content_type": response["ContentType"],
                "etag": response.get("ETag", "").strip('"'),
                "metadata": response.get("Metadata", {}),
                "last_modified": response["LastModified"],
            }
        except Exception as e:
            raise StorageError(f"Failed to get metadata for {key}: {e}") from e

    async def generate_presigned_url(
        self,
        key: str,
        expires_in: int = 3600,
    ) -> str:
        try:
            return self._client.generate_presigned_url(
                "get_object",
                Params={"Bucket": self.bucket, "Key": key},
                ExpiresIn=expires_in,
            )
        except Exception as e:
            raise StorageError(f"Failed to generate presigned URL: {e}") from e


class MinIOStorageAdapter(ObjectStorageAdapter):
    def __init__(
        self,
        endpoint: str,
        access_key: str,
        secret_key: str,
        bucket: str,
        secure: bool = False,
    ):
        self.bucket = bucket
        self._client = Minio(
            endpoint=endpoint,
            access_key=access_key,
            secret_key=secret_key,
            secure=secure,
        )
        self._ensure_bucket_exists()

    def _ensure_bucket_exists(self) -> None:
        try:
            if not self._client.bucket_exists(self.bucket):
                logger.info("Creating bucket: %s", self.bucket)
                self._client.make_bucket(self.bucket)
        except Exception as e:
            raise StorageError(f"Failed to check/create bucket: {e}") from e

    async def upload(
        self,
        key: str,
        data: Union[bytes, str, BinaryIO],
        metadata: Optional[Dict[str, Any]] = None,
        content_type: Optional[str] = None,
    ) -> StorageObject:
        try:
            if isinstance(data, str):
                data = data.encode("utf-8")

            if isinstance(data, bytes):
                fileobj = io.BytesIO(data)
                size = len(data)
            else:
                fileobj = data
                fileobj.seek(0, 2)
                size = fileobj.tell()
                fileobj.seek(0)

            result = self._client.put_object(
                bucket_name=self.bucket,
                object_name=key,
                data=fileobj,
                length=size,
                content_type=content_type or "application/octet-stream",
                metadata=metadata or {},
            )

            return StorageObject(
                key=key,
                bucket=self.bucket,
                size=size,
                content_type=content_type or "application/octet-stream",
                etag=result.etag,
                metadata=metadata or {},
                version_id=result.version_id,
            )

        except Exception as e:
            raise StorageError(f"Failed to upload {key}: {e}") from e

    async def download(self, key: str) -> bytes:
        try:
            response = self._client.get_object(
                bucket_name=self.bucket,
                object_name=key,
            )
            return response.read()
        except Exception as e:
            raise StorageError(f"Failed to download {key}: {e}") from e

    async def delete(self, key: str) -> bool:
        try:
            self._client.remove_object(bucket_name=self.bucket, object_name=key)
            return True
        except Exception as e:
            raise StorageError(f"Failed to delete {key}: {e}") from e

    async def exists(self, key: str) -> bool:
        try:
            self._client.stat_object(bucket_name=self.bucket, object_name=key)
            return True
        except Exception:
            return False

    async def list_objects(
        self,
        prefix: Optional[str] = None,
        limit: int = 1000,
    ) -> List[StorageObject]:
        try:
            objects = self._client.list_objects(
                bucket_name=self.bucket,
                prefix=prefix or "",
                recursive=True,
            )
            result: List[StorageObject] = []
            count = 0
            for obj in objects:
                if count >= limit:
                    break
                result.append(
                    StorageObject(
                        key=obj.object_name,
                        bucket=self.bucket,
                        size=obj.size,
                        etag=obj.etag,
                        created_at=obj.last_modified,
                    )
                )
                count += 1
            return result
        except Exception as e:
            raise StorageError(f"Failed to list objects: {e}") from e

    async def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        try:
            stat = self._client.stat_object(bucket_name=self.bucket, object_name=key)
            return {
                "size": stat.size,
                "content_type": stat.content_type,
                "etag": stat.etag,
                "metadata": stat.metadata,
                "last_modified": stat.last_modified,
                "version_id": stat.version_id,
            }
        except Exception as e:
            raise StorageError(f"Failed to get metadata for {key}: {e}") from e

    async def generate_presigned_url(
        self,
        key: str,
        expires_in: int = 3600,
    ) -> str:
        try:
            from datetime import timedelta

            return self._client.presigned_get_object(
                bucket_name=self.bucket,
                object_name=key,
                expires=timedelta(seconds=expires_in),
            )
        except Exception as e:
            raise StorageError(f"Failed to generate presigned URL: {e}") from e


class LocalStorageAdapter(ObjectStorageAdapter):
    def __init__(self, base_path: str = "./storage", bucket: str = "default"):
        self.base_path = Path(base_path)
        self.bucket = bucket
        self.bucket_path = self.base_path / bucket
        self.bucket_path.mkdir(parents=True, exist_ok=True)
        self._metadata_file = self.base_path / f"{bucket}_metadata.json"
        self._metadata: Dict[str, Dict[str, Any]] = self._load_metadata()

    def _load_metadata(self) -> Dict[str, Dict[str, Any]]:
        if self._metadata_file.exists():
            try:
                with open(self._metadata_file, "r") as f:
                    return json.load(f)
            except Exception:
                return {}
        return {}

    def _save_metadata(self) -> None:
        with open(self._metadata_file, "w") as f:
            json.dump(self._metadata, f, indent=2)

    def _get_file_path(self, key: str) -> Path:
        file_path = self.bucket_path / key
        file_path.parent.mkdir(parents=True, exist_ok=True)
        return file_path

    async def upload(
        self,
        key: str,
        data: Union[bytes, str, BinaryIO],
        metadata: Optional[Dict[str, Any]] = None,
        content_type: Optional[str] = None,
    ) -> StorageObject:
        try:
            file_path = self._get_file_path(key)

            if isinstance(data, str):
                data = data.encode("utf-8")

            if isinstance(data, bytes):
                fileobj = io.BytesIO(data)
                size = len(data)
            else:
                fileobj = data
                fileobj.seek(0, 2)
                size = fileobj.tell()
                fileobj.seek(0)

            with open(file_path, "wb") as f:
                shutil.copyfileobj(fileobj, f)

            etag = str(hash((key, size, time.time())))
            self._metadata[key] = {
                "size": size,
                "content_type": content_type or "application/octet-stream",
                "etag": etag,
                "metadata": metadata or {},
                "created_at": datetime.utcnow().isoformat(),
            }
            self._save_metadata()

            return StorageObject(
                key=key,
                bucket=self.bucket,
                size=size,
                content_type=content_type or "application/octet-stream",
                etag=etag,
                metadata=metadata or {},
            )

        except Exception as e:
            raise StorageError(f"Failed to upload {key}: {e}") from e

    async def download(self, key: str) -> bytes:
        try:
            file_path = self._get_file_path(key)
            if not file_path.exists():
                raise StorageError(f"Object not found: {key}")
            with open(file_path, "rb") as f:
                return f.read()
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(f"Failed to download {key}: {e}") from e

    async def delete(self, key: str) -> bool:
        try:
            file_path = self._get_file_path(key)
            if file_path.exists():
                file_path.unlink()
                self._metadata.pop(key, None)
                self._save_metadata()
                return True
            return False
        except Exception as e:
            raise StorageError(f"Failed to delete {key}: {e}") from e

    async def exists(self, key: str) -> bool:
        return self._get_file_path(key).exists()

    async def list_objects(
        self,
        prefix: Optional[str] = None,
        limit: int = 1000,
    ) -> List[StorageObject]:
        try:
            objects: List[StorageObject] = []
            search_path = self.bucket_path / (prefix or "")
            pattern = "**/*" if prefix else "*"

            for file_path in sorted(search_path.glob(pattern)):
                if len(objects) >= limit:
                    break
                if file_path.is_file():
                    rel_path = file_path.relative_to(self.bucket_path).as_posix()
                    meta = self._metadata.get(rel_path, {})
                    stat = file_path.stat()
                    objects.append(
                        StorageObject(
                            key=rel_path,
                            bucket=self.bucket,
                            size=stat.st_size,
                            etag=meta.get("etag", ""),
                            content_type=meta.get("content_type", "application/octet-stream"),
                            metadata=meta.get("metadata", {}),
                            created_at=datetime.fromtimestamp(stat.st_mtime),
                        )
                    )
            return objects
        except Exception as e:
            raise StorageError(f"Failed to list objects: {e}") from e

    async def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        if key not in self._metadata:
            return None
        return self._metadata[key].copy()

    async def generate_presigned_url(
        self,
        key: str,
        expires_in: int = 3600,
    ) -> str:
        return f"file://{self._get_file_path(key).absolute()}"


class MetadataIndex:
    def __init__(self, db_path: str = "./storage_metadata.db"):
        self.db_path = db_path
        self._conn: Optional[sqlite3.Connection] = None
        self._initialize()

    def _initialize(self) -> None:
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self.db_path)
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS metadata (
                object_id TEXT PRIMARY KEY,
                key TEXT NOT NULL,
                bucket TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                content_type TEXT,
                etag TEXT,
                metadata TEXT,
                tags TEXT,
                created_at TEXT,
                updated_at TEXT,
                UNIQUE(bucket, key)
            )
            """
        )
        self._conn.execute("CREATE INDEX IF NOT EXISTS idx_key ON metadata(key)")
        self._conn.execute("CREATE INDEX IF NOT EXISTS idx_bucket ON metadata(bucket)")
        self._conn.commit()

    def index_object(self, obj: StorageObject) -> None:
        if not self._conn:
            raise StorageError("Metadata index not initialized")

        self._conn.execute(
            """
            INSERT OR REPLACE INTO metadata
            (object_id, key, bucket, size, content_type, etag, metadata, tags, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                obj.object_id,
                obj.key,
                obj.bucket,
                obj.size,
                obj.content_type,
                obj.etag,
                json.dumps(obj.metadata),
                json.dumps(obj.tags),
                obj.created_at.isoformat(),
                obj.updated_at.isoformat(),
            ),
        )
        self._conn.commit()

    def search(
        self,
        key_pattern: Optional[str] = None,
        bucket: Optional[str] = None,
        tags: Optional[Dict[str, str]] = None,
        limit: int = 100,
    ) -> List[StorageObject]:
        if not self._conn:
            raise StorageError("Metadata index not initialized")

        query = "SELECT * FROM metadata WHERE 1=1"
        params: List[Any] = []

        if key_pattern:
            query += " AND key LIKE ?"
            params.append(f"%{key_pattern}%")

        if bucket:
            query += " AND bucket = ?"
            params.append(bucket)

        query += " ORDER BY created_at DESC LIMIT ?"
        params.append(limit)

        cursor = self._conn.execute(query, params)
        rows = cursor.fetchall()

        results: List[StorageObject] = []
        for row in rows:
            results.append(
                StorageObject(
                    object_id=row[0],
                    key=row[1],
                    bucket=row[2],
                    size=row[3],
                    content_type=row[4] or "application/octet-stream",
                    etag=row[5],
                    metadata=json.loads(row[6]) if row[6] else {},
                    tags=json.loads(row[7]) if row[7] else {},
                    created_at=datetime.fromisoformat(row[8]),
                    updated_at=datetime.fromisoformat(row[9]),
                )
            )
        return results

    def delete_index(self, object_id: str) -> None:
        if not self._conn:
            raise StorageError("Metadata index not initialized")
        self._conn.execute("DELETE FROM metadata WHERE object_id = ?", (object_id,))
        self._conn.commit()

    def close(self) -> None:
        if self._conn:
            self._conn.close()
            self._conn = None


class StorageManager:
    _instance: Optional["StorageManager"] = None

    def __new__(cls) -> "StorageManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if not hasattr(self, "initialized"):
            self.settings = get_settings()
            self._adapters: Dict[str, ObjectStorageAdapter] = {}
            self._metadata_index = MetadataIndex()
            self._default_adapter_name: str = "s3"
            self._initialize_adapters()
            self.initialized = True

    def _initialize_adapters(self) -> None:
        try:
            self._adapters["s3"] = S3StorageAdapter(
                endpoint_url=self.settings.S3_ENDPOINT,
                access_key=self.settings.S3_ACCESS_KEY,
                secret_key=self.settings.S3_SECRET_KEY,
                bucket=self.settings.S3_BUCKET,
                region=self.settings.S3_REGION,
            )
        except Exception as e:
            logger.warning("Failed to initialize S3 adapter: %s, using local storage", e)
            self._adapters["local"] = LocalStorageAdapter(bucket=self.settings.S3_BUCKET)
            self._default_adapter_name = "local"

        try:
            self._adapters["minio"] = MinIOStorageAdapter(
                endpoint=self.settings.S3_ENDPOINT.replace("http://", "").replace("https://", ""),
                access_key=self.settings.S3_ACCESS_KEY,
                secret_key=self.settings.S3_SECRET_KEY,
                bucket=self.settings.S3_BUCKET,
                secure=self.settings.S3_ENDPOINT.startswith("https"),
            )
        except Exception as e:
            logger.debug("Failed to initialize MinIO adapter: %s", e)

    def register_adapter(self, name: str, adapter: ObjectStorageAdapter) -> None:
        self._adapters[name] = adapter
        logger.info("Registered storage adapter: %s", name)

    def get_adapter(self, name: Optional[str] = None) -> ObjectStorageAdapter:
        adapter_name = name or self._default_adapter_name
        if adapter_name not in self._adapters:
            raise StorageError(f"Storage adapter not found: {adapter_name}")
        return self._adapters[adapter_name]

    def set_default_adapter(self, name: str) -> None:
        if name not in self._adapters:
            raise StorageError(f"Storage adapter not found: {name}")
        self._default_adapter_name = name

    async def upload(
        self,
        key: str,
        data: Union[bytes, str, BinaryIO],
        metadata: Optional[Dict[str, Any]] = None,
        content_type: Optional[str] = None,
        adapter_name: Optional[str] = None,
    ) -> StorageObject:
        adapter = self.get_adapter(adapter_name)
        obj = await adapter.upload(key, data, metadata, content_type)
        self._metadata_index.index_object(obj)
        logger.info("Uploaded object: %s to %s", key, adapter_name or self._default_adapter_name)
        return obj

    async def download(
        self,
        key: str,
        adapter_name: Optional[str] = None,
    ) -> bytes:
        adapter = self.get_adapter(adapter_name)
        return await adapter.download(key)

    async def delete(
        self,
        key: str,
        adapter_name: Optional[str] = None,
    ) -> bool:
        adapter = self.get_adapter(adapter_name)
        result = await adapter.delete(key)
        if result:
            self._metadata_index.delete_index(f"{adapter_name or self._default_adapter_name}:{key}")
        return result

    async def exists(
        self,
        key: str,
        adapter_name: Optional[str] = None,
    ) -> bool:
        adapter = self.get_adapter(adapter_name)
        return await adapter.exists(key)

    async def list_objects(
        self,
        prefix: Optional[str] = None,
        limit: int = 1000,
        adapter_name: Optional[str] = None,
    ) -> List[StorageObject]:
        adapter = self.get_adapter(adapter_name)
        return await adapter.list_objects(prefix, limit)

    def search_metadata(
        self,
        key_pattern: Optional[str] = None,
        bucket: Optional[str] = None,
        tags: Optional[Dict[str, str]] = None,
        limit: int = 100,
    ) -> List[StorageObject]:
        return self._metadata_index.search(key_pattern, bucket, tags, limit)

    async def generate_presigned_url(
        self,
        key: str,
        expires_in: int = 3600,
        adapter_name: Optional[str] = None,
    ) -> str:
        adapter = self.get_adapter(adapter_name)
        return await adapter.generate_presigned_url(key, expires_in)

    async def batch_upload(
        self,
        objects: List[Tuple[str, Union[bytes, str, BinaryIO], Optional[Dict[str, Any]]]],
        adapter_name: Optional[str] = None,
        max_concurrent: int = 5,
    ) -> List[StorageObject]:
        semaphore = asyncio.Semaphore(max_concurrent)

        async def _upload(obj: Tuple[str, Union[bytes, str, BinaryIO], Optional[Dict[str, Any]]]) -> StorageObject:
            async with semaphore:
                key, data, metadata = obj
                return await self.upload(key, data, metadata, adapter_name=adapter_name)

        tasks = [_upload(obj) for obj in objects]
        return await asyncio.gather(*tasks)

    def get_statistics(self) -> Dict[str, Any]:
        return {
            "adapters": list(self._adapters.keys()),
            "default_adapter": self._default_adapter_name,
            "index_path": self._metadata_index.db_path,
        }

    def close(self) -> None:
        self._metadata_index.close()
        logger.info("Storage manager closed")
