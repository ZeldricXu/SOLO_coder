from datetime import datetime, timedelta
from typing import List, Dict, Any
import logging
from sqlalchemy import and_

from config.settings import settings
from db.database import get_db_session
from db.models import DataArchive, Sample
from storage.minio_client import get_minio_client

logger = logging.getLogger(__name__)


class RetentionPolicyManager:
    def __init__(self):
        self.minio_client = get_minio_client()
        self.policies = {
            "fastq_r1": settings.retention.raw_fastq_days,
            "fastq_r2": settings.retention.raw_fastq_days,
            "bam": settings.retention.bam_days,
            "gvcf": settings.retention.gvcf_days,
            "vcf": settings.retention.report_days,
            "report": settings.retention.report_days,
        }

    def register_file_for_retention(
        self,
        object_key: str,
        bucket: str,
        file_type: str,
        sample_id: int,
        size_bytes: int,
    ) -> DataArchive:
        retention_days = self.policies.get(file_type, 365)
        delete_after = None
        if retention_days > 0:
            delete_after = datetime.utcnow() + timedelta(days=retention_days)

        with get_db_session() as db:
            archive = DataArchive(
                object_key=object_key,
                bucket=bucket,
                file_type=file_type,
                sample_id=sample_id,
                size_bytes=size_bytes,
                retention_days=retention_days,
                delete_after=delete_after,
            )
            db.add(archive)
            db.commit()
            db.refresh(archive)
            logger.info(
                f"Registered {object_key} for retention: {retention_days} days, "
                f"delete after {delete_after}"
            )
            return archive

    def get_files_due_for_deletion(self) -> List[DataArchive]:
        with get_db_session() as db:
            now = datetime.utcnow()
            archives = (
                db.query(DataArchive)
                .filter(
                    and_(
                        DataArchive.delete_after <= now,
                        DataArchive.is_deleted == False,
                    )
                )
                .all()
            )
            return archives

    def cleanup_expired_files(self) -> Dict[str, Any]:
        stats = {
            "total_checked": 0,
            "deleted_from_storage": 0,
            "marked_deleted": 0,
            "errors": [],
            "total_size_freed": 0,
        }

        expired_files = self.get_files_due_for_deletion()
        stats["total_checked"] = len(expired_files)

        for archive in expired_files:
            try:
                success = self.minio_client.delete_object(archive.bucket, archive.object_key)
                if success:
                    with get_db_session() as db:
                        archive_db = db.query(DataArchive).filter(DataArchive.id == archive.id).first()
                        if archive_db:
                            archive_db.is_deleted = True
                            archive_db.deleted_at = datetime.utcnow()
                            db.commit()
                            stats["marked_deleted"] += 1
                            stats["total_size_freed"] += archive_db.size_bytes or 0
                    stats["deleted_from_storage"] += 1
                    logger.info(f"Deleted expired file: {archive.object_key}")
                else:
                    stats["errors"].append(f"Failed to delete {archive.object_key} from storage")
            except Exception as e:
                error_msg = f"Error deleting {archive.object_key}: {str(e)}"
                stats["errors"].append(error_msg)
                logger.error(error_msg)

        logger.info(
            f"Cleanup complete: {stats['deleted_from_storage']} files deleted, "
            f"{stats['total_size_freed'] / (1024**3):.2f} GB freed"
        )
        return stats

    def update_retention_policy(self, file_type: str, days: int) -> None:
        self.policies[file_type] = days
        logger.info(f"Updated retention policy for {file_type}: {days} days")

    def get_retention_summary(self) -> Dict[str, Any]:
        summary = {"policies": self.policies.copy(), "archive_stats": {}}

        with get_db_session() as db:
            for file_type in self.policies.keys():
                count = (
                    db.query(DataArchive)
                    .filter(
                        and_(
                            DataArchive.file_type == file_type,
                            DataArchive.is_deleted == False,
                        )
                    )
                    .count()
                )
                total_size = (
                    db.query(DataArchive)
                    .filter(
                        and_(
                            DataArchive.file_type == file_type,
                            DataArchive.is_deleted == False,
                        )
                    )
                    .all()
                )
                size_sum = sum(a.size_bytes or 0 for a in total_size)
                summary["archive_stats"][file_type] = {
                    "count": count,
                    "total_size_gb": size_sum / (1024**3),
                }

        return summary

    def extend_retention(self, archive_id: int, additional_days: int) -> bool:
        try:
            with get_db_session() as db:
                archive = db.query(DataArchive).filter(DataArchive.id == archive_id).first()
                if archive and not archive.is_deleted:
                    if archive.delete_after:
                        archive.delete_after += timedelta(days=additional_days)
                    else:
                        archive.delete_after = datetime.utcnow() + timedelta(days=additional_days)
                    archive.retention_days = (archive.retention_days or 0) + additional_days
                    db.commit()
                    logger.info(f"Extended retention for archive {archive_id} by {additional_days} days")
                    return True
            return False
        except Exception as e:
            logger.error(f"Error extending retention: {e}")
            return False

    def cleanup_old_raw_fastq(self, days: int = None) -> Dict[str, Any]:
        if days is None:
            days = settings.retention.raw_fastq_days

        cutoff_date = datetime.utcnow() - timedelta(days=days)
        stats = {
            "samples_checked": 0,
            "fastq_files_deleted": 0,
            "errors": [],
            "total_size_freed": 0,
        }

        with get_db_session() as db:
            old_samples = (
                db.query(Sample)
                .filter(
                    and_(
                        Sample.received_at <= cutoff_date,
                        Sample.fastq_r1_path.isnot(None),
                    )
                )
                .all()
            )

            stats["samples_checked"] = len(old_samples)

            for sample in old_samples:
                try:
                    for path_attr in ["fastq_r1_path", "fastq_r2_path"]:
                        path = getattr(sample, path_attr)
                        if path:
                            if path.startswith("minio://"):
                                parts = path.replace("minio://", "").split("/", 1)
                                if len(parts) == 2:
                                    bucket, object_key = parts
                                    meta = self.minio_client.get_object_metadata(bucket, object_key)
                                    if self.minio_client.delete_object(bucket, object_key):
                                        stats["fastq_files_deleted"] += 1
                                        if meta and meta.get("size"):
                                            stats["total_size_freed"] += meta["size"]
                                    setattr(sample, path_attr, None)
                            elif path and not path.startswith(("http://", "https://")):
                                import os
                                if os.path.exists(path):
                                    size = os.path.getsize(path)
                                    os.remove(path)
                                    stats["fastq_files_deleted"] += 1
                                    stats["total_size_freed"] += size
                                    setattr(sample, path_attr, None)

                    db.commit()
                    logger.info(f"Deleted raw FASTQ files for sample {sample.sample_id}")
                except Exception as e:
                    error_msg = f"Error processing sample {sample.sample_id}: {str(e)}"
                    stats["errors"].append(error_msg)
                    logger.error(error_msg)

        return stats


def get_retention_manager() -> RetentionPolicyManager:
    return RetentionPolicyManager()
