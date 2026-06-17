import logging
import uuid
from typing import List, Optional, Dict, Any
from datetime import datetime

from sqlalchemy.orm import Session

from db.models import AnalysisTask, TaskStep, TaskStatus, StepStatus, Sample
from db.database import get_db_session
from config.pipeline_config import PipelineDefinition, PipelineStep, StepStatus as ConfigStepStatus

logger = logging.getLogger(__name__)


class TaskManager:
    """Manager for analysis task creation and tracking."""

    def create_analysis_task(
        self,
        sample_id: str,
        task_name: Optional[str] = None,
        pipeline_version: str = "1.0.0",
        reference_genome: str = "hg38",
        with_vardict: bool = False,
        priority: int = 0,
        params: Optional[Dict[str, Any]] = None,
    ) -> AnalysisTask:
        """
        Create a new analysis task for a sample.

        Args:
            sample_id: Sample identifier
            task_name: Optional task name
            pipeline_version: Pipeline version
            reference_genome: Reference genome build
            with_vardict: Whether to include VarDict for low-frequency variant calling
            priority: Task priority (higher = more urgent)
            params: Additional pipeline parameters

        Returns:
            Created AnalysisTask object
        """
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if not sample:
                raise ValueError(f"Sample not found: {sample_id}")

            task_id = f"task_{sample_id}_{uuid.uuid4().hex[:8]}"
            task = AnalysisTask(
                task_id=task_id,
                task_name=task_name or f"Analysis for {sample_id}",
                sample_id=sample.id,
                pipeline_version=pipeline_version,
                reference_genome=reference_genome,
                status=TaskStatus.PENDING,
                priority=priority,
                input_files=[sample.fastq_r1_path, sample.fastq_r2_path],
            )

            db.add(task)
            db.flush()

            steps = PipelineDefinition.get_single_sample_pipeline(sample_id, with_vardict)
            for step in steps:
                task_step = TaskStep(
                    task_id=task.id,
                    step_id=step.step_id,
                    step_name=step.name,
                    step_type=step.step_type.value,
                    status=StepStatus.PENDING,
                    max_retries=step.max_retries,
                    input_files=step.inputs,
                    output_files=step.outputs,
                    parameters=step.params,
                )
                db.add(task_step)

            db.commit()
            db.refresh(task)

            logger.info(f"Created analysis task {task_id} for sample {sample_id}")
            return task

    def create_cohort_task(
        self,
        cohort_id: str,
        sample_ids: List[str],
        task_name: Optional[str] = None,
        pipeline_version: str = "1.0.0",
        priority: int = 0,
    ) -> AnalysisTask:
        """Create a joint genotyping task for a cohort of samples."""
        with get_db_session() as db:
            samples = db.query(Sample).filter(Sample.sample_id.in_(sample_ids)).all()
            if len(samples) != len(sample_ids):
                missing = set(sample_ids) - {s.sample_id for s in samples}
                raise ValueError(f"Samples not found: {missing}")

            first_sample = samples[0]
            task_id = f"cohort_{cohort_id}_{uuid.uuid4().hex[:8]}"

            gvcf_inputs = []
            for sample in samples:
                gvcf_path = f"{sample.sample_id}.g.vcf.gz"
                gvcf_inputs.append(gvcf_path)

            task = AnalysisTask(
                task_id=task_id,
                task_name=task_name or f"Joint genotyping for cohort {cohort_id}",
                sample_id=first_sample.id,
                pipeline_version=pipeline_version,
                reference_genome="hg38",
                status=TaskStatus.PENDING,
                priority=priority,
                input_files=gvcf_inputs,
            )

            db.add(task)
            db.flush()

            steps = PipelineDefinition.get_joint_genotyping_pipeline(cohort_id, sample_ids)
            for step in steps:
                task_step = TaskStep(
                    task_id=task.id,
                    step_id=step.step_id,
                    step_name=step.name,
                    step_type=step.step_type.value,
                    status=StepStatus.PENDING,
                    max_retries=step.max_retries,
                    input_files=step.inputs,
                    output_files=step.outputs,
                    parameters=step.params,
                )
                db.add(task_step)

            db.commit()
            db.refresh(task)

            logger.info(f"Created cohort analysis task {task_id} for {len(samples)} samples")
            return task

    def get_task(self, task_id: str) -> Optional[AnalysisTask]:
        """Get a task by ID."""
        with get_db_session() as db:
            return db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()

    def get_tasks_by_sample(self, sample_id: str) -> List[AnalysisTask]:
        """Get all tasks for a sample."""
        with get_db_session() as db:
            sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
            if not sample:
                return []
            return (
                db.query(AnalysisTask)
                .filter(AnalysisTask.sample_id == sample.id)
                .order_by(AnalysisTask.created_at.desc())
                .all()
            )

    def get_pending_tasks(self, limit: int = 10) -> List[AnalysisTask]:
        """Get pending tasks ordered by priority."""
        with get_db_session() as db:
            return (
                db.query(AnalysisTask)
                .filter(AnalysisTask.status == TaskStatus.PENDING)
                .order_by(AnalysisTask.priority.desc(), AnalysisTask.created_at.asc())
                .limit(limit)
                .all()
            )

    def update_task_status(
        self,
        task_id: str,
        status: TaskStatus,
        error_message: Optional[str] = None,
        celery_task_id: Optional[str] = None,
    ) -> Optional[AnalysisTask]:
        """Update task status."""
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return None

            task.status = status
            if error_message:
                task.error_message = error_message
            if celery_task_id:
                task.celery_task_id = celery_task_id

            if status == TaskStatus.RUNNING:
                task.started_at = datetime.utcnow()
            elif status == TaskStatus.COMPLETED:
                task.completed_at = datetime.utcnow()
                task.failed_at = None
            elif status == TaskStatus.FAILED:
                task.failed_at = datetime.utcnow()
                task.completed_at = None

            db.commit()
            db.refresh(task)

            logger.info(f"Updated task {task_id} status to {status.value}")
            return task

    def update_task_progress(
        self,
        task_id: str,
        progress_percent: float,
        current_step: Optional[str] = None,
    ) -> Optional[AnalysisTask]:
        """Update task progress."""
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return None

            task.progress_percent = progress_percent
            if current_step:
                task.current_step = current_step

            db.commit()
            db.refresh(task)
            return task

    def update_step_status(
        self,
        task_id: str,
        step_id: str,
        status: StepStatus,
        output_files: Optional[List[str]] = None,
        metrics: Optional[Dict[str, Any]] = None,
        std_out: Optional[str] = None,
        std_err: Optional[str] = None,
        error_message: Optional[str] = None,
        duration_seconds: Optional[float] = None,
    ) -> Optional[TaskStep]:
        """Update step execution status."""
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return None

            task_step = (
                db.query(TaskStep)
                .filter(TaskStep.task_id == task.id, TaskStep.step_id == step_id)
                .first()
            )
            if not task_step:
                return None

            task_step.status = status

            if status == StepStatus.RUNNING:
                task_step.started_at = datetime.utcnow()
                task_step.retry_count += 1
            elif status == StepStatus.COMPLETED:
                task_step.completed_at = datetime.utcnow()
                if duration_seconds:
                    task_step.duration_seconds = duration_seconds
                if output_files:
                    task_step.output_files = output_files
            elif status == StepStatus.FAILED:
                task_step.completed_at = datetime.utcnow()
                if duration_seconds:
                    task_step.duration_seconds = duration_seconds
                if error_message:
                    task_step.error_message = error_message

            if metrics:
                task_step.metrics = metrics
            if std_out:
                task_step.std_out = (task_step.std_out or "") + std_out[-10000:]
            if std_err:
                task_step.std_err = (task_step.std_err or "") + std_err[-10000:]

            db.commit()
            db.refresh(task_step)

            return task_step

    def update_task_results(
        self,
        task_id: str,
        output_files: List[str],
        result_summary: Dict[str, Any],
    ) -> Optional[AnalysisTask]:
        """Update task with final results."""
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return None

            task.output_files = output_files
            task.result_summary = result_summary
            db.commit()
            db.refresh(task)
            return task

    def get_task_steps(self, task_id: str) -> List[TaskStep]:
        """Get all steps for a task."""
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task:
                return []
            return sorted(task.steps, key=lambda s: s.created_at)

    def list_tasks(
        self,
        skip: int = 0,
        limit: int = 100,
        status: Optional[TaskStatus] = None,
    ) -> Dict[str, Any]:
        """List tasks with optional filtering."""
        with get_db_session() as db:
            query = db.query(AnalysisTask)

            if status:
                query = query.filter(AnalysisTask.status == status)

            total = query.count()
            tasks = query.order_by(AnalysisTask.created_at.desc()).offset(skip).limit(limit).all()

            return {
                "total": total,
                "skip": skip,
                "limit": limit,
                "tasks": tasks,
            }

    def queue_task(self, task_id: str) -> Optional[AnalysisTask]:
        """Mark task as queued for execution."""
        return self.update_task_status(task_id, TaskStatus.QUEUED)

    def cancel_task(self, task_id: str) -> Optional[AnalysisTask]:
        """Cancel a pending or queued task."""
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task or task.status not in (TaskStatus.PENDING, TaskStatus.QUEUED):
                return None

            task.status = TaskStatus.CANCELLED
            db.commit()
            db.refresh(task)
            logger.info(f"Cancelled task {task_id}")
            return task

    def retry_failed_task(self, task_id: str) -> Optional[AnalysisTask]:
        """Reset a failed task for retry."""
        with get_db_session() as db:
            task = db.query(AnalysisTask).filter(AnalysisTask.task_id == task_id).first()
            if not task or task.status != TaskStatus.FAILED:
                return None

            for step in task.steps:
                if step.status == StepStatus.FAILED:
                    step.status = StepStatus.PENDING
                    step.error_message = None

            task.status = TaskStatus.PENDING
            task.error_message = None
            task.failed_at = None

            db.commit()
            db.refresh(task)
            logger.info(f"Reset task {task_id} for retry")
            return task

    def get_task_summary(self, task_id: str) -> Optional[Dict[str, Any]]:
        """Get a summary of task execution."""
        task = self.get_task(task_id)
        if not task:
            return None

        steps = self.get_task_steps(task_id)
        step_summary = []
        for step in steps:
            step_summary.append({
                "step_id": step.step_id,
                "step_name": step.step_name,
                "step_type": step.step_type,
                "status": step.status.value,
                "retry_count": step.retry_count,
                "max_retries": step.max_retries,
                "duration_seconds": step.duration_seconds,
                "error_message": step.error_message,
                "metrics": step.metrics,
            })

        return {
            "task_id": task.task_id,
            "task_name": task.task_name,
            "sample_id": task.sample.sample_id if task.sample else None,
            "status": task.status.value,
            "progress_percent": task.progress_percent,
            "current_step": task.current_step,
            "priority": task.priority,
            "created_at": task.created_at,
            "started_at": task.started_at,
            "completed_at": task.completed_at,
            "failed_at": task.failed_at,
            "error_message": task.error_message,
            "steps": step_summary,
            "output_files": task.output_files,
            "result_summary": task.result_summary,
        }
