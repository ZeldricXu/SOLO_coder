#!/usr/bin/env python3
import logging
import sys
import json
from typing import Optional, List
from pathlib import Path
from datetime import datetime
import argparse

from db.database import init_db
from config.settings import settings
from db.models import SampleType, SampleStatus, TaskStatus
from storage.repository import (
    SampleRepository,
    TaskRepository,
    VariantRepository,
)
from storage.minio_client import MinioClient
from storage.retention_policy import RetentionPolicyManager
from config.pipeline_config import PipelineDefinition
from pipeline.engine import PipelineEngine
from tasks import (
    submit_analysis_task,
    cleanup_expired_data_task,
    cleanup_old_raw_fastq_task,
    register_sample_data_task,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


def init_database():
    """Initialize database tables."""
    logger.info("Initializing database...")
    init_db()
    logger.info("Database initialized successfully")
    return True


def register_sample(
    sample_id: str,
    sample_type: str,
    r1_path: str,
    r2_path: str,
    patient_id: str = None,
    library_id: str = None,
    sequencing_platform: str = None,
    read_length: int = None,
    clinical_diagnosis: str = None,
    referring_physician: str = None,
    institution: str = None,
):
    """Register a new sample and upload FASTQ files."""
    logger.info(f"Registering sample: {sample_id}")

    try:
        sample_type_enum = SampleType(sample_type) if sample_type else SampleType.GERMLINE_WES

        sample = SampleRepository.create(
            sample_id=sample_id,
            sample_type=sample_type_enum,
            patient_id=patient_id,
            library_id=library_id,
            sequencing_platform=sequencing_platform,
            read_length=read_length,
            clinical_diagnosis=clinical_diagnosis,
            referring_physician=referring_physician,
            institution=institution,
        )

        if r1_path and r2_path:
            register_sample_data_task.delay(sample_id, r1_path, r2_path)
            logger.info(f"Sample data upload task submitted")

        print(json.dumps({
            "success": True,
            "sample_id": sample.sample_id,
            "id": sample.id,
            "status": sample.status.value,
            "registered_at": sample.created_at.isoformat(),
        }, indent=2))

        return sample

    except Exception as e:
        logger.error(f"Failed to register sample: {e}")
        sys.exit(1)


def submit_analysis(
    sample_id: str,
    with_vardict: bool = False,
    resume: bool = True,
    priority: str = "normal",
    run_local: bool = False,
):
    """Submit analysis task for a sample."""
    logger.info(f"Submitting analysis for sample: {sample_id}")

    try:
        if run_local:
            sample = SampleRepository.get_by_id(sample_id)
            if sample:
                task = TaskRepository.create(
                    sample_id=sample.id,
                    task_type="single_sample",
                    parameters={
                        "with_vardict": with_vardict,
                        "resume": resume,
                        "priority": priority,
                    },
                )

                pipeline_steps = PipelineDefinition.get_single_sample_pipeline(
                    sample_id, with_vardict=with_vardict)

                for step in pipeline_steps:
                    step.params["sample_id"] = sample_id
                    step.params["task_id"] = str(task.id)

                engine = PipelineEngine(
                    task_id=str(task.id),
                    sample_id=sample_id,
                    steps=pipeline_steps,
                    resume=resume,
                    max_parallel=settings.pipeline.max_parallel_chromosomes,
                )

                success = engine.run()

                if success:
                    TaskRepository.update_status(str(task.id), TaskStatus.COMPLETED)
                    SampleRepository.update_status(sample_id, SampleStatus.ANALYZED)
                    logger.info(f"Analysis completed successfully")
                else:
                    TaskRepository.update_status(str(task.id), TaskStatus.FAILED)
                    SampleRepository.update_status(sample_id, SampleStatus.FAILED)
                    logger.error(f"Analysis failed")

                print(json.dumps({
                    "success": success,
                    "sample_id": sample_id,
                    "task_id": task.id,
                }, indent=2))
            else:
                logger.error(f"Sample not found")
                sys.exit(1)
        else:
            result = submit_analysis_task.delay(
                sample_id=sample_id,
                with_vardict=with_vardict,
                resume=resume,
                priority=priority,
            )

            print(json.dumps({
                "success": True,
                "sample_id": sample_id,
                "task_id": result.id,
                "submitted": True,
            }, indent=2))

    except Exception as e:
        logger.error(f"Failed to submit analysis: {e}")
        sys.exit(1)


def list_samples(status: str = None):
    """List all samples with optional status filter."""
    try:
        from db.database import get_db_session
        from db.models import Sample

        with get_db_session() as db:
            query = db.query(Sample)
            if status:
                query = query.filter(Sample.status == status)
            samples = query.order_by(Sample.created_at.desc()).all()

            result = []
            for s in samples:
                result.append({
                    "id": s.id,
                    "sample_id": s.sample_id,
                    "sample_type": s.sample_type.value,
                    "status": s.status.value,
                    "patient_id": s.patient_id,
                    "created_at": s.created_at.isoformat() if s.created_at else None,
                })

            print(json.dumps(result, indent=2, default=str))

    except Exception as e:
        logger.error(f"Failed to list samples: {e}")
        sys.exit(1)


def list_tasks(sample_id: str = None, status: str = None):
    """List analysis tasks with optional filters."""
    try:
        from db.database import get_db_session
        from db.models import AnalysisTask

        with get_db_session() as db:
            query = db.query(AnalysisTask)
            if sample_id:
                from db.models import Sample
                sample = db.query(Sample).filter(Sample.sample_id == sample_id).first()
                if sample:
                    query = query.filter(AnalysisTask.sample_id == sample.id)
            if status:
                query = query.filter(AnalysisTask.status == status)
            tasks = query.order_by(AnalysisTask.created_at.desc()).all()

            result = []
            for t in tasks:
                result.append({
                    "id": t.id,
                    "task_type": t.task_type,
                    "status": t.status.value,
                    "created_at": t.created_at.isoformat() if t.created_at else None,
                    "completed_at": t.completed_at.isoformat() if t.completed_at else None,
                    "error_message": t.error_message,
                })

            print(json.dumps(result, indent=2, default=str))

    except Exception as e:
        logger.error(f"Failed to list tasks: {e}")
        sys.exit(1)


def get_sample_info(sample_id: str):
    """Get detailed information about a sample."""
    try:
        sample = SampleRepository.get_by_id(sample_id)
        if sample:
            data = {
                "id": sample.id,
                "sample_id": sample.sample_id,
                "sample_type": sample.sample_type.value,
                "status": sample.status.value,
                "patient_id": sample.patient_id,
                "clinical_diagnosis": sample.clinical_diagnosis,
                "created_at": sample.created_at.isoformat() if sample.created_at else None,
                "analysis_started_at": sample.analysis_started_at.isoformat() if sample.analysis_started_at else None,
                "analysis_completed_at": sample.analysis_completed_at.isoformat() if sample.analysis_completed_at else None,
                "qc_metrics": sample.qc_metrics,
            }
            print(json.dumps(data, indent=2, default=str))
        else:
            print(json.dumps({"error": "Sample not found"}, indent=2))
            sys.exit(1)

    except Exception as e:
        logger.error(f"Failed to get sample info: {e}")
        sys.exit(1)


def get_task_info(task_id: str):
    """Get detailed information about a task."""
    try:
        task = TaskRepository.get_by_id(task_id)
        if task:
            data = {
                "id": task.id,
                "task_type": task.task_type,
                "status": task.status.value,
                "created_at": task.created_at.isoformat() if task.created_at else None,
                "started_at": task.started_at.isoformat() if task.started_at else None,
                "completed_at": task.completed_at.isoformat() if task.completed_at else None,
                "parameters": task.parameters,
                "result_summary": task.result_summary,
                "error_message": task.error_message,
            }
            print(json.dumps(data, indent=2, default=str))
        else:
            print(json.dumps({"error": "Task not found"}, indent=2))
            sys.exit(1)

    except Exception as e:
        logger.error(f"Failed to get task info: {e}")
        sys.exit(1)


def run_cleanup(dry_run: bool = False, fastq_only: bool = False, days: int = None):
    """Run data retention cleanup."""
    try:
        if dry_run:
            logger.info("Dry run mode - no actual deletion")
            retention_manager = RetentionPolicyManager()
            from db.database import get_db_session
            from db.models import DataArchive
            with get_db_session() as db:
                expired = retention_manager._get_expired_files(db)
                result = {
                    "dry_run": True,
                    "files_to_delete": len(expired),
                    "files": [
                        {
                            "id": f.id,
                            "object_key": f.object_key,
                            "bucket": f.bucket,
                            "file_type": f.file_type,
                            "expires_at": f.expires_at.isoformat() if f.expires_at else None,
                        }
                        for f in expired
                    ],
                }
                print(json.dumps(result, indent=2, default=str))
        else:
            if fastq_only:
                result = cleanup_old_raw_fastq_task.delay(days=days)
            else:
                result = cleanup_expired_data_task.delay()
            print(json.dumps({"success": True, "task_id": result.id}, indent=2))

    except Exception as e:
        logger.error(f"Failed to run cleanup: {e}")
        sys.exit(1)


def show_pipeline(sample_id: str = None, with_vardict: str = None):
    """Display the pipeline DAG structure."""
    try:
        if sample_id:
            steps = PipelineDefinition.get_single_sample_pipeline(
                sample_id or "example",
                with_vardict=with_vardict or False,
            )
        else:
            steps = PipelineDefinition.get_single_sample_pipeline(
                "example", with_vardict=False)

        result = {
            "pipeline": "single_sample",
            "total_steps": len(steps),
            "steps": [
                {
                    "step_id": s.step_id,
                    "step_type": s.step_type.value,
                    "name": s.name,
                    "description": s.description,
                    "dependencies": s.dependencies,
                    "parallel_group": s.parallel_group,
                    "is_parallel": s.is_parallel,
                    "max_retries": s.max_retries,
                }
                for s in steps
            ],
        }
        print(json.dumps(result, indent=2))

    except Exception as e:
        logger.error(f"Failed to show pipeline: {e}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        description="Genome Variant Detection Pipeline CLI",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    init_parser = subparsers.add_parser("init", help="Initialize database")

    register_parser = subparsers.add_parser("register", help="Register a new sample")
    register_parser.add_argument("sample_id", help="Sample ID")
    register_parser.add_argument("--sample-type", default="GERMLINE_WES",
                              choices=["GERMLINE_WES", "GERMLINE_WGS", "TUMOR_WES", "TUMOR_WGS", "CFDNA"])
    register_parser.add_argument("--r1", help="Path to R1 FASTQ file")
    register_parser.add_argument("--r2", help="Path to R2 FASTQ file")
    register_parser.add_argument("--patient-id", help="Patient ID")
    register_parser.add_argument("--library-id", help="Library ID")
    register_parser.add_argument("--platform", help="Sequencing platform")
    register_parser.add_argument("--read-length", type=int, help="Read length")
    register_parser.add_argument("--diagnosis", help="Clinical diagnosis")
    register_parser.add_argument("--physician", help="Referring physician")
    register_parser.add_argument("--institution", help="Institution")

    analyze_parser = subparsers.add_parser("analyze", help="Submit analysis task")
    analyze_parser.add_argument("sample_id", help="Sample ID")
    analyze_parser.add_argument("--with-vardict", action="store_true", help="Include VarDict calling")
    analyze_parser.add_argument("--no-resume", action="store_true", help="Do not resume from checkpoint")
    analyze_parser.add_argument("--priority", default="normal", choices=["low", "normal", "high"])
    analyze_parser.add_argument("--local", action="store_true", help="Run locally without Celery")

    list_samples_parser = subparsers.add_parser("list-samples", help="List samples")
    list_samples_parser.add_argument("--status", help="Filter by status")

    list_tasks_parser = subparsers.add_parser("list-tasks", help="List tasks")
    list_tasks_parser.add_argument("--sample-id", help="Filter by sample ID")
    list_tasks_parser.add_argument("--status", help="Filter by status")

    sample_info_parser = subparsers.add_parser("sample-info", help="Get sample info")
    sample_info_parser.add_argument("sample_id", help="Sample ID")

    task_info_parser = subparsers.add_parser("task-info", help="Get task info")
    task_info_parser.add_argument("task_id", help="Task ID")

    cleanup_parser = subparsers.add_parser("cleanup", help="Run data cleanup")
    cleanup_parser.add_argument("--dry-run", action="store_true", help="Dry run mode")
    cleanup_parser.add_argument("--fastq-only", action="store_true", help="Only clean old FASTQ")
    cleanup_parser.add_argument("--days", type=int, help="Age in days for FASTQ cleanup")

    pipeline_parser = subparsers.add_parser("pipeline", help="Show pipeline DAG")
    pipeline_parser.add_argument("--sample-id", help="Sample ID for pipeline")
    pipeline_parser.add_argument("--with-vardict", action="store_true", help="Include VarDict")

    args = parser.parse_args()

    if args.command == "init":
        init_database()
    elif args.command == "register":
        register_sample(
            sample_id=args.sample_id,
            sample_type=args.sample_type,
            r1_path=args.r1,
            r2_path=args.r2,
            patient_id=args.patient_id,
            library_id=args.library_id,
            sequencing_platform=args.platform,
            read_length=args.read_length,
            clinical_diagnosis=args.diagnosis,
            referring_physician=args.physician,
            institution=args.institution,
        )
    elif args.command == "analyze":
        submit_analysis(
            sample_id=args.sample_id,
            with_vardict=args.with_vardict,
            resume=not args.no_resume,
            priority=args.priority,
            run_local=args.local,
        )
    elif args.command == "list-samples":
        list_samples(status=args.status)
    elif args.command == "list-tasks":
        list_tasks(sample_id=args.sample_id, status=args.status)
    elif args.command == "sample-info":
        get_sample_info(sample_id=args.sample_id)
    elif args.command == "task-info":
        get_task_info(task_id=args.task_id)
    elif args.command == "cleanup":
        run_cleanup(
            dry_run=args.dry_run,
            fastq_only=args.fastq_only,
            days=args.days,
        )
    elif args.command == "pipeline":
        show_pipeline(
            sample_id=args.sample_id,
            with_vardict=args.with_vardict,
        )
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
