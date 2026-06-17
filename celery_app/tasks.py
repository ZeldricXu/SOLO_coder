import logging
import json
import traceback
from pathlib import Path
from datetime import datetime
from typing import Dict, Any, Optional

from celery import Task, current_task
from sqlalchemy import and_

from celery_app.celery_app import celery_app
from db.models import SampleStatus, TaskStatus, StepStatus, AnalysisTask
from db.database import get_db_session
from pipeline.runner import PipelineRunner, PipelineContext, PipelineResult
from pipeline.dag import PipelineDAG
from config.pipeline_config import PipelineDefinition, PipelineStepType
from config.settings import settings
from data_management.sample_manager import SampleManager
from data_management.task_manager import TaskManager
from data_management.retention_policy import RetentionPolicyManager

logger = logging.getLogger(__name__)


class PipelineTask(Task):
    """Base class for pipeline tasks with database tracking."""

    def on_failure(self, exc, task_id, args, kwargs, einfo):
        """Handle task failure."""
        super().on_failure(exc, task_id, args, kwargs, einfo)
        task_manager = TaskManager()
        try:
            analysis_task_id = kwargs.get("analysis_task_id") or (args[0] if args else None)
            if analysis_task_id:
                task_manager.update_task_status(
                    analysis_task_id,
                    TaskStatus.FAILED,
                    error_message=str(exc),
                )
        except Exception as e:
            logger.error(f"Failed to update task status on failure: {e}")


@celery_app.task(base=PipelineTask, bind=True, name="celery_app.tasks.run_analysis_pipeline")
def run_analysis_pipeline(self, analysis_task_id: str, resume: bool = True) -> Dict[str, Any]:
    """
    Run the full analysis pipeline for a task.

    Args:
        analysis_task_id: Analysis task identifier
        resume: Whether to resume from checkpoint if available

    Returns:
        Execution summary
    """
    task_manager = TaskManager()
    sample_manager = SampleManager()
    retention_manager = RetentionPolicyManager()

    try:
        task = task_manager.get_task(analysis_task_id)
        if not task:
            raise ValueError(f"Task not found: {analysis_task_id}")

        celery_task_id = self.request.id

        task_manager.update_task_status(
            analysis_task_id,
            TaskStatus.RUNNING,
            celery_task_id=celery_task_id,
        )

        sample = task.sample
        if not sample:
            raise ValueError(f"No sample associated with task {analysis_task_id}")

        sample_manager.update_sample_status(sample.sample_id, SampleStatus.ANALYZING)

        logger.info(f"Starting pipeline execution for task: {analysis_task_id}, sample: {sample.sample_id}")

        output_dir = Path(settings.pipeline.work_dir) / analysis_task_id
        output_dir.mkdir(parents=True, exist_ok=True)

        sample_fastq = sample_manager.download_raw_data(sample.sample_id, str(output_dir))

        task_steps = task_manager.get_task_steps(analysis_task_id)
        steps_config = PipelineDefinition.get_single_sample_pipeline(sample.sample_id)

        def progress_callback(step_id: str, step_name: str, status: str, progress: float, message: str = ""):
            """Callback for pipeline progress updates."""
            if current_task:
                current_task.update_state(
                    state="PROGRESS",
                    meta={
                        "step_id": step_id,
                        "step_name": step_name,
                        "status": status,
                        "progress": progress,
                        "message": message,
                    },
                )

            step_db_status = StepStatus.RUNNING
            if status == "completed":
                step_db_status = StepStatus.COMPLETED
            elif status == "failed":
                step_db_status = StepStatus.FAILED
            elif status == "skipped":
                step_db_status = StepStatus.SKIPPED

            if step_id:
                task_manager.update_step_status(
                    analysis_task_id,
                    step_id,
                    step_db_status,
                    std_out=message[:5000] if message else None,
                )

            task_manager.update_task_progress(
                analysis_task_id,
                progress * 100,
                current_step=step_name if step_name else None,
            )

        work_dir = output_dir
        context = PipelineContext(
            sample_id=sample.sample_id,
            work_dir=Path(work_dir),
            temp_dir=Path(work_dir) / "tmp",
            log_dir=Path(work_dir) / "logs",
            fastq_r1=sample_fastq["fastq_r1"],
            fastq_r2=sample_fastq["fastq_r2"],
            reference_genome=settings.reference.hg38_fasta,
            params={},
        )
        context.temp_dir.mkdir(parents=True, exist_ok=True)
        context.log_dir.mkdir(parents=True, exist_ok=True)

        dag = PipelineDAG(steps_config)

        runner = PipelineRunner(
            dag=dag,
            context=context,
            resume=resume,
            max_parallel=settings.pipeline.max_parallel,
            progress_callback=progress_callback,
        )

        result = runner.run()

        if result.success:
            report_files = [f for f in result.output_files if f.endswith((".pdf", ".json"))]
            for report_file in report_files:
                if report_file.endswith(".pdf"):
                    retention_manager.archive_sample_report(sample.sample_id, report_file)

            gvcf_files = [f for f in result.output_files if ".g.vcf" in f or ".gvcf" in f]
            for gvcf_file in gvcf_files:
                retention_manager.archive_gvcf(sample.sample_id, gvcf_file)

            other_results = [
                f for f in result.output_files
                if f not in report_files and f not in gvcf_files
            ]
            if other_results:
                retention_manager.archive_sample_results(sample.sample_id, other_results)

            for report_file in report_files:
                if report_file.endswith(".json"):
                    sample_manager.update_report_path(sample.sample_id, report_file)
                    break

            total_variants = result.summary.get("total_variants", 0)
            sample_manager.update_sample_variant_count(sample.sample_id, total_variants)

            sample_manager.update_sample_status(sample.sample_id, SampleStatus.REPORTED)

            task_manager.update_task_results(
                analysis_task_id,
                result.output_files,
                result.summary,
            )

            task_manager.update_task_status(
                analysis_task_id,
                TaskStatus.COMPLETED,
            )

            return {
                "task_id": analysis_task_id,
                "sample_id": sample.sample_id,
                "success": True,
                "output_files": result.output_files,
                "summary": result.summary,
                "duration_seconds": result.duration_seconds,
            }
        else:
            error_msg = result.error_message or "Pipeline failed without specific error message"
            sample_manager.update_sample_status(sample.sample_id, SampleStatus.FAILED)

            failed_step_id = None
            for step in task_steps:
                if step.status == StepStatus.FAILED:
                    failed_step_id = step.step_id
                    if step.error_message:
                        error_msg = f"Step {step.step_name} failed: {step.error_message}"
                    break

            task_manager.update_task_status(
                analysis_task_id,
                TaskStatus.FAILED,
                error_message=error_msg,
            )

            return {
                "task_id": analysis_task_id,
                "sample_id": sample.sample_id,
                "success": False,
                "error": error_msg,
                "failed_step": failed_step_id,
                "duration_seconds": result.duration_seconds,
            }

    except Exception as e:
        logger.error(f"Pipeline execution failed for task {analysis_task_id}: {e}", exc_info=True)
        tb = traceback.format_exc()

        try:
            task_manager.update_task_status(
                analysis_task_id,
                TaskStatus.FAILED,
                error_message=f"{str(e)}\n\n{tb[:2000]}",
            )
        except Exception:
            pass

        return {
            "task_id": analysis_task_id,
            "success": False,
            "error": str(e),
            "traceback": tb,
        }


@celery_app.task(name="celery_app.tasks.cleanup_expired_data")
def cleanup_expired_data() -> Dict[str, Any]:
    """
    Periodic task to clean up expired data according to retention policies.
    Runs daily at 2 AM.
    """
    logger.info("Starting expired data cleanup")

    try:
        retention_manager = RetentionPolicyManager()
        summary = retention_manager.cleanup_expired_data()

        logger.info(f"Cleanup completed: {len(summary.get('raw_fastq_deleted', []))} raw files deleted, "
                    f"{len(summary.get('archives_deleted', []))} archives deleted, "
                    f"{summary.get('total_size_freed', 0) / (1024*1024*1024):.2f} GB freed")

        return summary
    except Exception as e:
        logger.error(f"Cleanup failed: {e}", exc_info=True)
        return {"error": str(e)}


@celery_app.task(name="celery_app.tasks.check_and_queue_pending_tasks")
def check_and_queue_pending_tasks() -> Dict[str, Any]:
    """
    Periodic task to check for pending analysis tasks and queue them.
    Runs every 5 minutes.
    """
    task_manager = TaskManager()
    results = {
        "queued": [],
        "already_queued": 0,
        "skipped": 0,
    }

    try:
        pending_tasks = task_manager.get_pending_tasks(limit=10)
        logger.info(f"Found {len(pending_tasks)} pending tasks")

        with get_db_session() as db:
            running_count = db.query(AnalysisTask).filter(
                AnalysisTask.status == TaskStatus.RUNNING
            ).count()

            max_running = settings.pipeline.max_parallel
            available_slots = max_running - running_count

            if available_slots <= 0:
                results["skipped"] = len(pending_tasks)
                logger.info(f"No available slots (max {max_running} running), skipping")
                return results

            for task in pending_tasks[:available_slots]:
                if task.status != TaskStatus.PENDING:
                    results["already_queued"] += 1
                    continue

                task_manager.queue_task(task.task_id)

                run_analysis_pipeline.apply_async(
                    args=[task.task_id],
                    kwargs={"resume": True},
                    task_id=f"pipeline_{task.task_id}",
                )

                results["queued"].append(task.task_id)
                logger.info(f"Queued task {task.task_id} for execution")

        return results

    except Exception as e:
        logger.error(f"Failed to check pending tasks: {e}", exc_info=True)
        results["error"] = str(e)
        return results


@celery_app.task(name="celery_app.tasks.retry_failed_task")
def retry_failed_task(analysis_task_id: str) -> Dict[str, Any]:
    """Retry a failed analysis task."""
    task_manager = TaskManager()

    try:
        task = task_manager.retry_failed_task(analysis_task_id)
        if not task:
            return {"error": f"Cannot retry task {analysis_task_id}"}

        run_analysis_pipeline.apply_async(
            args=[analysis_task_id],
            kwargs={"resume": True},
            task_id=f"pipeline_retry_{analysis_task_id}",
        )

        logger.info(f"Queued retry for task {analysis_task_id}")

        return {
            "task_id": analysis_task_id,
            "status": "queued_for_retry",
        }

    except Exception as e:
        logger.error(f"Failed to retry task {analysis_task_id}: {e}", exc_info=True)
        return {"error": str(e)}


@celery_app.task(name="celery_app.tasks.run_joint_genotyping")
def run_joint_genotyping(cohort_id: str, sample_ids: list, task_name: Optional[str] = None) -> Dict[str, Any]:
    """Run joint genotyping for a cohort of samples."""
    task_manager = TaskManager()

    try:
        task = task_manager.create_cohort_task(
            cohort_id=cohort_id,
            sample_ids=sample_ids,
            task_name=task_name,
        )

        run_analysis_pipeline.apply_async(
            args=[task.task_id],
            kwargs={"resume": True},
            task_id=f"joint_{task.task_id}",
        )

        return {
            "task_id": task.task_id,
            "cohort_id": cohort_id,
            "sample_count": len(sample_ids),
            "status": "queued",
        }

    except Exception as e:
        logger.error(f"Failed to run joint genotyping for cohort {cohort_id}: {e}", exc_info=True)
        return {"error": str(e)}
