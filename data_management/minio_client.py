import logging
import io
import os
from typing import List, Optional, Dict, Any
from pathlib import Path
from datetime import datetime, timedelta

from minio import Minio
from minio.error import S3Error
from minio.commonconfig import GOVERNANCE
from minio.retention import Retention

from config.settings import settings

logger = logging.getLogger(__name__)


class MinIOClient:
    """MinIO object storage client for genomic data management."""

    def __init__(self):
        self.client = Minio(
            settings.minio.endpoint,
            access_key=settings.minio.access_key,
            secret_key=settings.minio.secret_key,
            secure=settings.minio.secure,
        )
        self._ensure_buckets()

    def _ensure_buckets(self):
        """Ensure required buckets exist."""
        buckets = [
            settings.minio.raw_data_bucket,
            settings.minio.results_bucket,
            settings.minio.reports_bucket,
        ]

        for bucket in buckets:
            if not self.client.bucket_exists(bucket):
                self.client.make_bucket(bucket)
                logger.info(f"Created bucket: {bucket}")

    def upload_file(
        self,
        file_path: str,
        bucket: str,
        object_key: str,
        metadata: Optional[Dict[str, Any]] = None,
        retention_days: Optional[int] = None,
    ) -> str:
        """
        Upload a file to MinIO storage.

        Args:
            file_path: Path to local file
            bucket: Bucket name
            object_key: Object key/path in bucket
            metadata: Optional metadata dictionary
            retention_days: Optional retention period in days

        Returns:
            Object key
        """
        file_path_obj = Path(file_path)
        if not file_path_obj.exists():
            raise FileNotFoundError(f"File not found: {file_path}")

        file_size = file_path_obj.stat().st_size

        extra_params = {}
        if metadata:
            extra_params["metadata"] = {
                k: str(v) for k, v in metadata.items()
            }

        if retention_days and retention_days > 0:
            retention = Retention(
                GOVERNANCE,
                datetime.utcnow() + timedelta(days=retention_days),
            )
            extra_params["retention"] = retention

        try:
            self.client.fput_object(
                bucket_name=bucket,
                object_name=object_key,
                file_path=file_path,
                **extra_params,
            )
            logger.info(f"Uploaded {file_path} to {bucket}/{object_key}")
            return object_key
        except S3Error as e:
            logger.error(f"Failed to upload file: {e}")
            raise

    def upload_fileobj(
        self,
        file_obj: io.BytesIO,
        bucket: str,
        object_key: str,
        file_size: int,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Upload a file-like object to MinIO storage."""
        try:
            self.client.put_object(
                bucket_name=bucket,
                object_name=object_key,
                data=file_obj,
                length=file_size,
                metadata=metadata,
            )
            logger.info(f"Uploaded file object to {bucket}/{object_key}")
            return object_key
        except S3Error as e:
            logger.error(f"Failed to upload file object: {e}")
            raise

    def download_file(
        self,
        bucket: str,
        object_key: str,
        local_path: Optional[str] = None,
    ) -> str:
        """
        Download a file from MinIO storage.

        Args:
            bucket: Bucket name
            object_key: Object key in bucket
            local_path: Optional local path to save file

        Returns:
            Local file path
        """
        if local_path is None:
            local_path = f"/tmp/{Path(object_key).name}"

        try:
            self.client.fget_object(bucket, object_key, local_path)
            logger.info(f"Downloaded {bucket}/{object_key} to {local_path}")
            return local_path
        except S3Error as e:
            logger.error(f"Failed to download file: {e}")
            raise

    def get_fileobj(self, bucket: str, object_key: str) -> io.BytesIO:
        """Get a file-like object from MinIO."""
        try:
            response = self.client.get_object(bucket, object_key)
            data = io.BytesIO(response.read())
            response.close()
            response.release_conn()
            return data
        except S3Error as e:
            logger.error(f"Failed to get file object: {e}")
            raise

    def delete_object(self, bucket: str, object_key: str) -> bool:
        """Delete an object from MinIO."""
        try:
            self.client.remove_object(bucket, object_key)
            logger.info(f"Deleted {bucket}/{object_key}")
            return True
        except S3Error as e:
            logger.error(f"Failed to delete object: {e}")
            return False

    def list_objects(
        self,
        bucket: str,
        prefix: str = "",
        recursive: bool = True,
    ) -> List[Dict[str, Any]]:
        """List objects in a bucket with optional prefix."""
        try:
            objects = self.client.list_objects(
                bucket_name=bucket,
                prefix=prefix,
                recursive=recursive,
            )
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
            logger.error(f"Failed to list objects: {e}")
            raise

    def get_object_stat(self, bucket: str, object_key: str) -> Optional[Dict[str, Any]]:
        """Get metadata and stat info for an object."""
        try:
            stat = self.client.stat_object(bucket, object_key)
            return {
                "object_name": stat.object_name,
                "size": stat.size,
                "last_modified": stat.last_modified,
                "etag": stat.etag,
                "metadata": stat.metadata,
            }
        except S3Error as e:
            logger.warning(f"Object not found: {bucket}/{object_key}")
            return None

    def object_exists(self, bucket: str, object_key: str) -> bool:
        """Check if an object exists in the bucket."""
        return self.get_object_stat(bucket, object_key) is not None

    def copy_object(
        self,
        source_bucket: str,
        source_key: str,
        dest_bucket: str,
        dest_key: str,
    ) -> bool:
        """Copy an object from one bucket/key to another."""
        try:
            self.client.copy_object(
                dest_bucket,
                dest_key,
                f"{source_bucket}/{source_key}",
            )
            logger.info(f"Copied {source_bucket}/{source_key} to {dest_bucket}/{dest_key}")
            return True
        except S3Error as e:
            logger.error(f"Failed to copy object: {e}")
            return False

    def get_presigned_url(
        self,
        bucket: str,
        object_key: str,
        expires_hours: int = 24,
    ) -> str:
        """Generate a presigned URL for downloading a file."""
        try:
            url = self.client.presigned_get_object(
                bucket_name=bucket,
                object_name=object_key,
                expires=timedelta(hours=expires_hours),
            )
            return url
        except S3Error as e:
            logger.error(f"Failed to generate presigned URL: {e}")
            raise

    def upload_sample_raw_data(
        self,
        sample_id: str,
        fastq_r1_path: str,
        fastq_r2_path: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, str]:
        """
        Upload raw FASTQ files for a sample.

        Args:
            sample_id: Sample identifier
            fastq_r1_path: Path to R1 FASTQ file
            fastq_r2_path: Path to R2 FASTQ file
            metadata: Optional metadata

        Returns:
            Dictionary with object keys for R1 and R2
        """
        r1_key = f"{sample_id}/raw/{Path(fastq_r1_path).name}"
        r2_key = f"{sample_id}/raw/{Path(fastq_r2_path).name}"

        file_metadata = metadata or {}
        file_metadata["sample_id"] = sample_id
        file_metadata["data_type"] = "raw_fastq"
        file_metadata["upload_date"] = datetime.utcnow().isoformat()

        retention_days = settings.retention.raw_fastq_days

        r1_uploaded = self.upload_file(
            fastq_r1_path,
            settings.minio.raw_data_bucket,
            r1_key,
            metadata=file_metadata,
            retention_days=retention_days,
        )
        r2_uploaded = self.upload_file(
            fastq_r2_path,
            settings.minio.raw_data_bucket,
            r2_key,
            metadata=file_metadata,
            retention_days=retention_days,
        )

        return {
            "fastq_r1": r1_uploaded,
            "fastq_r2": r2_uploaded,
        }

    def upload_analysis_results(
        self,
        sample_id: str,
        result_files: List[str],
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[str]:
        """Upload analysis result files to results bucket."""
        uploaded_keys = []
        result_metadata = metadata or {}
        result_metadata["sample_id"] = sample_id
        result_metadata["data_type"] = "analysis_result"
        result_metadata["upload_date"] = datetime.utcnow().isoformat()

        for file_path in result_files:
            file_path_obj = Path(file_path)
            object_key = f"{sample_id}/results/{file_path_obj.name}"
            key = self.upload_file(
                file_path,
                settings.minio.results_bucket,
                object_key,
                metadata=result_metadata,
            )
            uploaded_keys.append(key)

        return uploaded_keys

    def upload_report(
        self,
        sample_id: str,
        report_path: str,
        report_type: str = "clinical",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Upload a clinical report."""
        report_metadata = metadata or {}
        report_metadata["sample_id"] = sample_id
        report_metadata["report_type"] = report_type
        report_metadata["upload_date"] = datetime.utcnow().isoformat()

        object_key = f"{sample_id}/reports/{Path(report_path).name}"
        return self.upload_file(
            report_path,
            settings.minio.reports_bucket,
            object_key,
            metadata=report_metadata,
        )

    def archive_gvcf(
        self,
        sample_id: str,
        gvcf_path: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Archive a gVCF file for long-term storage."""
        gvcf_metadata = metadata or {}
        gvcf_metadata["sample_id"] = sample_id
        gvcf_metadata["data_type"] = "gvcf"
        gvcf_metadata["archive_date"] = datetime.utcnow().isoformat()

        object_key = f"{sample_id}/archive/{Path(gvcf_path).name}"
        return self.upload_file(
            gvcf_path,
            settings.minio.results_bucket,
            object_key,
            metadata=gvcf_metadata,
            retention_days=settings.retention.gvcf_days if settings.retention.gvcf_days > 0 else None,
        )

    def get_expired_objects(self, bucket: str) -> List[Dict[str, Any]]:
        """Get list of objects that have expired retention policies."""
        objects = self.list_objects(bucket)
        expired = []
        now = datetime.utcnow()

        for obj in objects:
            stat = self.get_object_stat(bucket, obj["object_name"])
            if stat and stat.get("metadata"):
                retention_days = stat["metadata"].get("X-Amz-Meta-Retention-Days")
                upload_date_str = stat["metadata"].get("X-Amz-Meta-Upload-Date")
                archive_date_str = stat["metadata"].get("X-Amz-Meta-Archive-Date")

                date_str = upload_date_str or archive_date_str
                if date_str and retention_days:
                    try:
                        upload_date = datetime.fromisoformat(date_str)
                        retention_days_int = int(retention_days)
                        if (now - upload_date).days > retention_days_int:
                            obj["delete_after"] = upload_date + timedelta(days=retention_days_int)
                            expired.append(obj)
                    except (ValueError, TypeError):
                        continue

        return expired
