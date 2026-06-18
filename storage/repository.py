from typing import List, Optional, Dict, Any
from datetime import datetime
import logging
import json

from db.database import get_db_session
from db.models import (
    Sample,
    AnalysisTask,
    TaskStep,
    Variant,
    Cohort,
    CohortSample,
    QCMetric,
    SampleStatus,
    TaskStatus,
    StepStatus,
    ACMGClassification,
    SampleType,
)

logger = logging.getLogger(__name__)


class SampleRepository:
    @staticmethod
    def create(
        sample_id: str,
        sample_type: SampleType,
        patient_id: str = None,
        library_id: str = None,
        sequencing_platform: str = None,
        paired_end: bool = True,
        read_length: int = None,
        phenotype_hpo: List[str] = None,
        clinical_diagnosis: str = None,
        referring_physician: str = None,
        institution: str = None,
        fastq_r1_path: str = None,
        fastq_r2_path: str = None,
        fastq_md5_r1: str = None,
        fastq_md5_r2: str = None,
    ) -> Sample:
        with get_db_session() as db:
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
                fastq_r1_path=fastq_r1_path,
                fastq_r2_path=fastq_r2_path,
                fastq_md5_r1=fastq_md5_r1,
                fastq_md5_r2=fastq_md5_r2,
                status=SampleStatus.REGISTERED,
            )
            db.add(sample)
            db.commit()
            db.refresh(sample)
            logger.info(f"Created sample: {sample_id}")
            return sample

    @staticmethod
    def get_by_id(sample_id: str) -> Optional[Sample]:
        with get_db_session() as db:
            return db.query(Sample).filter(Sample.sample_id == sample_id).first()

    @staticmethod
    def get_by_db_id(db_id: int) -> Optional[Sample]:
        with get_db_session() as db:
            return db.query(Sample).filter(Sample.id == db_id).first()

    @staticmethod
    def update_status(sample_id: str, status: SampleStatus) -> bool:
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if sample:
                sample.status = status
                if status == SampleStatus.ANALYZING:
                    sample.analysis_started_at = datetime.utcnow()
                elif status in [SampleStatus.ANALYZED, SampleStatus.REPORTED]:
                    sample.analysis_completed_at = datetime.utcnow()
                elif status == SampleStatus.ARCHIVED:
                    sample.archived_at = datetime.utcnow()
                db.commit()
                return True
            return False

    @staticmethod
    def update_qc_metrics(sample_id: str, qc_metrics: Dict[str, Any]) -> bool:
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if sample:
                sample.qc_metrics = qc_metrics
                db.commit()
                return True
            return False

    @staticmethod
    def update_fastq_paths(
        sample_id: str,
        fastq_r1_path: str,
        fastq_r2_path: str,
        fastq_md5_r1: str = None,
        fastq_md5_r2: str = None,
    ) -> bool:
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if sample:
                sample.fastq_r1_path = fastq_r1_path
                sample.fastq_r2_path = fastq_r2_path
                sample.fastq_md5_r1 = fastq_md5_r1
                sample.fastq_md5_r2 = fastq_md5_r2
                db.commit()
                return True
            return False

    @staticmethod
    def list_by_status(status: SampleStatus) -> List[Sample]:
        with get_db_session() as db:
            return db.query(Sample).filter(Sample.status == status).all()

    @staticmethod
    def search(
        sample_id: str = None,
        patient_id: str = None,
        institution: str = None,
        status: SampleStatus = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[Sample]:
        with get_db_session() as db:
            query = db.query(Sample)
            if sample_id:
                query = query.filter(Sample.sample_id.contains(sample_id))
            if patient_id:
                query = query.filter(Sample.patient_id.contains(patient_id))
            if institution:
                query = query.filter(Sample.institution.contains(institution))
            if status:
                query = query.filter(Sample.status == status)
            return query.order_by(Sample.received_at.desc()).offset(offset).limit(limit).all()

    @staticmethod
    def update_variant_count(sample_id: str, count: int) -> bool:
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if sample:
                sample.total_variants = count
                db.commit()
                return True
            return False


class TaskRepository:
    @staticmethod
    def create(
        task_id: str,
        sample_db_id: int,
        task_name: str = None,
        pipeline_version: str = "1.0.0",
        reference_genome: str = "hg38",
        priority: int = 0,
        input_files: List[str] = None,
    ) -> AnalysisTask:
        with get_db_session() as db:
            task = AnalysisTask(
                task_id=task_id,
                task_name=task_name or f"Analysis_{task_id}",
                sample_id=sample_db_id,
                pipeline_version=pipeline_version,
                reference_genome=reference_genome,
                priority=priority,
                input_files=input_files or [],
                status=TaskStatus.PENDING,
            )
            db.add(task)
            db.commit()
            db.refresh(task)
            logger.info(f"Created analysis task: {task_id}")
            return task

    @staticmethod
    def get_by_id(task_id: str) -> Optional[AnalysisTask]:
        with get_db_session() as db:
            return db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()

    @staticmethod
    def update_status(task_id: str, status: TaskStatus, error_message: str = None) -> bool:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if task:
                task.status = status
                task.error_message = error_message
                if status == TaskStatus.RUNNING:
                    task.started_at = datetime.utcnow()
                elif status == TaskStatus.COMPLETED:
                    task.completed_at = datetime.utcnow()
                elif status == TaskStatus.FAILED:
                    task.failed_at = datetime.utcnow()
                db.commit()
                return True
            return False

    @staticmethod
    def update_progress(task_id: str, current_step: str, progress_percent: float) -> bool:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if task:
                task.current_step = current_step
                task.progress_percent = progress_percent
                db.commit()
                return True
            return False

    @staticmethod
    def update_celery_task_id(task_id: str, celery_task_id: str) -> bool:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if task:
                task.celery_task_id = celery_task_id
                db.commit()
                return True
            return False

    @staticmethod
    def add_step(
        task_id: str,
        step_id: str,
        step_name: str,
        step_type: str,
        parameters: Dict[str, Any] = None,
        input_files: List[str] = None,
        max_retries: int = 3,
    ) -> TaskStep:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                raise ValueError(f"Task {task_id} not found")

            step = TaskStep(
                task_id=task.id,
                step_id=step_id,
                step_name=step_name,
                step_type=step_type,
                parameters=parameters or {},
                input_files=input_files or [],
                max_retries=max_retries,
                status=StepStatus.PENDING,
            )
            db.add(step)
            db.commit()
            db.refresh(step)
            return step

    @staticmethod
    def update_step_status(
        task_id: str,
        step_id: str,
        status: StepStatus,
        output_files: List[str] = None,
        metrics: Dict[str, Any] = None,
        std_out: str = None,
        std_err: str = None,
        error_message: str = None,
        duration_seconds: float = None,
    ) -> Optional[TaskStep]:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return None

            step = (
                db.query(TaskStep)
                .filter(TaskStep.task_id == task.id, TaskStep.step_id == step_id)
                .first()
            )
            if step:
                if status == StepStatus.RUNNING:
                    step.started_at = datetime.utcnow()
                    step.retry_count += 1
                elif status in [StepStatus.COMPLETED, StepStatus.FAILED]:
                    step.completed_at = datetime.utcnow()
                    if step.started_at:
                        step.duration_seconds = (
                            datetime.utcnow() - step.started_at
                        ).total_seconds()

                step.status = status
                if output_files is not None:
                    step.output_files = output_files
                if metrics is not None:
                    step.metrics = metrics
                if std_out is not None:
                    step.std_out = std_out
                if std_err is not None:
                    step.std_err = std_err
                if error_message is not None:
                    step.error_message = error_message
                if duration_seconds is not None:
                    step.duration_seconds = duration_seconds

                db.commit()
                db.refresh(step)
                return step
            return None

    @staticmethod
    def get_step(task_id: str, step_id: str) -> Optional[TaskStep]:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return None
            return (
                db.query(TaskStep)
                .filter(TaskStep.task_id == task.id, TaskStep.step_id == step_id)
                .first()
            )

    @staticmethod
    def get_steps(task_id: str) -> List[TaskStep]:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return []
            return (
                db.query(TaskStep)
                .filter(TaskStep.task_id == task.id)
                .order_by(TaskStep.id)
                .all()
            )

    @staticmethod
    def get_completed_step_outputs(task_id: str) -> Dict[str, List[str]]:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return {}
            steps = (
                db.query(TaskStep)
                .filter(
                    TaskStep.task_id == task.id,
                    TaskStep.status == StepStatus.COMPLETED,
                )
                .all()
            )
            return {step.step_id: step.output_files for step in steps if step.output_files}

    @staticmethod
    def list_pending(priority_min: int = 0) -> List[AnalysisTask]:
        with get_db_session() as db:
            return (
                db.query(AnalysisTask)
                .filter(
                    AnalysisTask.status == TaskStatus.PENDING,
                    AnalysisTask.priority >= priority_min,
                )
                .order_by(AnalysisTask.priority.desc(), AnalysisTask.created_at)
                .all()
            )

    @staticmethod
    def update_result_summary(task_id: str, summary: Dict[str, Any]) -> bool:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if task:
                task.result_summary = summary
                db.commit()
                return True
            return False

    @staticmethod
    def add_output_files(task_id: str, output_files: List[str]) -> bool:
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if task:
                current = task.output_files or []
                current.extend(output_files)
                task.output_files = current
                db.commit()
                return True
            return False


class VariantRepository:
    @staticmethod
    def bulk_create(sample_db_id: int, variants: List[Dict[str, Any]]) -> int:
        with get_db_session() as db:
            count = 0
            for var_data in variants:
                variant = Variant(
                    sample_id=sample_db_id,
                    variant_id=var_data.get("variant_id"),
                    chromosome=var_data.get("chromosome"),
                    position=var_data.get("position"),
                    ref=var_data.get("ref"),
                    alt=var_data.get("alt"),
                    variant_type=var_data.get("variant_type"),
                    genotype=var_data.get("genotype"),
                    genotype_quality=var_data.get("genotype_quality"),
                    depth=var_data.get("depth"),
                    allele_depth=var_data.get("allele_depth"),
                    allele_frequency=var_data.get("allele_frequency"),
                    gene=var_data.get("gene"),
                    transcript=var_data.get("transcript"),
                    hgvsc=var_data.get("hgvsc"),
                    hgvsp=var_data.get("hgvsp"),
                    consequence=var_data.get("consequence"),
                    impact=var_data.get("impact"),
                    gnomad_af=var_data.get("gnomad_af"),
                    thousandg_af=var_data.get("thousandg_af"),
                    exac_af=var_data.get("exac_af"),
                    cadd_score=var_data.get("cadd_score"),
                    revel_score=var_data.get("revel_score"),
                    sift_score=var_data.get("sift_score"),
                    polyphen2_score=var_data.get("polyphen2_score"),
                    clinvar_id=var_data.get("clinvar_id"),
                    clinvar_clinsig=var_data.get("clinvar_clinsig"),
                    clinvar_review_status=var_data.get("clinvar_review_status"),
                    acmg_classification=var_data.get("acmg_classification"),
                    acmg_criteria=var_data.get("acmg_criteria", []),
                    acmg_score=var_data.get("acmg_score"),
                    is_secondary_finding=var_data.get("is_secondary_finding", False),
                    is_candidate=var_data.get("is_candidate", False),
                )
                db.add(variant)
                count += 1
            db.commit()
            logger.info(f"Bulk created {count} variants for sample {sample_db_id}")
            return count

    @staticmethod
    def get_by_sample(
        sample_db_id: int,
        classification: ACMGClassification = None,
        chromosome: str = None,
        gene: str = None,
        limit: int = 1000,
    ) -> List[Variant]:
        with get_db_session() as db:
            query = db.query(Variant).filter(Variant.sample_id == sample_db_id)
            if classification:
                query = query.filter(Variant.acmg_classification == classification)
            if chromosome:
                query = query.filter(Variant.chromosome == chromosome)
            if gene:
                query = query.filter(Variant.gene == gene)
            return query.order_by(Variant.chromosome, Variant.position).limit(limit).all()

    @staticmethod
    def get_pathogenic_variants(sample_db_id: int) -> List[Variant]:
        with get_db_session() as db:
            return (
                db.query(Variant)
                .filter(
                    Variant.sample_id == sample_db_id,
                    Variant.acmg_classification.in_(
                        [ACMGClassification.PATHOGENIC, ACMGClassification.LIKELY_PATHOGENIC]
                    ),
                )
                .order_by(Variant.acmg_score.desc())
                .all()
            )

    @staticmethod
    def get_secondary_findings(sample_db_id: int) -> List[Variant]:
        with get_db_session() as db:
            return (
                db.query(Variant)
                .filter(
                    Variant.sample_id == sample_db_id,
                    Variant.is_secondary_finding == True,
                    Variant.acmg_classification.in_(
                        [ACMGClassification.PATHOGENIC, ACMGClassification.LIKELY_PATHOGENIC]
                    ),
                )
                .all()
            )

    @staticmethod
    def get_secondary_finding_genes() -> List[str]:
        return [
            "APC", "MYBPC3", "MYH7", "TNNT2", "TNNI3", "TPM1", "MYL3", "MYL2",
            "ACTC1", "PLN", "RBM20", "TTN", "DSC2", "DSG2", "DSP", "PKP2",
            "LMNA", "SCN5A", "KCNH2", "KCNQ1", "RYR2", "CASQ2", "CALM1",
            "CALM2", "CALM3", "TRDN", "BRCA1", "BRCA2", "PALB2", "TP53",
            "PTEN", "STK11", "MLH1", "MSH2", "MSH6", "PMS2", "EPCAM",
            "APC", "MUTYH", "NTRK1", "NTRK2", "NTRK3", "RET", "VHL",
            "SDHB", "SDHD", "SDHAF2", "MAX", "FH", "FLCN", "MEN1", "CDC73",
            "PRKAR1A", "SMARCB1", "NF2", "TSC1", "TSC2", "NF1",
        ]


class QCMetricRepository:
    @staticmethod
    def create(sample_db_id: int, step_type: str, metrics: Dict[str, Any]) -> QCMetric:
        with get_db_session() as db:
            qc = QCMetric(
                sample_id=sample_db_id,
                step_type=step_type,
                total_reads=metrics.get("total_reads"),
                total_bases=metrics.get("total_bases"),
                q20_bases=metrics.get("q20_bases"),
                q30_bases=metrics.get("q30_bases"),
                gc_content=metrics.get("gc_content"),
                adapter_content=metrics.get("adapter_content"),
                duplication_rate=metrics.get("duplication_rate"),
                mapped_reads=metrics.get("mapped_reads"),
                mapping_rate=metrics.get("mapping_rate"),
                properly_paired=metrics.get("properly_paired"),
                proper_pair_rate=metrics.get("proper_pair_rate"),
                mean_insert_size=metrics.get("mean_insert_size"),
                on_target_rate=metrics.get("on_target_rate"),
                mean_coverage=metrics.get("mean_coverage"),
                coverage_1x=metrics.get("coverage_1x"),
                coverage_10x=metrics.get("coverage_10x"),
                coverage_20x=metrics.get("coverage_20x"),
                coverage_30x=metrics.get("coverage_30x"),
                transition_transversion_ratio=metrics.get("transition_transversion_ratio"),
                het_hom_ratio=metrics.get("het_hom_ratio"),
                metrics_json=metrics,
            )
            db.add(qc)
            db.commit()
            db.refresh(qc)
            return qc

    @staticmethod
    def get_by_sample(sample_db_id: int) -> List[QCMetric]:
        with get_db_session() as db:
            return (
                db.query(QCMetric)
                .filter(QCMetric.sample_id == sample_db_id)
                .order_by(QCMetric.created_at)
                .all()
            )
