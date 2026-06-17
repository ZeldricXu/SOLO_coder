import logging
import hashlib
from typing import List, Optional, Dict, Any
from pathlib import Path
from datetime import datetime

from sqlalchemy.orm import Session
from sqlalchemy import and_

from db.models import Sample, SampleType, SampleStatus, QCMetric
from db.database import get_db_session, get_db
from data_management.minio_client import MinIOClient

logger = logging.getLogger(__name__)


class SampleManager:
    """Manager for sample registration and tracking."""

    def __init__(self, minio_client: Optional[MinIOClient] = None):
        self.minio_client = minio_client or MinIOClient()

    def register_sample(
        self,
        sample_id: str,
        sample_type: SampleType,
        fastq_r1_path: str,
        fastq_r2_path: str,
        patient_id: Optional[str] = None,
        library_id: Optional[str] = None,
        sequencing_platform: Optional[str] = None,
        paired_end: bool = True,
        read_length: Optional[int] = None,
        phenotype_hpo: Optional[List[str]] = None,
        clinical_diagnosis: Optional[str] = None,
        referring_physician: Optional[str] = None,
        institution: Optional[str] = None,
    ) -> Sample:
        """
        Register a new sample and upload raw data to MinIO.

        Args:
            sample_id: Unique sample identifier
            sample_type: Type of sample (WES, WGS, PANEL, cfDNA)
            fastq_r1_path: Local path to R1 FASTQ file
            fastq_r2_path: Local path to R2 FASTQ file
            patient_id: Optional patient identifier
            library_id: Optional library identifier
            sequencing_platform: Optional sequencing platform
            paired_end: Whether paired-end sequencing
            read_length: Optional read length
            phenotype_hpo: Optional list of HPO terms
            clinical_diagnosis: Optional clinical diagnosis
            referring_physician: Optional referring physician name
            institution: Optional institution name

        Returns:
            Created Sample object
        """
        with get_db_session() as db:
            existing = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if existing:
                raise ValueError(f"Sample with ID '{sample_id}' already exists")

            r1_path_obj = Path(fastq_r1_path)
            r2_path_obj = Path(fastq_r2_path)

            if not r1_path_obj.exists():
                raise FileNotFoundError(f"FASTQ R1 file not found: {fastq_r1_path}")
            if not r2_path_obj.exists():
                raise FileNotFoundError(f"FASTQ R2 file not found: {fastq_r2_path}")

            md5_r1 = self._compute_md5(fastq_r1_path)
            md5_r2 = self._compute_md5(fastq_r2_path)

            minio_paths = self.minio_client.upload_sample_raw_data(
                sample_id=sample_id,
                fastq_r1_path=fastq_r1_path,
                fastq_r2_path=fastq_r2_path,
                metadata={
                    "patient_id": patient_id or "",
                    "sample_type": sample_type.value,
                    "md5_r1": md5_r1,
                    "md5_r2": md5_r2,
                },
            )

            sample = Sample(
                sample_id=sample_id,
                patient_id=patient_id,
                sample_type=sample_type,
                library_id=library_id,
                sequencing_platform=sequencing_platform,
                paired_end=paired_end,
                read_length=read_length,
                phenotype_hpo=phenotype_hpo or [],
                clinical_diagnosis=clinical_diagnosis,
                referring_physician=referring_physician,
                institution=institution,
                fastq_r1_path=minio_paths["fastq_r1"],
                fastq_r2_path=minio_paths["fastq_r2"],
                fastq_md5_r1=md5_r1,
                fastq_md5_r2=md5_r2,
                status=SampleStatus.REGISTERED,
            )

            db.add(sample)
            db.commit()
            db.refresh(sample)

            logger.info(f"Registered sample: {sample_id}")
            return sample

    def get_sample(self, sample_id: str) -> Optional[Sample]:
        """Get a sample by ID."""
        with get_db_session() as db:
            return db.query(Sample).filter(Sample.sample_id == sample_id).first()

    def get_samples_by_patient(self, patient_id: str) -> List[Sample]:
        """Get all samples for a patient."""
        with get_db_session() as db:
            return db.query(Sample).filter(Sample.patient_id == patient_id).all()

    def get_samples_by_status(self, status: SampleStatus) -> List[Sample]:
        """Get all samples with a given status."""
        with get_db_session() as db:
            return db.query(Sample).filter(Sample.status == status).all()

    def update_sample_status(self, sample_id: str, status: SampleStatus) -> Optional[Sample]:
        """Update sample status."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if sample:
                sample.status = status
                if status == SampleStatus.ANALYZING:
                    sample.analysis_started_at = datetime.utcnow()
                elif status in (SampleStatus.ANALYZED, SampleStatus.REPORTED):
                    sample.analysis_completed_at = datetime.utcnow()
                elif status == SampleStatus.ARCHIVED:
                    sample.archived_at = datetime.utcnow()
                db.commit()
                db.refresh(sample)
                logger.info(f"Updated sample {sample_id} status to {status.value}")
            return sample

    def update_sample_qc_metrics(
        self,
        sample_id: str,
        qc_data: Dict[str, Any],
        step_type: str = "sequencing",
    ) -> Optional[QCMetric]:
        """Update QC metrics for a sample."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if not sample:
                return None

            qc_metric = QCMetric(
                sample_id=sample.id,
                step_type=step_type,
                total_reads=qc_data.get("total_reads"),
                total_bases=qc_data.get("total_bases"),
                q20_bases=qc_data.get("q20_bases"),
                q30_bases=qc_data.get("q30_bases"),
                gc_content=qc_data.get("gc_content"),
                adapter_content=qc_data.get("adapter_content"),
                duplication_rate=qc_data.get("duplication_rate"),
                mapped_reads=qc_data.get("mapped_reads"),
                mapping_rate=qc_data.get("mapping_rate"),
                properly_paired=qc_data.get("properly_paired"),
                proper_pair_rate=qc_data.get("proper_pair_rate"),
                mean_insert_size=qc_data.get("mean_insert_size"),
                on_target_rate=qc_data.get("on_target_rate"),
                mean_coverage=qc_data.get("mean_coverage"),
                coverage_1x=qc_data.get("coverage_1x"),
                coverage_10x=qc_data.get("coverage_10x"),
                coverage_20x=qc_data.get("coverage_20x"),
                coverage_30x=qc_data.get("coverage_30x"),
                transition_transversion_ratio=qc_data.get("ti_tv_ratio"),
                het_hom_ratio=qc_data.get("het_hom_ratio"),
                metrics_json=qc_data,
            )

            db.add(qc_metric)

            sample.qc_metrics = qc_data

            qc_pass = self._evaluate_qc(qc_data, step_type)
            if step_type == "sequencing":
                sample.status = SampleStatus.QC_PASSED if qc_pass else SampleStatus.QC_FAILED

            db.commit()
            db.refresh(qc_metric)

            return qc_metric

    def update_sample_variant_count(self, sample_id: str, total_variants: int) -> Optional[Sample]:
        """Update total variant count for a sample."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if sample:
                sample.total_variants = total_variants
                db.commit()
                db.refresh(sample)
            return sample

    def update_report_path(self, sample_id: str, report_path: str) -> Optional[Sample]:
        """Update report path for a sample."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if sample:
                sample.report_path = report_path
                sample.status = SampleStatus.REPORTED
                db.commit()
                db.refresh(sample)
            return sample

    def download_raw_data(self, sample_id: str, output_dir: str) -> Dict[str, str]:
        """Download raw FASTQ files for a sample."""
        sample = self.get_sample(sample_id)
        if not sample:
            raise ValueError(f"Sample not found: {sample_id}")

        output_dir_path = Path(output_dir)
        output_dir_path.mkdir(parents=True, exist_ok=True)

        r1_local = str(output_dir_path / Path(sample.fastq_r1_path).name)
        r2_local = str(output_dir_path / Path(sample.fastq_r2_path).name)

        self.minio_client.download_file(
            bucket="raw-sequencing-data",
            object_key=sample.fastq_r1_path,
            local_path=r1_local,
        )
        self.minio_client.download_file(
            bucket="raw-sequencing-data",
            object_key=sample.fastq_r2_path,
            local_path=r2_local,
        )

        return {"fastq_r1": r1_local, "fastq_r2": r2_local}

    def list_samples(
        self,
        skip: int = 0,
        limit: int = 100,
        status: Optional[SampleStatus] = None,
        sample_type: Optional[SampleType] = None,
    ) -> Dict[str, Any]:
        """List samples with optional filtering."""
        with get_db_session() as db:
            query = db.query(Sample)

            if status:
                query = query.filter(Sample.status == status)
            if sample_type:
                query = query.filter(Sample.sample_type == sample_type)

            total = query.count()
            samples = query.order_by(Sample.created_at.desc()).offset(skip).limit(limit).all()

            return {
                "total": total,
                "skip": skip,
                "limit": limit,
                "samples": samples,
            }

    def archive_sample(self, sample_id: str) -> Optional[Sample]:
        """Mark a sample as archived."""
        return self.update_sample_status(sample_id, SampleStatus.ARCHIVED)

    def _compute_md5(self, file_path: str, chunk_size: int = 8192) -> str:
        """Compute MD5 hash of a file."""
        md5 = hashlib.md5()
        with open(file_path, "rb") as f:
            while chunk := f.read(chunk_size):
                md5.update(chunk)
        return md5.hexdigest()

    def _evaluate_qc(self, qc_data: Dict[str, Any], step_type: str) -> bool:
        """Evaluate if QC metrics pass thresholds."""
        if step_type == "sequencing":
            q30_rate = qc_data.get("q30_rate", 0)
            mapping_rate = qc_data.get("mapping_rate", 0)
            duplication_rate = qc_data.get("duplication_rate", 100)

            return (
                q30_rate >= 85
                and mapping_rate >= 90
                and duplication_rate <= 25
            )

        return True
