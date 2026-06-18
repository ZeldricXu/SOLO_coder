import logging
from typing import List, Dict, Any, Optional
from datetime import datetime

from celery import Task
from celery.utils.log import get_task_logger

from celery_app import celery
from config.settings import settings
from config.pipeline_config import PipelineDefinition
from pipeline.engine import PipelineEngine
from storage.repository import (
    SampleRepository,
    TaskRepository,
    VariantRepository,
)
from storage.retention_policy import RetentionPolicyManager
from storage.minio_client import MinioClient
from db.models import (
    SampleStatus,
    TaskStatus,
    StepStatus,
    SampleType,
    AnalysisTask,
)

logger = get_task_logger(__name__)


class PipelineTask(Task):
    abstract = True

    def on_failure(self, exc, task_id, args, kwargs, einfo):
        logger.error(f"Task {task_id} failed: {exc}")
        if "task_id" in kwargs:
            analysis_task_id = kwargs["task_id"]
            TaskRepository.update_status(analysis_task_id, TaskStatus.FAILED)
            TaskRepository.update_error_message(analysis_task_id, str(exc))
        super().on_failure(exc, task_id, args, kwargs, einfo)

    def on_success(self, retval, task_id, args, kwargs):
        logger.info(f"Task {task_id} succeeded")
        super().on_success(retval, task_id, args, kwargs, einfo)


@celery.task(bind=True, base=PipelineTask, name="run_analysis")
def run_analysis_task(
    self,
    sample_id: str,
    task_id: str,
    resume: bool = True,
    with_vardict: bool = False,
    max_parallel: int = None,
) -> Dict[str, Any]:
    logger.info(f"Starting analysis for sample {sample_id}, task {task_id}, resume={resume}")

    try:
        sample = SampleRepository.get_by_id(sample_id)
        if not sample:
            raise ValueError(f"Sample {sample_id} not found")

        task = TaskRepository.get_by_id(task_id)
        if not task:
            raise ValueError(f"Task {task_id} not found")

        if task.status == TaskStatus.COMPLETED and resume:
            logger.info(f"Task {task_id} already completed, skipping")
            return {"status": "already_completed", "task_id": task_id}

        TaskRepository.update_status(task_id, TaskStatus.RUNNING)
        SampleRepository.update_status(sample_id, SampleStatus.ANALYZING)

        minio_client = MinioClient()
        work_dir = settings.pipeline.work_dir

        r1_local = f"{work_dir}/{task_id}/{sample_id}_R1.fastq.gz"
        r2_local = f"{work_dir}/{task_id}/{sample_id}_R2.fastq.gz"

        if sample.fastq_r1_path and sample.fastq_r1_path.startswith("minio://"):
            logger.info(f"Downloading FASTQ files from MinIO for sample {sample_id}")
            parts = sample.fastq_r1_path.replace("minio://", "").split("/", 1)
            bucket, r1_obj = parts[0], parts[1]
            parts2 = sample.fastq_r2_path.replace("minio://", "").split("/", 1)
            r2_obj = parts2[1]

            minio_client.download_file(bucket, r1_obj, r1_local)
            minio_client.download_file(bucket, r2_obj, r2_local)

        pipeline_steps = PipelineDefinition.get_single_sample_pipeline(
            sample_id, with_vardict=with_vardict
        )

        for step in pipeline_steps:
            step.params["sample_id"] = sample_id
            step.params["task_id"] = task_id
            if step.step_type == "fastqc" or step.step_type == "fastp":
                step.inputs = [r1_local, r2_local]

        engine = PipelineEngine(
            task_id=task_id,
            sample_id=sample_id,
            steps=pipeline_steps,
            resume=resume,
            max_parallel=max_parallel or settings.pipeline.max_parallel_chromosomes,
        )

        success = engine.run()

        if success:
            TaskRepository.update_status(task_id, TaskStatus.COMPLETED)
            SampleRepository.update_status(sample_id, SampleStatus.ANALYZED)
            logger.info(f"Analysis completed successfully for sample {sample_id}")
        else:
            TaskRepository.update_status(task_id, TaskStatus.FAILED)
            SampleRepository.update_status(sample_id, SampleStatus.FAILED)
            logger.error(f"Analysis failed for sample {sample_id}")

        return {
            "success": success,
            "task_id": task_id,
            "sample_id": sample_id,
            "completed_at": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.exception(f"Error in analysis task {task_id}")
        TaskRepository.update_status(task_id, TaskStatus.FAILED)
        TaskRepository.update_error_message(task_id, str(e))
        SampleRepository.update_status(sample_id, SampleStatus.FAILED)
        raise


@celery.task(bind=True, base=PipelineTask, name="run_cohort_genotyping")
def run_cohort_genotyping_task(
    self,
    cohort_id: int,
    task_id: str,
    resume: bool = True,
) -> Dict[str, Any]:
    logger.info(f"Starting cohort genotyping for cohort {cohort_id}, task {task_id}")

    try:
        task = TaskRepository.get_by_id(task_id)
        if not task:
            raise ValueError(f"Task {task_id} not found")

        TaskRepository.update_status(task_id, TaskStatus.RUNNING)

        cohort_gvcf_paths = VariantRepository.get_cohort_gvcfs(cohort_id)
        if not cohort_gvcf_paths:
            raise ValueError(f"No gVCFs found for cohort {cohort_id}")

        genotyping_steps = PipelineDefinition.get_cohort_genotyping_pipeline(
            cohort_id, cohort_gvcf_paths
        )

        for step in genotyping_steps:
            step.params["cohort_id"] = cohort_id
            step.params["task_id"] = task_id

        engine = PipelineEngine(
            task_id=task_id,
            sample_id=f"cohort_{cohort_id}",
            steps=genotyping_steps,
            resume=resume,
            max_parallel=4,
        )

        success = engine.run()

        if success:
            TaskRepository.update_status(task_id, TaskStatus.COMPLETED)
            logger.info(f"Cohort genotyping completed for cohort {cohort_id}")
        else:
            TaskRepository.update_status(task_id, TaskStatus.FAILED)
            logger.error(f"Cohort genotyping failed for cohort {cohort_id}")

        return {
            "success": success,
            "task_id": task_id,
            "cohort_id": cohort_id,
            "completed_at": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.exception(f"Error in cohort genotyping task {task_id}")
        TaskRepository.update_status(task_id, TaskStatus.FAILED)
        TaskRepository.update_error_message(task_id, str(e))
        raise


@celery.task(name="cleanup_expired_data")
def cleanup_expired_data_task() -> Dict[str, Any]:
    logger.info("Starting expired data cleanup task")

    try:
        retention_manager = RetentionPolicyManager()
        result = retention_manager.cleanup_expired_files()

        logger.info(f"Cleanup completed: {result}")
        return {
            "success": True,
            "cleanup_result": result,
            "completed_at": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.exception("Error in data cleanup task")
        return {
            "success": False,
            "error": str(e),
            "completed_at": datetime.now().isoformat(),
        }


@celery.task(name="cleanup_old_raw_fastq")
def cleanup_old_raw_fastq_task(days: int = None) -> Dict[str, Any]:
    logger.info(f"Starting old raw FASTQ cleanup task (days={days})")

    try:
        retention_manager = RetentionPolicyManager()
        result = retention_manager.cleanup_old_raw_fastq(days=days)

        logger.info(f"FASTQ cleanup completed: {result}")
        return {
            "success": True,
            "cleanup_result": result,
            "completed_at": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.exception("Error in FASTQ cleanup task")
        return {
            "success": False,
            "error": str(e),
            "completed_at": datetime.now().isoformat(),
        }


@celery.task(name="register_sample_data")
def register_sample_data_task(
    sample_id: str,
    r1_path: str,
    r2_path: str,
) -> Dict[str, Any]:
    logger.info(f"Registering sample data for {sample_id}")

    try:
        minio_client = MinioClient()
        retention_manager = RetentionPolicyManager()

        uploaded = minio_client.upload_sample_fastq(sample_id, r1_path, r2_path)

        sample = SampleRepository.get_by_id(sample_id)
        if sample:
            SampleRepository.update_fastq_paths(
                sample_id, uploaded["r1_object"], uploaded["r2_object"]
            )

            retention_manager.register_file_for_retention(
                object_key=uploaded["r1_object"],
                bucket=settings.minio.raw_data_bucket,
                file_type="fastq_r1",
                sample_id=sample.id,
                size_bytes=uploaded.get("r1_size", 0),
            )
            retention_manager.register_file_for_retention(
                object_key=uploaded["r2_object"],
                bucket=settings.minio.raw_data_bucket,
                file_type="fastq_r2",
                sample_id=sample.id,
                size_bytes=uploaded.get("r2_size", 0),
            )

        return {
            "success": True,
            "sample_id": sample_id,
            "uploaded": uploaded,
            "completed_at": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.exception(f"Error registering sample data for {sample_id}")
        return {
            "success": False,
            "sample_id": sample_id,
            "error": str(e),
            "completed_at": datetime.now().isoformat(),
        }


@celery.task(name="archive_analysis_results")
def archive_analysis_results_task(
    sample_id: str,
    result_files: List[str],
) -> Dict[str, Any]:
    logger.info(f"Archiving analysis results for {sample_id}")

    try:
        minio_client = MinioClient()
        archived = minio_client.upload_analysis_results(sample_id, result_files)

        return {
            "success": True,
            "sample_id": sample_id,
            "archived_files": archived,
            "completed_at": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.exception(f"Error archiving results for {sample_id}")
        return {
            "success": False,
            "sample_id": sample_id,
            "error": str(e),
            "completed_at": datetime.now().isoformat(),
        }


@celery.task(name="submit_analysis")
def submit_analysis_task(
    sample_id: str,
    task_type: str = "single_sample",
    with_vardict: bool = False,
    resume: bool = True,
    priority: str = "normal",
) -> Dict[str, Any]:
    logger.info(f"Submitting analysis task for sample {sample_id}")

    try:
        sample = SampleRepository.get_by_id(sample_id)
        if not sample:
            raise ValueError(f"Sample {sample_id} not found")

        task_priority = 0
        if priority == "high":
            task_priority = 5
        elif priority == "low":
            task_priority = -5

        task = TaskRepository.create(
            sample_id=sample.id,
            task_type=task_type,
            parameters={
                "with_vardict": with_vardict,
                "resume": resume,
                "priority": priority,
            },
        )

        run_analysis_task.apply_async(
            args=[sample_id, str(task.id)],
            kwargs={"with_vardict": with_vardict, "resume": resume},
            priority=task_priority,
            task_id=f"analysis_{task.id}",
        )

        return {
            "success": True,
            "sample_id": sample_id,
            "task_id": task.id,
            "submitted_at": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.exception(f"Error submitting analysis for {sample_id}")
        raise
