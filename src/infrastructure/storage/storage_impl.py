"""
存储实现
遵循 StorageProtocol 协议，提供内存、本地文件、S3 三种后端
"""

from __future__ import annotations

import os
import pickle
import shutil
import tempfile
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from uuid import uuid4

from src.core import StorageError, StorageProtocol


@dataclass
class _StorageObject:
    data: bytes
    metadata: Dict[str, Any] = field(default_factory=dict)


class MemoryStorage(StorageProtocol):
    """内存存储 - 主要用于测试"""

    def __init__(self) -> None:
        self._buckets: Dict[str, Dict[str, _StorageObject]] = {}

    def _ensure_bucket(self, bucket: str) -> None:
        if bucket not in self._buckets:
            self._buckets[bucket] = {}

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        try:
            self._ensure_bucket(bucket)
            self._buckets[bucket][key] = _StorageObject(
                data=data, metadata=metadata or {}
            )
            return f"memory://{bucket}/{key}"
        except Exception as e:
            raise StorageError(
                f"Failed to upload object: {e}", bucket=bucket, key=key
            ) from e

    async def download(self, bucket: str, key: str) -> bytes:
        try:
            self._ensure_bucket(bucket)
            if key not in self._buckets[bucket]:
                raise StorageError(
                    "Object not found", bucket=bucket, key=key
                )
            return self._buckets[bucket][key].data
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                f"Failed to download object: {e}", bucket=bucket, key=key
            ) from e

    async def delete(self, bucket: str, key: str) -> None:
        try:
            self._ensure_bucket(bucket)
            if key in self._buckets[bucket]:
                del self._buckets[bucket][key]
        except Exception as e:
            raise StorageError(
                f"Failed to delete object: {e}", bucket=bucket, key=key
            ) from e

    async def exists(self, bucket: str, key: str) -> bool:
        self._ensure_bucket(bucket)
        return key in self._buckets[bucket]

    async def list(
        self, bucket: str, prefix: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        try:
            self._ensure_bucket(bucket)
            results = []
            for key, obj in self._buckets[bucket].items():
                if prefix is None or key.startswith(prefix):
                    results.append(
                        {
                            "key": key,
                            "size": len(obj.data),
                            "metadata": obj.metadata,
                        }
                    )
            return results
        except Exception as e:
            raise StorageError(
                f"Failed to list objects: {e}", bucket=bucket
            ) from e

    async def get_metadata(self, bucket: str, key: str) -> Dict[str, Any]:
        try:
            self._ensure_bucket(bucket)
            if key not in self._buckets[bucket]:
                raise StorageError(
                    "Object not found", bucket=bucket, key=key
                )
            return self._buckets[bucket][key].metadata
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                f"Failed to get metadata: {e}", bucket=bucket, key=key
            ) from e


class LocalFileStorage(StorageProtocol):
    """本地文件系统存储"""

    def __init__(self, base_path: str) -> None:
        self.base_path = os.path.abspath(base_path)
        os.makedirs(self.base_path, exist_ok=True)

    def _get_path(self, bucket: str, key: str) -> str:
        safe_key = key.lstrip("/").lstrip("\\")
        return os.path.join(self.base_path, bucket, safe_key)

    def _get_meta_path(self, bucket: str, key: str) -> str:
        return self._get_path(bucket, key) + ".meta"

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        try:
            file_path = self._get_path(bucket, key)
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            with open(file_path, "wb") as f:
                f.write(data)
            if metadata:
                meta_path = self._get_meta_path(bucket, key)
                with open(meta_path, "wb") as f:
                    pickle.dump(metadata, f)
            return f"file://{file_path}"
        except Exception as e:
            raise StorageError(
                f"Failed to upload object: {e}", bucket=bucket, key=key
            ) from e

    async def download(self, bucket: str, key: str) -> bytes:
        try:
            file_path = self._get_path(bucket, key)
            if not os.path.exists(file_path):
                raise StorageError(
                    "Object not found", bucket=bucket, key=key
                )
            with open(file_path, "rb") as f:
                return f.read()
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                f"Failed to download object: {e}", bucket=bucket, key=key
            ) from e

    async def delete(self, bucket: str, key: str) -> None:
        try:
            file_path = self._get_path(bucket, key)
            meta_path = self._get_meta_path(bucket, key)
            if os.path.exists(file_path):
                os.remove(file_path)
            if os.path.exists(meta_path):
                os.remove(meta_path)
        except Exception as e:
            raise StorageError(
                f"Failed to delete object: {e}", bucket=bucket, key=key
            ) from e

    async def exists(self, bucket: str, key: str) -> bool:
        file_path = self._get_path(bucket, key)
        return os.path.exists(file_path)

    async def list(
        self, bucket: str, prefix: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        try:
            bucket_path = os.path.join(self.base_path, bucket)
            if not os.path.exists(bucket_path):
                return []
            results = []
            for root, _, files in os.walk(bucket_path):
                for file in files:
                    if file.endswith(".meta"):
                        continue
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, bucket_path)
                    rel_path = rel_path.replace(os.sep, "/")
                    if prefix is None or rel_path.startswith(prefix):
                        stat = os.stat(full_path)
                        metadata = {}
                        meta_path = full_path + ".meta"
                        if os.path.exists(meta_path):
                            with open(meta_path, "rb") as f:
                                metadata = pickle.load(f)
                        results.append(
                            {
                                "key": rel_path,
                                "size": stat.st_size,
                                "last_modified": stat.st_mtime,
                                "metadata": metadata,
                            }
                        )
            return results
        except Exception as e:
            raise StorageError(
                f"Failed to list objects: {e}", bucket=bucket
            ) from e

    async def get_metadata(self, bucket: str, key: str) -> Dict[str, Any]:
        try:
            meta_path = self._get_meta_path(bucket, key)
            if not os.path.exists(meta_path):
                return {}
            with open(meta_path, "rb") as f:
                return pickle.load(f)
        except Exception as e:
            raise StorageError(
                f"Failed to get metadata: {e}", bucket=bucket, key=key
            ) from e


class S3Storage(StorageProtocol):
    """S3兼容存储 - 使用占位实现"""

    def __init__(
        self,
        access_key: str,
        secret_key: str,
        region: str = "us-east-1",
        endpoint_url: Optional[str] = None,
    ) -> None:
        self.access_key = access_key
        self.secret_key = secret_key
        self.region = region
        self.endpoint_url = endpoint_url
        self._client = None
        self._init_client()

    def _init_client(self) -> None:
        try:
            import boto3
            self._client = boto3.client(
                "s3",
                aws_access_key_id=self.access_key,
                aws_secret_access_key=self.secret_key,
                region_name=self.region,
                endpoint_url=self.endpoint_url,
            )
        except ImportError:
            self._client = None

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        if self._client is None:
            raise StorageError(
                "boto3 not installed", bucket=bucket, key=key
            )
        try:
            extra_args = {"Metadata": metadata} if metadata else {}
            with tempfile.NamedTemporaryFile(delete=False) as f:
                f.write(data)
                temp_path = f.name
            self._client.upload_file(
                temp_path, bucket, key, ExtraArgs=extra_args
            )
            os.unlink(temp_path)
            return f"s3://{bucket}/{key}"
        except Exception as e:
            raise StorageError(
                f"Failed to upload to S3: {e}", bucket=bucket, key=key
            ) from e

    async def download(self, bucket: str, key: str) -> bytes:
        if self._client is None:
            raise StorageError(
                "boto3 not installed", bucket=bucket, key=key
            )
        try:
            with tempfile.NamedTemporaryFile(delete=False) as f:
                temp_path = f.name
            self._client.download_file(bucket, key, temp_path)
            with open(temp_path, "rb") as f:
                data = f.read()
            os.unlink(temp_path)
            return data
        except Exception as e:
            raise StorageError(
                f"Failed to download from S3: {e}", bucket=bucket, key=key
            ) from e

    async def delete(self, bucket: str, key: str) -> None:
        if self._client is None:
            raise StorageError(
                "boto3 not installed", bucket=bucket, key=key
            )
        try:
            self._client.delete_object(Bucket=bucket, Key=key)
        except Exception as e:
            raise StorageError(
                f"Failed to delete from S3: {e}", bucket=bucket, key=key
            ) from e

    async def exists(self, bucket: str, key: str) -> bool:
        if self._client is None:
            raise StorageError(
                "boto3 not installed", bucket=bucket, key=key
            )
        try:
            self._client.head_object(Bucket=bucket, Key=key)
            return True
        except Exception:
            return False

    async def list(
        self, bucket: str, prefix: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        if self._client is None:
            raise StorageError(
                "boto3 not installed", bucket=bucket
            )
        try:
            kwargs = {"Bucket": bucket}
            if prefix:
                kwargs["Prefix"] = prefix
            response = self._client.list_objects_v2(**kwargs)
            results = []
            for obj in response.get("Contents", []):
                results.append(
                    {
                        "key": obj["Key"],
                        "size": obj["Size"],
                        "last_modified": obj["LastModified"].timestamp(),
                        "metadata": {},
                    }
                )
            return results
        except Exception as e:
            raise StorageError(
                f"Failed to list S3 objects: {e}", bucket=bucket
            ) from e

    async def get_metadata(self, bucket: str, key: str) -> Dict[str, Any]:
        if self._client is None:
            raise StorageError(
                "boto3 not installed", bucket=bucket, key=key
            )
        try:
            response = self._client.head_object(Bucket=bucket, Key=key)
            return response.get("Metadata", {})
        except Exception as e:
            raise StorageError(
                f"Failed to get S3 metadata: {e}", bucket=bucket, key=key
            ) from e


class StorageMetadataIndex:
    """存储元数据索引 - 提供快速检索能力"""

    def __init__(self) -> None:
        self._index: Dict[str, Dict[str, Any]] = {}
        self._tag_index: Dict[str, List[str]] = {}

    def index_object(
        self,
        bucket: str,
        key: str,
        metadata: Dict[str, Any],
    ) -> None:
        obj_id = f"{bucket}/{key}"
        self._index[obj_id] = {
            "bucket": bucket,
            "key": key,
            **metadata,
        }
        tags = metadata.get("tags", [])
        for tag in tags:
            if tag not in self._tag_index:
                self._tag_index[tag] = []
            if obj_id not in self._tag_index[tag]:
                self._tag_index[tag].append(obj_id)

    def search_by_tag(self, tag: str) -> List[Dict[str, Any]]:
        return [
            self._index[obj_id]
            for obj_id in self._tag_index.get(tag, [])
        ]

    def search_by_metadata(
        self,
        bucket: Optional[str] = None,
        **filters: Any,
    ) -> List[Dict[str, Any]]:
        results = []
        for obj_id, meta in self._index.items():
            if bucket and meta["bucket"] != bucket:
                continue
            match = all(
                meta.get(k) == v for k, v in filters.items()
            )
            if match:
                results.append(meta)
        return results
