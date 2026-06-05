import os
import io
from typing import Optional, Dict, Any
from pathlib import Path

from minio import Minio
from minio.error import S3Error
import redis
import pickle

from app.core.config import get_settings
from app.core.logging_config import get_logger

logger = get_logger(__name__)
settings = get_settings()


class StorageService:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self.minio_client = None
        self.redis_client = None
        self._initialize_minio()
        self._initialize_redis()

    def _initialize_minio(self):
        try:
            self.minio_client = Minio(
                settings.MINIO_ENDPOINT,
                access_key=settings.MINIO_ACCESS_KEY,
                secret_key=settings.MINIO_SECRET_KEY,
                secure=settings.MINIO_SECURE,
            )
            self._ensure_buckets_exist()
            logger.info("MinIO client initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize MinIO client: {e}")
            self.minio_client = None

    def _ensure_buckets_exist(self):
        if not self.minio_client:
            return

        buckets = [
            settings.MINIO_RAW_BUCKET,
            settings.MINIO_PROCESSED_BUCKET,
            settings.MINIO_MODEL_BUCKET,
        ]

        for bucket in buckets:
            try:
                if not self.minio_client.bucket_exists(bucket):
                    self.minio_client.make_bucket(bucket)
                    logger.info(f"Created bucket: {bucket}")
            except S3Error as e:
                logger.warning(f"Failed to check/create bucket {bucket}: {e}")

    def _initialize_redis(self):
        try:
            self.redis_client = redis.Redis.from_url(
                settings.REDIS_URL,
                decode_responses=False,
                socket_connect_timeout=5,
                socket_timeout=5,
            )
            self.redis_client.ping()
            logger.info("Redis client initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize Redis client: {e}")
            self.redis_client = None

    def upload_file(
        self,
        bucket_name: str,
        object_name: str,
        file_path: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        if not self.minio_client:
            raise RuntimeError("MinIO client not initialized")

        try:
            file_size = os.path.getsize(file_path)
            with open(file_path, "rb") as file_data:
                self.minio_client.put_object(
                    bucket_name=bucket_name,
                    object_name=object_name,
                    data=file_data,
                    length=file_size,
                    metadata=metadata or {},
                )
            logger.debug(f"Uploaded {object_name} to {bucket_name}")
            return object_name
        except Exception as e:
            logger.error(f"Failed to upload {object_name} to {bucket_name}: {e}")
            raise

    def upload_bytes(
        self,
        bucket_name: str,
        object_name: str,
        data: bytes,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        if not self.minio_client:
            raise RuntimeError("MinIO client not initialized")

        try:
            self.minio_client.put_object(
                bucket_name=bucket_name,
                object_name=object_name,
                data=io.BytesIO(data),
                length=len(data),
                metadata=metadata or {},
            )
            logger.debug(f"Uploaded bytes to {bucket_name}/{object_name}")
            return object_name
        except Exception as e:
            logger.error(f"Failed to upload bytes to {bucket_name}/{object_name}: {e}")
            raise

    def download_file(
        self,
        bucket_name: str,
        object_name: str,
        file_path: str,
    ) -> None:
        if not self.minio_client:
            raise RuntimeError("MinIO client not initialized")

        try:
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            self.minio_client.fget_object(bucket_name, object_name, file_path)
            logger.debug(f"Downloaded {bucket_name}/{object_name} to {file_path}")
        except Exception as e:
            logger.error(f"Failed to download {bucket_name}/{object_name}: {e}")
            raise

    def download_file_bytes(
        self,
        bucket_name: str,
        object_name: str,
    ) -> bytes:
        if not self.minio_client:
            raise RuntimeError("MinIO client not initialized")

        try:
            response = self.minio_client.get_object(bucket_name, object_name)
            data = response.read()
            response.close()
            response.release_conn()
            return data
        except Exception as e:
            logger.error(f"Failed to download bytes from {bucket_name}/{object_name}: {e}")
            raise

    def file_exists(self, bucket_name: str, object_name: str) -> bool:
        if not self.minio_client:
            return False

        try:
            self.minio_client.stat_object(bucket_name, object_name)
            return True
        except S3Error as e:
            if e.code == "NoSuchKey":
                return False
            raise

    def delete_file(self, bucket_name: str, object_name: str) -> None:
        if not self.minio_client:
            raise RuntimeError("MinIO client not initialized")

        try:
            self.minio_client.remove_object(bucket_name, object_name)
            logger.debug(f"Deleted {bucket_name}/{object_name}")
        except Exception as e:
            logger.error(f"Failed to delete {bucket_name}/{object_name}: {e}")
            raise

    def list_files(self, bucket_name: str, prefix: str = "") -> list:
        if not self.minio_client:
            raise RuntimeError("MinIO client not initialized")

        try:
            objects = self.minio_client.list_objects(bucket_name, prefix=prefix, recursive=True)
            return [obj.object_name for obj in objects]
        except Exception as e:
            logger.error(f"Failed to list files in {bucket_name}/{prefix}: {e}")
            raise

    def cache_set(self, key: str, value: Any, ttl: int = 3600) -> bool:
        if not self.redis_client:
            return False

        try:
            if isinstance(value, (str, bytes)):
                self.redis_client.setex(key, ttl, value)
            else:
                self.redis_client.setex(key, ttl, pickle.dumps(value))
            return True
        except Exception as e:
            logger.debug(f"Failed to set cache for key {key}: {e}")
            return False

    def cache_get(self, key: str) -> Any:
        if not self.redis_client:
            return None

        try:
            value = self.redis_client.get(key)
            if value is None:
                return None
            try:
                return pickle.loads(value)
            except (pickle.UnpicklingError, TypeError):
                return value
        except Exception as e:
            logger.debug(f"Failed to get cache for key {key}: {e}")
            return None

    def cache_delete(self, key: str) -> bool:
        if not self.redis_client:
            return False

        try:
            self.redis_client.delete(key)
            return True
        except Exception as e:
            logger.debug(f"Failed to delete cache for key {key}: {e}")
            return False

    def get_file_url(
        self,
        bucket_name: str,
        object_name: str,
        expires_in: int = 3600,
    ) -> str:
        if not self.minio_client:
            raise RuntimeError("MinIO client not initialized")

        try:
            url = self.minio_client.presigned_get_object(
                bucket_name, object_name, expires=expires_in
            )
            return url
        except Exception as e:
            logger.error(f"Failed to generate presigned URL: {e}")
            raise

    def is_minio_available(self) -> bool:
        return self.minio_client is not None

    def is_redis_available(self) -> bool:
        if not self.redis_client:
            return False
        try:
            return self.redis_client.ping()
        except Exception:
            return False
