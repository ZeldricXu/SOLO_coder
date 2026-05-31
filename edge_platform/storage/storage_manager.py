import os
import io
import json
import hashlib
import logging
import mimetypes
import uuid
from abc import ABC, abstractmethod
from typing import Dict, List, Optional, Any, Iterator
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
import threading

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import StorageException, ObjectNotFoundException

logger = logging.getLogger(__name__)


@dataclass
class ObjectMetadata:
    key: str = ""
    size: int = 0
    content_type: str = "application/octet-stream"
    md5_hash: str = ""
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)
    tags: Dict[str, str] = field(default_factory=dict)
    custom_metadata: Dict[str, str] = field(default_factory=dict)


@dataclass
class StoredObject:
    key: str = ""
    data: bytes = b""
    metadata: ObjectMetadata = field(default_factory=ObjectMetadata)


class ObjectStorageAdapter(ABC):
    @abstractmethod
    def put_object(
        self,
        key: str,
        data: bytes,
        content_type: Optional[str] = None,
        metadata: Optional[Dict[str, str]] = None
    ) -> ObjectMetadata:
        pass

    @abstractmethod
    def get_object(self, key: str) -> StoredObject:
        pass

    @abstractmethod
    def delete_object(self, key: str) -> None:
        pass

    @abstractmethod
    def list_objects(
        self,
        prefix: str = "",
        limit: int = 100
    ) -> List[ObjectMetadata]:
        pass

    @abstractmethod
    def object_exists(self, key: str) -> bool:
        pass

    @abstractmethod
    def get_metadata(self, key: str) -> ObjectMetadata:
        pass


class LocalStorageAdapter(ObjectStorageAdapter):
    def __init__(self, base_path: str):
        self._base_path = Path(base_path)
        self._base_path.mkdir(parents=True, exist_ok=True)
        self._metadata_path = self._base_path / ".metadata"
        self._metadata_path.mkdir(exist_ok=True)

    def _get_object_path(self, key: str) -> Path:
        safe_key = key.replace("/", os.sep)
        return self._base_path / safe_key

    def _get_metadata_path(self, key: str) -> Path:
        safe_key = key.replace("/", "_")
        return self._metadata_path / f"{safe_key}.json"

    def _calculate_md5(self, data: bytes) -> str:
        return hashlib.md5(data).hexdigest()

    def _save_metadata(self, metadata: ObjectMetadata) -> None:
        meta_path = self._get_metadata_path(metadata.key)
        meta_dict = {
            "key": metadata.key,
            "size": metadata.size,
            "content_type": metadata.content_type,
            "md5_hash": metadata.md5_hash,
            "created_at": metadata.created_at.isoformat(),
            "updated_at": metadata.updated_at.isoformat(),
            "tags": metadata.tags,
            "custom_metadata": metadata.custom_metadata
        }
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta_dict, f)

    def _load_metadata(self, key: str) -> ObjectMetadata:
        meta_path = self._get_metadata_path(key)
        if not meta_path.exists():
            raise ObjectNotFoundException(f"Metadata for {key} not found")

        with open(meta_path, "r", encoding="utf-8") as f:
            meta_dict = json.load(f)

        return ObjectMetadata(
            key=meta_dict["key"],
            size=meta_dict["size"],
            content_type=meta_dict["content_type"],
            md5_hash=meta_dict["md5_hash"],
            created_at=datetime.fromisoformat(meta_dict["created_at"]),
            updated_at=datetime.fromisoformat(meta_dict["updated_at"]),
            tags=meta_dict.get("tags", {}),
            custom_metadata=meta_dict.get("custom_metadata", {})
        )

    def put_object(
        self,
        key: str,
        data: bytes,
        content_type: Optional[str] = None,
        metadata: Optional[Dict[str, str]] = None
    ) -> ObjectMetadata:
        object_path = self._get_object_path(key)
        object_path.parent.mkdir(parents=True, exist_ok=True)

        with open(object_path, "wb") as f:
            f.write(data)

        if content_type is None:
            content_type, _ = mimetypes.guess_type(key)
            content_type = content_type or "application/octet-stream"

        obj_metadata = ObjectMetadata(
            key=key,
            size=len(data),
            content_type=content_type,
            md5_hash=self._calculate_md5(data),
            custom_metadata=metadata or {}
        )

        if self._get_metadata_path(key).exists():
            existing = self._load_metadata(key)
            obj_metadata.created_at = existing.created_at

        self._save_metadata(obj_metadata)
        return obj_metadata

    def get_object(self, key: str) -> StoredObject:
        object_path = self._get_object_path(key)
        if not object_path.exists():
            raise ObjectNotFoundException(f"Object {key} not found")

        with open(object_path, "rb") as f:
            data = f.read()

        metadata = self._load_metadata(key)
        return StoredObject(key=key, data=data, metadata=metadata)

    def delete_object(self, key: str) -> None:
        object_path = self._get_object_path(key)
        meta_path = self._get_metadata_path(key)

        if object_path.exists():
            object_path.unlink()
        if meta_path.exists():
            meta_path.unlink()

    def list_objects(
        self,
        prefix: str = "",
        limit: int = 100
    ) -> List[ObjectMetadata]:
        objects = []
        prefix_path = prefix.replace("/", os.sep) if prefix else ""
        search_path = self._base_path / prefix_path if prefix else self._base_path

        if search_path.is_file():
            try:
                objects.append(self._load_metadata(prefix))
            except Exception:
                pass
        elif search_path.is_dir():
            for root, _, files in os.walk(search_path):
                for file in files:
                    if file.startswith("."):
                        continue
                    full_path = Path(root) / file
                    rel_path = full_path.relative_to(self._base_path)
                    key = str(rel_path).replace(os.sep, "/")
                    try:
                        objects.append(self._load_metadata(key))
                        if len(objects) >= limit:
                            return objects
                    except Exception:
                        continue

        objects.sort(key=lambda o: o.created_at, reverse=True)
        return objects[:limit]

    def object_exists(self, key: str) -> bool:
        return self._get_object_path(key).exists()

    def get_metadata(self, key: str) -> ObjectMetadata:
        return self._load_metadata(key)


class S3StorageAdapter(ObjectStorageAdapter):
    def __init__(
        self,
        endpoint_url: str,
        access_key: str,
        secret_key: str,
        bucket: str
    ):
        try:
            import boto3
            self._s3 = boto3.client(
                "s3",
                endpoint_url=endpoint_url,
                aws_access_key_id=access_key,
                aws_secret_access_key=secret_key
            )
            self._bucket = bucket
        except ImportError:
            raise StorageException("boto3 is required for S3 storage")

    def put_object(
        self,
        key: str,
        data: bytes,
        content_type: Optional[str] = None,
        metadata: Optional[Dict[str, str]] = None
    ) -> ObjectMetadata:
        if content_type is None:
            content_type, _ = mimetypes.guess_type(key)
            content_type = content_type or "application/octet-stream"

        self._s3.put_object(
            Bucket=self._bucket,
            Key=key,
            Body=data,
            ContentType=content_type,
            Metadata=metadata or {}
        )

        response = self._s3.head_object(Bucket=self._bucket, Key=key)
        return ObjectMetadata(
            key=key,
            size=response["ContentLength"],
            content_type=response["ContentType"],
            md5_hash=response["ETag"].strip('"'),
            created_at=response["LastModified"],
            updated_at=response["LastModified"],
            custom_metadata=response.get("Metadata", {})
        )

    def get_object(self, key: str) -> StoredObject:
        try:
            response = self._s3.get_object(Bucket=self._bucket, Key=key)
            data = response["Body"].read()
            metadata = ObjectMetadata(
                key=key,
                size=response["ContentLength"],
                content_type=response["ContentType"],
                md5_hash=response["ETag"].strip('"'),
                created_at=response["LastModified"],
                updated_at=response["LastModified"],
                custom_metadata=response.get("Metadata", {})
            )
            return StoredObject(key=key, data=data, metadata=metadata)
        except self._s3.exceptions.NoSuchKey:
            raise ObjectNotFoundException(f"Object {key} not found")

    def delete_object(self, key: str) -> None:
        self._s3.delete_object(Bucket=self._bucket, Key=key)

    def list_objects(
        self,
        prefix: str = "",
        limit: int = 100
    ) -> List[ObjectMetadata]:
        response = self._s3.list_objects_v2(
            Bucket=self._bucket,
            Prefix=prefix,
            MaxKeys=limit
        )

        objects = []
        for obj in response.get("Contents", []):
            objects.append(ObjectMetadata(
                key=obj["Key"],
                size=obj["Size"],
                md5_hash=obj["ETag"].strip('"'),
                created_at=obj["LastModified"],
                updated_at=obj["LastModified"]
            ))

        return objects

    def object_exists(self, key: str) -> bool:
        try:
            self._s3.head_object(Bucket=self._bucket, Key=key)
            return True
        except self._s3.exceptions.ClientError:
            return False

    def get_metadata(self, key: str) -> ObjectMetadata:
        try:
            response = self._s3.head_object(Bucket=self._bucket, Key=key)
            return ObjectMetadata(
                key=key,
                size=response["ContentLength"],
                content_type=response["ContentType"],
                md5_hash=response["ETag"].strip('"'),
                created_at=response["LastModified"],
                updated_at=response["LastModified"],
                custom_metadata=response.get("Metadata", {})
            )
        except self._s3.exceptions.NoSuchKey:
            raise ObjectNotFoundException(f"Object {key} not found")


class StorageManager:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._adapter: Optional[ObjectStorageAdapter] = None
        self._metadata_index: Dict[str, ObjectMetadata] = {}
        self._tag_index: Dict[str, List[str]] = {}
        self._lock = threading.RLock()
        self._initialize_adapter()

    def _initialize_adapter(self) -> None:
        provider = config.get("storage.provider", "local")

        if provider == "local":
            base_path = config.get("storage.local.base_path", "./data/storage")
            self._adapter = LocalStorageAdapter(base_path)
        elif provider == "s3":
            endpoint_url = config.get("storage.s3.endpoint_url", "")
            access_key = config.get("storage.s3.access_key", "")
            secret_key = config.get("storage.s3.secret_key", "")
            bucket = config.get("storage.s3.bucket", "")
            self._adapter = S3StorageAdapter(endpoint_url, access_key, secret_key, bucket)
        else:
            raise StorageException(f"Unsupported storage provider: {provider}")

        logger.info(f"Initialized {provider} storage adapter")

    def put_object(
        self,
        key: str,
        data: bytes,
        content_type: Optional[str] = None,
        metadata: Optional[Dict[str, str]] = None,
        tags: Optional[Dict[str, str]] = None
    ) -> ObjectMetadata:
        if not self._adapter:
            raise StorageException("Storage adapter not initialized")

        obj_metadata = self._adapter.put_object(key, data, content_type, metadata)

        if tags:
            obj_metadata.tags = tags

        with self._lock:
            self._metadata_index[key] = obj_metadata
            if tags:
                for tag_key, tag_value in tags.items():
                    tag_index_key = f"{tag_key}:{tag_value}"
                    if tag_index_key not in self._tag_index:
                        self._tag_index[tag_index_key] = []
                    if key not in self._tag_index[tag_index_key]:
                        self._tag_index[tag_index_key].append(key)

        self._event_bus.publish(Event(
            event_type="storage.object.created",
            source="storage",
            payload={
                "key": key,
                "size": obj_metadata.size,
                "content_type": obj_metadata.content_type
            }
        ))

        return obj_metadata

    def get_object(self, key: str) -> StoredObject:
        if not self._adapter:
            raise StorageException("Storage adapter not initialized")

        obj = self._adapter.get_object(key)

        self._event_bus.publish(Event(
            event_type="storage.object.accessed",
            source="storage",
            payload={"key": key}
        ))

        return obj

    def delete_object(self, key: str) -> None:
        if not self._adapter:
            raise StorageException("Storage adapter not initialized")

        self._adapter.delete_object(key)

        with self._lock:
            if key in self._metadata_index:
                metadata = self._metadata_index[key]
                for tag_key, tag_value in metadata.tags.items():
                    tag_index_key = f"{tag_key}:{tag_value}"
                    if tag_index_key in self._tag_index:
                        if key in self._tag_index[tag_index_key]:
                            self._tag_index[tag_index_key].remove(key)
                del self._metadata_index[key]

        self._event_bus.publish(Event(
            event_type="storage.object.deleted",
            source="storage",
            payload={"key": key}
        ))

    def list_objects(
        self,
        prefix: str = "",
        limit: int = 100
    ) -> List[ObjectMetadata]:
        if not self._adapter:
            raise StorageException("Storage adapter not initialized")

        return self._adapter.list_objects(prefix, limit)

    def find_by_tag(self, tag_key: str, tag_value: str) -> List[ObjectMetadata]:
        with self._lock:
            tag_index_key = f"{tag_key}:{tag_value}"
            keys = self._tag_index.get(tag_index_key, [])
            return [self._metadata_index[key] for key in keys if key in self._metadata_index]

    def object_exists(self, key: str) -> bool:
        if not self._adapter:
            raise StorageException("Storage adapter not initialized")

        return self._adapter.object_exists(key)

    def get_metadata(self, key: str) -> ObjectMetadata:
        if not self._adapter:
            raise StorageException("Storage adapter not initialized")

        return self._adapter.get_metadata(key)

    def copy_object(self, source_key: str, destination_key: str) -> ObjectMetadata:
        obj = self.get_object(source_key)
        return self.put_object(
            destination_key,
            obj.data,
            obj.metadata.content_type,
            obj.metadata.custom_metadata,
            obj.metadata.tags
        )

    def move_object(self, source_key: str, destination_key: str) -> ObjectMetadata:
        obj = self.get_object(source_key)
        metadata = self.put_object(
            destination_key,
            obj.data,
            obj.metadata.content_type,
            obj.metadata.custom_metadata,
            obj.metadata.tags
        )
        self.delete_object(source_key)
        return metadata

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            indexed_count = len(self._metadata_index)
            total_size = sum(m.size for m in self._metadata_index.values())
            tag_count = len(self._tag_index)

        return {
            "indexed_objects": indexed_count,
            "total_size_bytes": total_size,
            "total_size_mb": total_size / (1024 * 1024),
            "tag_index_entries": tag_count
        }

    def rebuild_index(self) -> None:
        if not self._adapter:
            raise StorageException("Storage adapter not initialized")

        with self._lock:
            self._metadata_index.clear()
            self._tag_index.clear()

            all_objects = self._adapter.list_objects(limit=10000)
            for obj_meta in all_objects:
                self._metadata_index[obj_meta.key] = obj_meta
                for tag_key, tag_value in obj_meta.tags.items():
                    tag_index_key = f"{tag_key}:{tag_value}"
                    if tag_index_key not in self._tag_index:
                        self._tag_index[tag_index_key] = []
                    self._tag_index[tag_index_key].append(obj_meta.key)

        logger.info(f"Rebuilt index with {len(self._metadata_index)} objects")
