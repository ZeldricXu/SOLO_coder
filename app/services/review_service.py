import json
import csv
import io
from typing import List, Optional, Dict, Any, Tuple
from datetime import datetime, timedelta
from sqlalchemy import and_, or_, func

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.review import (
    ReviewTaskCreate,
    ReviewTaskUpdate,
    ReviewTaskCompleteRequest,
    ReviewStatusEnum,
    ReviewPriorityEnum,
    FieldCorrection,
    TrainingDataExportRequest,
)
from app.models.review import ReviewTask, ReviewComment
from app.models.extraction import ExtractedField, ExtractionResult
from app.models.document import Document, DocumentStatus
from app.core.database import get_sync_db

logger = get_logger(__name__)
settings = get_settings()


class ReviewService:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True

    def create_review_task(
        self,
        document_id: int,
        extraction_result_id: Optional[int] = None,
        priority: ReviewPriorityEnum = ReviewPriorityEnum.MEDIUM,
        deadline_hours: int = 24,
    ) -> ReviewTask:
        db = next(get_sync_db())
        try:
            existing = db.query(ReviewTask).filter(
                and_(
                    ReviewTask.document_id == document_id,
                    ReviewTask.status.in_([
                        ReviewStatusEnum.PENDING,
                        ReviewStatusEnum.ASSIGNED,
                        ReviewStatusEnum.IN_PROGRESS,
                    ])
                )
            ).first()

            if existing:
                logger.info(f"Review task already exists for document {document_id}")
                return existing

            db.query(ExtractedField).filter(
                ExtractedField.extraction_result_id == extraction_result_id
            ).all()

            low_confidence_fields = db.query(ExtractedField).filter(
                and_(
                    ExtractedField.extraction_result_id == extraction_result_id,
                    or_(
                        ExtractedField.is_low_confidence == True,
                        ExtractedField.validation_status == "error",
                        ExtractedField.validation_status == "warning",
                    )
                )
            ).all()

            fields_to_review = [
                {
                    "field_id": f.id,
                    "field_name": f.field_name,
                    "current_value": f.value,
                    "confidence": f.confidence,
                    "reason": "low_confidence" if f.is_low_confidence else f.validation_status,
                }
                for f in low_confidence_fields
            ]

            task_create = ReviewTaskCreate(
                document_id=document_id,
                extraction_result_id=extraction_result_id,
                priority=priority,
                fields_to_review=fields_to_review,
                deadline_at=datetime.utcnow() + timedelta(hours=deadline_hours),
            )

            task = ReviewTask(**task_create.model_dump())
            db.add(task)
            db.commit()
            db.refresh(task)

            doc = db.query(Document).filter(Document.id == document_id).first()
            if doc:
                doc.status = DocumentStatus.NEEDS_REVIEW
                db.commit()

            logger.info(
                f"Created review task {task.id} for document {document_id} "
                f"with {len(fields_to_review)} fields to review"
            )

            return task

        except Exception as e:
            logger.error(f"Failed to create review task: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_review_queue(
        self,
        status: Optional[ReviewStatusEnum] = None,
        priority: Optional[ReviewPriorityEnum] = None,
        assigned_to: Optional[str] = None,
        page: int = 1,
        page_size: int = 20,
    ) -> Tuple[List[Dict[str, Any]], int]:
        db = next(get_sync_db())
        try:
            query = db.query(ReviewTask).join(
                Document, ReviewTask.document_id == Document.id
            )

            if status:
                query = query.filter(ReviewTask.status == status)
            if priority:
                query = query.filter(ReviewTask.priority == priority)
            if assigned_to:
                query = query.filter(ReviewTask.assigned_to == assigned_to)

            query = query.order_by(
                ReviewTask.priority.desc(),
                ReviewTask.queued_at.asc(),
            )

            total = query.count()

            tasks = query.offset((page - 1) * page_size).limit(page_size).all()

            result = []
            for task in tasks:
                waiting_time = (datetime.utcnow() - task.queued_at).total_seconds()

                fields_to_review = task.fields_to_review or []
                field_names = [f.get("field_name") for f in fields_to_review] if isinstance(fields_to_review, list) else []

                result.append({
                    "task_id": task.id,
                    "document_id": task.document_id,
                    "document_filename": task.document.filename,
                    "document_type": task.document.document_type.value if task.document.document_type else "unknown",
                    "status": task.status.value,
                    "priority": task.priority.value,
                    "fields_count": len(fields_to_review) if isinstance(fields_to_review, list) else 0,
                    "fields_to_review": field_names,
                    "assigned_to": task.assigned_to,
                    "queued_at": task.queued_at,
                    "waiting_time_seconds": waiting_time,
                })

            return result, total

        except Exception as e:
            logger.error(f"Failed to get review queue: {e}", exc_info=True)
            raise
        finally:
            db.close()

    def get_review_task(self, task_id: int) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).first()
            if not task:
                return None

            extracted_fields = []
            if task.extraction_result_id:
                fields = db.query(ExtractedField).filter(
                    ExtractedField.extraction_result_id == task.extraction_result_id
                ).all()

                for f in fields:
                    extracted_fields.append({
                        "field_id": f.id,
                        "field_name": f.field_name,
                        "field_type": f.field_type.value,
                        "original_value": f.value,
                        "normalized_value": f.normalized_value,
                        "confidence": f.confidence,
                        "is_low_confidence": f.is_low_confidence,
                        "validation_status": f.validation_status.value if f.validation_status else "unchecked",
                        "validation_errors": f.validation_errors,
                        "validation_warnings": f.validation_warnings,
                        "suggested_value": f.suggested_value,
                        "page_number": f.page_number,
                        "bounding_box": f.bounding_box,
                        "reviewed": f.reviewed,
                        "reviewed_value": f.reviewed_value,
                    })

            result = {
                "task_id": task.id,
                "document_id": task.document_id,
                "extraction_result_id": task.extraction_result_id,
                "status": task.status.value,
                "priority": task.priority.value,
                "assigned_to": task.assigned_to,
                "fields_to_review": task.fields_to_review,
                "extracted_fields": extracted_fields,
                "queued_at": task.queued_at,
                "deadline_at": task.deadline_at,
                "review_notes": task.review_notes,
            }

            return result

        except Exception as e:
            logger.error(f"Failed to get review task {task_id}: {e}", exc_info=True)
            raise
        finally:
            db.close()

    def assign_review_task(
        self,
        task_id: int,
        assigned_to: str,
    ) -> ReviewTask:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).first()
            if not task:
                raise ValueError(f"Review task not found: {task_id}")

            task.assigned_to = assigned_to
            task.assigned_at = datetime.utcnow()
            task.status = ReviewStatusEnum.ASSIGNED

            db.commit()
            db.refresh(task)

            logger.info(f"Assigned review task {task_id} to {assigned_to}")
            return task

        except Exception as e:
            logger.error(f"Failed to assign review task {task_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def start_review_task(self, task_id: int, reviewer: str) -> ReviewTask:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).first()
            if not task:
                raise ValueError(f"Review task not found: {task_id}")

            if task.assigned_to and task.assigned_to != reviewer:
                raise PermissionError(f"Task {task_id} is assigned to {task.assigned_to}, not {reviewer}")

            task.status = ReviewStatusEnum.IN_PROGRESS
            task.started_at = datetime.utcnow()
            if not task.assigned_to:
                task.assigned_to = reviewer
                task.assigned_at = datetime.utcnow()

            db.commit()
            db.refresh(task)

            logger.info(f"Started review task {task_id} by {reviewer}")
            return task

        except Exception as e:
            logger.error(f"Failed to start review task {task_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def complete_review_task(
        self,
        request: ReviewTaskCompleteRequest,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == request.task_id).first()
            if not task:
                raise ValueError(f"Review task not found: {request.task_id}")

            task.status = ReviewStatusEnum.COMPLETED
            task.completed_at = datetime.utcnow()
            task.completed_by = request.completed_by
            task.is_correct = request.is_correct
            task.review_notes = request.review_notes
            task.has_quality_issues = request.has_quality_issues
            task.quality_issue_description = request.quality_issue_description

            if task.started_at:
                task.review_duration = (task.completed_at - task.started_at).total_seconds()

            correction_count = 0
            if request.corrections:
                correction_count = len(request.corrections)
                task.correction_count = correction_count

                for correction in request.corrections:
                    field = db.query(ExtractedField).filter(
                        ExtractedField.id == correction.field_id
                    ).first()
                    if field:
                        field.reviewed = True
                        field.reviewed_value = correction.new_value
                        field.reviewed_by = request.completed_by
                        field.reviewed_at = datetime.utcnow()
                        field.value = correction.new_value
                        field.normalized_value = correction.new_value

                        if correction.comment:
                            comment = ReviewComment(
                                review_task_id=task.id,
                                comment_text=correction.comment,
                                comment_type="correction",
                                field_name=correction.field_name,
                                old_value=correction.old_value,
                                new_value=correction.new_value,
                                commenter=request.completed_by,
                            )
                            db.add(comment)

            doc = db.query(Document).filter(Document.id == task.document_id).first()
            if doc:
                doc.status = DocumentStatus.COMPLETED

            db.commit()
            db.refresh(task)

            logger.info(
                f"Completed review task {task.id} by {request.completed_by}. "
                f"Corrections: {correction_count}, Correct: {request.is_correct}"
            )

            return {
                "task_id": task.id,
                "status": task.status.value,
                "completed_by": task.completed_by,
                "completed_at": task.completed_at,
                "review_duration_seconds": task.review_duration,
                "correction_count": correction_count,
                "is_correct": task.is_correct,
            }

        except Exception as e:
            logger.error(f"Failed to complete review task {request.task_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def add_review_comment(
        self,
        task_id: int,
        comment_text: str,
        commenter: str,
        field_name: Optional[str] = None,
        comment_type: str = "general",
    ) -> ReviewComment:
        db = next(get_sync_db())
        try:
            comment = ReviewComment(
                review_task_id=task_id,
                comment_text=comment_text,
                comment_type=comment_type,
                field_name=field_name,
                commenter=commenter,
            )
            db.add(comment)
            db.commit()
            db.refresh(comment)

            logger.info(f"Added comment to review task {task_id} by {commenter}")
            return comment

        except Exception as e:
            logger.error(f"Failed to add review comment: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_review_statistics(
        self,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            query = db.query(ReviewTask)

            if start_date:
                query = query.filter(ReviewTask.created_at >= start_date)
            if end_date:
                query = query.filter(ReviewTask.created_at <= end_date)

            total_tasks = query.count()
            pending_tasks = query.filter(ReviewTask.status == ReviewStatusEnum.PENDING).count()
            in_progress_tasks = query.filter(ReviewTask.status == ReviewStatusEnum.IN_PROGRESS).count()
            completed_tasks = query.filter(ReviewTask.status == ReviewStatusEnum.COMPLETED).count()
            escalated_tasks = query.filter(ReviewTask.status == ReviewStatusEnum.ESCALATED).count()

            high_priority_pending = query.filter(
                and_(
                    ReviewTask.status == ReviewStatusEnum.PENDING,
                    ReviewTask.priority == ReviewPriorityEnum.HIGH,
                )
            ).count()

            tasks_past_deadline = query.filter(
                and_(
                    ReviewTask.status.in_([
                        ReviewStatusEnum.PENDING,
                        ReviewStatusEnum.ASSIGNED,
                        ReviewStatusEnum.IN_PROGRESS,
                    ]),
                    ReviewTask.deadline_at < datetime.utcnow(),
                )
            ).count()

            completed_query = query.filter(ReviewTask.status == ReviewStatusEnum.COMPLETED)
            avg_review_time = None
            avg_corrections = 0.0

            if completed_tasks > 0:
                from sqlalchemy import func

                avg_review_time = db.query(
                    func.avg(ReviewTask.review_duration)
                ).filter(
                    ReviewTask.review_duration.isnot(None)
                ).scalar()

                avg_corrections = db.query(
                    func.avg(ReviewTask.correction_count)
                ).scalar() or 0.0

            return {
                "total_tasks": total_tasks,
                "pending_tasks": pending_tasks,
                "in_progress_tasks": in_progress_tasks,
                "completed_tasks": completed_tasks,
                "escalated_tasks": escalated_tasks,
                "average_review_time": avg_review_time,
                "average_corrections_per_task": float(avg_corrections),
                "high_priority_pending": high_priority_pending,
                "tasks_past_deadline": tasks_past_deadline,
            }

        except Exception as e:
            logger.error(f"Failed to get review statistics: {e}", exc_info=True)
            raise
        finally:
            db.close()

    def export_training_data(
        self,
        request: TrainingDataExportRequest,
    ) -> Tuple[str, bytes]:
        db = next(get_sync_db())
        try:
            query = db.query(ExtractedField).filter(
                ExtractedField.reviewed == True
            )

            if request.only_reviewed:
                query = query.filter(ExtractedField.reviewed_value.isnot(None))
            if not request.include_low_confidence:
                query = query.filter(ExtractedField.confidence >= settings.EXTRACTION_CONFIDENCE_THRESHOLD)
            if request.start_date:
                query = query.filter(ExtractedField.reviewed_at >= request.start_date)
            if request.end_date:
                query = query.filter(ExtractedField.reviewed_at <= request.end_date)
            if request.field_names:
                query = query.filter(ExtractedField.field_name.in_(request.field_names))

            fields = query.all()

            training_data = []
            for field in fields:
                item = {
                    "field_id": field.id,
                    "field_name": field.field_name,
                    "field_type": field.field_type.value,
                    "original_value": field.value,
                    "normalized_value": field.normalized_value,
                    "reviewed_value": field.reviewed_value,
                    "confidence": field.confidence,
                    "page_number": field.page_number,
                    "bounding_box": field.bounding_box,
                    "text_block": field.text_block,
                    "extraction_result_id": field.extraction_result_id,
                    "reviewed_by": field.reviewed_by,
                    "reviewed_at": field.reviewed_at.isoformat() if field.reviewed_at else None,
                }
                training_data.append(item)

            if request.export_format == "json":
                content = json.dumps(training_data, ensure_ascii=False, indent=2).encode("utf-8")
                filename = f"training_data_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.json"
            elif request.export_format == "csv":
                output = io.StringIO()
                writer = csv.DictWriter(
                    output,
                    fieldnames=list(training_data[0].keys()) if training_data else [],
                )
                writer.writeheader()
                for item in training_data:
                    item["bounding_box"] = json.dumps(item["bounding_box"]) if item["bounding_box"] else None
                    writer.writerow(item)
                content = output.getvalue().encode("utf-8")
                filename = f"training_data_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.csv"
            else:
                raise ValueError(f"Unsupported export format: {request.export_format}")

            logger.info(f"Exported {len(training_data)} training data records as {request.export_format}")

            for field in fields:
                field.is_used_for_training = True
            db.commit()

            return filename, content

        except Exception as e:
            logger.error(f"Failed to export training data: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def escalate_review_task(
        self,
        task_id: int,
        escalated_to: str,
        escalated_reason: str,
        escalated_by: str,
    ) -> ReviewTask:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).first()
            if not task:
                raise ValueError(f"Review task not found: {task_id}")

            task.escalated = True
            task.escalated_to = escalated_to
            task.escalated_reason = escalated_reason
            task.escalated_at = datetime.utcnow()
            task.status = ReviewStatusEnum.ESCALATED

            comment = ReviewComment(
                review_task_id=task_id,
                comment_text=f"Escalated by {escalated_by} to {escalated_to}: {escalated_reason}",
                comment_type="escalation",
                commenter=escalated_by,
            )
            db.add(comment)

            db.commit()
            db.refresh(task)

            logger.info(
                f"Escalated review task {task_id} from {escalated_by} to {escalated_to}. "
                f"Reason: {escalated_reason}"
            )

            return task

        except Exception as e:
            logger.error(f"Failed to escalate review task {task_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()
