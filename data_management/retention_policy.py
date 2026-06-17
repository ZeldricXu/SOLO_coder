import logging
from typing import List, Dict, Any
from datetime import datetime, timedelta
from pathlib import Path
import os

from sqlalchemy import and_

from db.models import Sample, SampleStatus, DataArchive
from db.database import get_db_session
from data_management.minio_client import MinIOClient
from config.settings import settings

logger = logging.getLogger(__name__)


class RetentionPolicyManager:
    """Manager for data retention policies and automatic cleanup."""

    def __init__(self, minio_client: MinIOClient = None):
        self.minio_client = minio_client or MinIOClient()

    def register_archive(
        self,
        object_key: str,
        bucket: str,
        file_type: str,
        sample_id: int,
        size_bytes: int,
        retention_days: int,
    ) -> DataArchive:
        """Register an archived file in the database."""
        with get_db_session() as db:
            delete_after = datetime.utcnow() + timedelta(days=retention_days) if retention_days > 0 else None

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

            logger.info(f"Registered archive: {bucket}/{object_key}, retention: {retention_days} days")
            return archive

    def get_expired_archives(self) -> List[DataArchive]:
        """Get all archives that have expired."""
        with get_db_session() as db:
            now = datetime.utcnow()
            return (
                db.query(DataArchive)
                .filter(
                    and_(
                        DataArchive.is_deleted == False,
                        DataArchive.delete_after <= now,
                    )
                )
                .all()
            )

    def get_expired_raw_data(self) -> List[Sample]:
        """Get samples whose raw FASTQ data has expired (90 days)."""
        with get_db_session() as db:
            cutoff_date = datetime.utcnow() - timedelta(days=settings.retention.raw_fastq_days)
            return (
                db.query(Sample)
                .filter(
                    and_(
                        Sample.status != SampleStatus.ARCHIVED,
                        Sample.created_at <= cutoff_date,
                        Sample.fastq_r1_path.isnot(None),
                    )
                )
                .all()
            )

    def cleanup_expired_data(self, dry_run: bool = False) -> Dict[str, Any]:
        """
        Clean up all expired data according to retention policies.

        Args:
            dry_run: If True, only report what would be deleted

        Returns:
            Summary of cleanup operations
        """
        summary = {
            "dry_run": dry_run,
            "raw_fastq_deleted": [],
            "raw_fastq_failed": [],
            "archives_deleted": [],
            "archives_failed": [],
            "local_files_deleted": [],
            "total_size_freed": 0,
        }

        expired_samples = self.get_expired_raw_data()
        logger.info(f"Found {len(expired_samples)} samples with expired raw data")

        for sample in expired_samples:
            try:
                if not dry_run:
                    if sample.fastq_r1_path:
                        self.minio_client.delete_object(
                            settings.minio.raw_data_bucket,
                            sample.fastq_r1_path,
                        )
                    if sample.fastq_r2_path:
                        self.minio_client.delete_object(
                            settings.minio.raw_data_bucket,
                            sample.fastq_r2_path,
                        )

                    sample.fastq_r1_path = None
                    sample.fastq_r2_path = None

                    with get_db_session() as db:
                        db.merge(sample)
                        db.commit()

                summary["raw_fastq_deleted"].append({
                    "sample_id": sample.sample_id,
                    "patient_id": sample.patient_id,
                    "created_at": sample.created_at.isoformat() if sample.created_at else None,
                })

                logger.info(f"Deleted raw data for sample {sample.sample_id}")

            except Exception as e:
                logger.error(f"Failed to delete raw data for sample {sample.sample_id}: {e}")
                summary["raw_fastq_failed"].append({
                    "sample_id": sample.sample_id,
                    "error": str(e),
                })

        expired_archives = self.get_expired_archives()
        logger.info(f"Found {len(expired_archives)} expired archives")

        for archive in expired_archives:
            try:
                if not dry_run:
                    self.minio_client.delete_object(archive.bucket, archive.object_key)

                    with get_db_session() as db:
                        archive.is_deleted = True
                        archive.deleted_at = datetime.utcnow()
                        db.merge(archive)
                        db.commit()

                summary["archives_deleted"].append({
                    "object_key": archive.object_key,
                    "bucket": archive.bucket,
                    "file_type": archive.file_type,
                    "size_bytes": archive.size_bytes,
                    "delete_after": archive.delete_after.isoformat() if archive.delete_after else None,
                })
                summary["total_size_freed"] += archive.size_bytes or 0

                logger.info(f"Deleted expired archive: {archive.bucket}/{archive.object_key}")

            except Exception as e:
                logger.error(f"Failed to delete archive {archive.object_key}: {e}")
                summary["archives_failed"].append({
                    "object_key": archive.object_key,
                    "error": str(e),
                })

        if not dry_run:
            local_cleanup = self.cleanup_local_work_dirs()
            summary.update(local_cleanup)

        return summary

    def cleanup_local_work_dirs(self, max_age_days: int = 30) -> Dict[str, Any]:
        """Clean up local working directories older than max_age_days."""
        work_dir = Path(settings.pipeline.work_dir)
        summary = {
            "local_files_deleted": [],
            "local_size_freed": 0,
        }

        if not work_dir.exists():
            return summary

        cutoff_time = datetime.now() - timedelta(days=max_age_days)
        timestamp_cutoff = cutoff_time.timestamp()

        for dirpath, dirnames, filenames in os.walk(work_dir):
            for filename in filenames:
                filepath = Path(dirpath) / filename
                try:
                    mtime = filepath.stat().st_mtime
                    if mtime < timestamp_cutoff:
                        file_size = filepath.stat().st_size
                        filepath.unlink()
                        summary["local_files_deleted"].append(str(filepath))
                        summary["local_size_freed"] += file_size
                        logger.info(f"Deleted local file: {filepath}")
                except Exception as e:
                    logger.warning(f"Failed to delete local file {filepath}: {e}")

            if dirnames:
                for dirname in list(dirnames):
                    dirpath_full = Path(dirpath) / dirname
                    try:
                        if not any(dirpath_full.iterdir()):
                            mtime = dirpath_full.stat().st_mtime
                            if mtime < timestamp_cutoff:
                                dirpath_full.rmdir()
                                logger.info(f"Deleted empty directory: {dirpath_full}")
                    except Exception as e:
                        logger.warning(f"Failed to check directory {dirpath_full}: {e}")

        return summary

    def archive_sample_results(
        self,
        sample_id: str,
        result_files: List[str],
    ) -> List[DataArchive]:
        """Archive result files for long-term storage."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if not sample:
                raise ValueError(f"Sample not found: {sample_id}")

            archives = []
            for file_path in result_files:
                file_path_obj = Path(file_path)
                if not file_path_obj.exists():
                    logger.warning(f"File not found for archiving: {file_path}")
                    continue

                file_type = self._determine_file_type(file_path)
                retention_days = self._get_retention_days(file_type)
                size_bytes = file_path_obj.stat().st_size

                object_key = self.minio_client.upload_analysis_results(
                    sample_id,
                    [file_path],
                    metadata={
                        "file_type": file_type,
                        "retention_days": retention_days,
                    },
                )[0]

                archive = self.register_archive(
                    object_key=object_key,
                    bucket=settings.minio.results_bucket,
                    file_type=file_type,
                    sample_id=sample.id,
                    size_bytes=size_bytes,
                    retention_days=retention_days,
                )
                archives.append(archive)

            logger.info(f"Archived {len(archives)} files for sample {sample_id}")
            return archives

    def archive_sample_report(
        self,
        sample_id: str,
        report_path: str,
    ) -> DataArchive:
        """Archive a clinical report."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if not sample:
                raise ValueError(f"Sample not found: {sample_id}")

            file_path_obj = Path(report_path)
            if not file_path_obj.exists():
                raise FileNotFoundError(f"Report not found: {report_path}")

            size_bytes = file_path_obj.stat().st_size
            retention_days = settings.retention.report_days if settings.retention.report_days > 0 else -1

            object_key = self.minio_client.upload_report(
                sample_id,
                report_path,
                metadata={
                    "retention_days": retention_days,
                },
            )

            archive = self.register_archive(
                object_key=object_key,
                bucket=settings.minio.reports_bucket,
                file_type="report",
                sample_id=sample.id,
                size_bytes=size_bytes,
                retention_days=retention_days,
            )

            return archive

    def archive_gvcf(
        self,
        sample_id: str,
        gvcf_path: str,
    ) -> DataArchive:
        """Archive a gVCF file for long-term storage."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if not sample:
                raise ValueError(f"Sample not found: {sample_id}")

            file_path_obj = Path(gvcf_path)
            if not file_path_obj.exists():
                raise FileNotFoundError(f"gVCF not found: {gvcf_path}")

            size_bytes = file_path_obj.stat().st_size
            retention_days = settings.retention.gvcf_days if settings.retention.gvcf_days > 0 else -1

            object_key = self.minio_client.archive_gvcf(
                sample_id,
                gvcf_path,
                metadata={
                    "retention_days": retention_days,
                },
            )

            archive = self.register_archive(
                object_key=object_key,
                bucket=settings.minio.results_bucket,
                file_type="gvcf",
                sample_id=sample.id,
                size_bytes=size_bytes,
                retention_days=retention_days,
            )

            return archive

    def get_retention_policy(self, file_type: str) -> Dict[str, Any]:
        """Get retention policy for a specific file type."""
        policies = {
            "raw_fastq": {
                "retention_days": settings.retention.raw_fastq_days,
                "description": "Raw sequencing data (FASTQ)",
                "action": "Delete after retention period",
            },
            "bam": {
                "retention_days": settings.retention.bam_days,
                "description": "Aligned reads (BAM/CRAM)",
                "action": "Delete after retention period",
            },
            "gvcf": {
                "retention_days": "Permanent" if settings.retention.gvcf_days < 0 else settings.retention.gvcf_days,
                "description": "Genomic VCF for reanalysis",
                "action": "Permanent archive",
            },
            "vcf": {
                "retention_days": 365,
                "description": "Variant call format files",
                "action": "Delete after retention period",
            },
            "report": {
                "retention_days": "Permanent" if settings.retention.report_days < 0 else settings.retention.report_days,
                "description": "Clinical reports (PDF/JSON)",
                "action": "Permanent archive",
            },
        }

        return policies.get(file_type, {
            "retention_days": 365,
            "description": "Unknown file type",
            "action": "Default retention policy",
        })

    def get_all_policies(self) -> Dict[str, Any]:
        """Get all retention policies."""
        return {
            "raw_fastq": self.get_retention_policy("raw_fastq"),
            "bam": self.get_retention_policy("bam"),
            "gvcf": self.get_retention_policy("gvcf"),
            "vcf": self.get_retention_policy("vcf"),
            "report": self.get_retention_policy("report"),
            "default_retention_days": 365,
        }

    def _determine_file_type(self, file_path: str) -> str:
        """Determine file type from extension."""
        path = Path(file_path)
        suffix = path.suffix.lower()
        suffixes = [s.lower() for s in path.suffixes]

        if ".g.vcf.gz" in suffixes or ".gvcf" in suffixes:
            return "gvcf"
        elif ".vcf.gz" in suffixes or suffix == ".vcf":
            return "vcf"
        elif ".bam" in suffixes or ".cram" in suffixes:
            return "bam"
        elif ".fastq.gz" in suffixes or ".fq.gz" in suffixes or suffix in (".fastq", ".fq"):
            return "raw_fastq"
        elif suffix in (".pdf", ".html"):
            return "report"
        elif suffix == ".json":
            return "json"
        elif suffix in (".txt", ".tsv", ".csv"):
            return "text"
        else:
            return "other"

    def _get_retention_days(self, file_type: str) -> int:
        """Get retention days for a file type."""
        policy = self.get_retention_policy(file_type)
        retention = policy.get("retention_days")
        if isinstance(retention, int):
            return retention
        return -1
