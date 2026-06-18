from minio import Minio
from minio.error import S3Error
from typing import List, Optional, Dict, Any
from pathlib import Path
import logging
import os
from datetime import datetime, timedelta

from config.settings import settings

logger = logging.getLogger(__name__)


class MinioClient:
    def __init__(self):
        self.client = Minio(
            settings.minio.endpoint,
            access_key=settings.minio.access_key,
            secret_key=settings.minio.secret_key,
            secure=settings.minio.secure,
        )
        self._ensure_buckets()

    def _ensure_buckets(self) -> None:
        buckets = [
            settings.minio.raw_data_bucket,
            settings.minio.results_bucket,
            settings.minio.reports_bucket,
        ]
        for bucket in buckets:
            try:
                if not self.client.bucket_exists(bucket):
                    self.client.make_bucket(bucket)
                    logger.info(f"Created bucket: {bucket}")
            except S3Error as e:
                logger.error(f"Error checking/creating bucket {bucket}: {e}")

    def upload_file(
        self,
        file_path: str,
        bucket: str,
        object_name: str,
        metadata: Optional[Dict[str, str]] = None,
    ) -> bool:
        try:
            file_stat = os.stat(file_path)
            with open(file_path, "rb") as file_data:
                self.client.put_object(
                    bucket,
                    object_name,
                    file_data,
                    file_stat.st_size,
                    metadata=metadata,
                )
            logger.info(f"Uploaded {file_path} to {bucket}/{object_name}")
            return True
        except S3Error as e:
            logger.error(f"Error uploading {file_path}: {e}")
            return False

    def download_file(
        self,
        bucket: str,
        object_name: str,
        file_path: str,
    ) -> bool:
        try:
            self.client.fget_object(bucket, object_name, file_path)
            logger.info(f"Downloaded {bucket}/{object_name} to {file_path}")
            return True
        except S3Error as e:
            logger.error(f"Error downloading {bucket}/{object_name}: {e}")
            return False

    def delete_object(self, bucket: str, object_name: str) -> bool:
        try:
            self.client.remove_object(bucket, object_name)
            logger.info(f"Deleted {bucket}/{object_name}")
            return True
        except S3Error as e:
            logger.error(f"Error deleting {bucket}/{object_name}: {e}")
            return False

    def list_objects(self, bucket: str, prefix: str = "") -> List[Dict[str, Any]]:
        try:
            objects = self.client.list_objects(bucket, prefix=prefix, recursive=True)
            return [
                {
                    "object_name": obj.object_name,
                    "size": obj.size,
                    "last_modified": obj.last_modified,
                    "etag": obj.etag,
                }
                for obj in objects
            ]
        except S3Error as e:
            logger.error(f"Error listing objects in {bucket}/{prefix}: {e}")
            return []

    def get_object_url(
        self,
        bucket: str,
        object_name: str,
        expires_seconds: int = 3600,
    ) -> Optional[str]:
        try:
            return self.client.presigned_get_object(
                bucket, object_name, expires=timedelta(seconds=expires_seconds)
            )
        except S3Error as e:
            logger.error(f"Error generating URL for {bucket}/{object_name}: {e}")
            return None

    def object_exists(self, bucket: str, object_name: str) -> bool:
        try:
            self.client.stat_object(bucket, object_name)
            return True
        except S3Error:
            return False

    def get_object_metadata(self, bucket: str, object_name: str) -> Optional[Dict[str, Any]]:
        try:
            stat = self.client.stat_object(bucket, object_name)
            return {
                "object_name": stat.object_name,
                "size": stat.size,
                "last_modified": stat.last_modified,
                "etag": stat.etag,
                "metadata": stat.metadata,
            }
        except S3Error as e:
            logger.error(f"Error getting metadata for {bucket}/{object_name}: {e}")
            return None

    def copy_object(
        self,
        source_bucket: str,
        source_object: str,
        dest_bucket: str,
        dest_object: str,
    ) -> bool:
        try:
            self.client.copy_object(
                dest_bucket,
                dest_object,
                f"{source_bucket}/{source_object}",
            )
            logger.info(f"Copied {source_bucket}/{source_object} to {dest_bucket}/{dest_object}")
            return True
        except S3Error as e:
            logger.error(f"Error copying object: {e}")
            return False

    def upload_sample_fastq(
        self,
        sample_id: str,
        r1_path: str,
        r2_path: str,
    ) -> Dict[str, str]:
        object_r1 = f"{sample_id}/raw/{Path(r1_path).name}"
        object_r2 = f"{sample_id}/raw/{Path(r2_path).name}"

        result = {}
        if self.upload_file(r1_path, settings.minio.raw_data_bucket, object_r1):
            result["r1_object"] = object_r1
        if self.upload_file(r2_path, settings.minio.raw_data_bucket, object_r2):
            result["r2_object"] = object_r2
        return result

    def upload_analysis_results(
        self,
        sample_id: str,
        result_files: List[str],
    ) -> List[str]:
        uploaded = []
        for file_path in result_files:
            object_name = f"{sample_id}/results/{Path(file_path).name}"
            if self.upload_file(file_path, settings.minio.results_bucket, object_name):
                uploaded.append(object_name)
        return uploaded

    def upload_report(
        self,
        sample_id: str,
        report_path: str,
    ) -> Optional[str]:
        object_name = f"{sample_id}/reports/{Path(report_path).name}"
        if self.upload_file(report_path, settings.minio.reports_bucket, object_name):
            return object_name
        return None


def get_minio_client() -> MinioClient:
    return MinioClient()
