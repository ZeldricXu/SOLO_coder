import json
import csv
import io
from typing import List, Optional, Dict, Any, Tuple
from datetime import datetime, timedelta
from sqlalchemy import and_, or_, func, desc
from sqlalchemy.types import Integer

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
    BatchReviewConfirmRequest,
    FieldHighlightInfo,
    BatchReviewTaskResponse,
    DailyReviewStats,
    ReviewEfficiencyStatistics,
)
from app.models.review import ReviewTask, ReviewComment, ReviewStatus, ReviewPriority
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
        priority: ReviewPriority = ReviewPriority.MEDIUM,
        deadline_hours: int = 24,
    ) -> ReviewTask:
        db = next(get_sync_db())
        try:
            existing = db.query(ReviewTask).filter(
                and_(
                    ReviewTask.document_id == document_id,
                    ReviewTask.status.in_([
                        ReviewStatus.PENDING,
                        ReviewStatus.ASSIGNED,
                        ReviewStatus.IN_PROGRESS,
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
        status: Optional[ReviewStatus] = None,
        priority: Optional[ReviewPriority] = None,
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
            task.status = ReviewStatus.ASSIGNED

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

    def claim_review_task(self, task_id: int, reviewer_id: str) -> ReviewTask:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).first()
            if not task:
                raise ValueError(f"Review task not found: {task_id}")

            current_version = task.version

            if task.status not in [ReviewStatus.PENDING, ReviewStatus.ASSIGNED]:
                raise ValueError(f"Task {task_id} is not available for claiming (status: {task.status})")

            from sqlalchemy import update
            stmt = (
                update(ReviewTask)
                .where(
                    and_(
                        ReviewTask.id == task_id,
                        ReviewTask.version == current_version,
                    )
                )
                .values(
                    status=ReviewStatus.IN_PROGRESS,
                    assigned_to=reviewer_id,
                    assigned_at=datetime.utcnow(),
                    started_at=datetime.utcnow(),
                    version=current_version + 1,
                )
                .execution_options(synchronize_session=False)
            )

            result = db.execute(stmt)
            db.commit()

            if result.rowcount == 0:
                raise ValueError(
                    f"Task {task_id} has been claimed by another reviewer. "
                    "Please refresh and try again."
                )

            db.refresh(task)
            logger.info(f"Review task {task_id} claimed by {reviewer_id}")
            return task

        except ValueError:
            raise
        except Exception as e:
            logger.error(f"Failed to claim review task {task_id}: {e}", exc_info=True)
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

            task.status = ReviewStatus.IN_PROGRESS
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
        *args,
        **kwargs,
    ) -> Dict[str, Any]:
        if args and isinstance(args[0], ReviewTaskCompleteRequest):
            request = args[0]
        elif len(args) >= 2:
            task_id = args[0]
            update = args[1]
            reviewer = args[2] if len(args) >= 3 else kwargs.get("reviewer", "unknown")

            corrections = []
            if hasattr(update, 'model_dump'):
                update_dict = update.model_dump()
            else:
                update_dict = dict(update)

            request = ReviewTaskCompleteRequest(
                task_id=task_id,
                completed_by=reviewer,
                is_correct=update_dict.get("is_correct", True),
                review_notes=update_dict.get("review_notes", None),
                has_quality_issues=update_dict.get("has_quality_issues", False),
                quality_issue_description=update_dict.get("quality_issue_description", None),
                corrections=corrections,
            )
        elif "request" in kwargs:
            request = kwargs["request"]
        else:
            raise ValueError("Invalid arguments for complete_review_task")

        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == request.task_id).first()
            if not task:
                raise ValueError(f"Review task not found: {request.task_id}")

            current_version = task.version
            from sqlalchemy import update as sqlalchemy_update
            stmt = (
                sqlalchemy_update(ReviewTask)
                .where(
                    and_(
                        ReviewTask.id == request.task_id,
                        ReviewTask.version == current_version,
                    )
                )
                .values(
                    status=ReviewStatus.COMPLETED,
                    completed_at=datetime.utcnow(),
                    completed_by=request.completed_by,
                    is_correct=request.is_correct,
                    review_notes=request.review_notes,
                    has_quality_issues=request.has_quality_issues,
                    quality_issue_description=request.quality_issue_description,
                    version=current_version + 1,
                )
                .execution_options(synchronize_session=False)
            )

            result = db.execute(stmt)
            db.commit()

            if result.rowcount == 0:
                raise ValueError(
                    f"Task {request.task_id} has been modified by another user. "
                    "Please refresh and try again."
                )

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
            pending_tasks = query.filter(ReviewTask.status == ReviewStatus.PENDING).count()
            in_progress_tasks = query.filter(ReviewTask.status == ReviewStatus.IN_PROGRESS).count()
            completed_tasks = query.filter(ReviewTask.status == ReviewStatus.COMPLETED).count()
            escalated_tasks = query.filter(ReviewTask.status == ReviewStatus.ESCALATED).count()

            high_priority_pending = query.filter(
                and_(
                    ReviewTask.status == ReviewStatus.PENDING,
                    ReviewTask.priority == ReviewPriority.HIGH,
                )
            ).count()

            tasks_past_deadline = query.filter(
                and_(
                    ReviewTask.status.in_([
                        ReviewStatus.PENDING,
                        ReviewStatus.ASSIGNED,
                        ReviewStatus.IN_PROGRESS,
                    ]),
                    ReviewTask.deadline_at < datetime.utcnow(),
                )
            ).count()

            completed_query = query.filter(ReviewTask.status == ReviewStatus.COMPLETED)
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

    def get_review_efficiency_statistics(
        self,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        days: int = 30,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            if not start_date:
                start_date = datetime.utcnow() - timedelta(days=days)
            if not end_date:
                end_date = datetime.utcnow()

            completed_tasks = db.query(ReviewTask).filter(
                and_(
                    ReviewTask.status == ReviewStatus.COMPLETED,
                    ReviewTask.completed_at >= start_date,
                    ReviewTask.completed_at <= end_date,
                )
            ).all()

            total_reviewed = len(completed_tasks)
            pass_count = sum(1 for t in completed_tasks if t.is_correct)
            tasks_with_corrections = sum(1 for t in completed_tasks if (t.correction_count or 0) > 0)
            total_corrections = sum(t.correction_count or 0 for t in completed_tasks)

            avg_processing_time = None
            durations = [t.review_duration for t in completed_tasks if t.review_duration is not None]
            if durations:
                avg_processing_time = sum(durations) / len(durations)

            pass_rate = pass_count / total_reviewed if total_reviewed > 0 else 0.0
            correction_rate = tasks_with_corrections / total_reviewed if total_reviewed > 0 else 0.0
            avg_corrections_per_task = total_corrections / total_reviewed if total_reviewed > 0 else 0.0

            daily_stats_map: Dict[str, Dict[str, Any]] = {}
            for task in completed_tasks:
                if not task.completed_at:
                    continue
                date_str = task.completed_at.strftime("%Y-%m-%d")
                if date_str not in daily_stats_map:
                    daily_stats_map[date_str] = {
                    "total_reviews": 0,
                    "durations": [],
                    "pass_count": 0,
                    "correction_count": 0,
                }
            daily_stats_map[date_str]["total_reviews"] += 1
            if task.review_duration is not None:
                daily_stats_map[date_str]["durations"].append(task.review_duration)
            if task.is_correct:
                daily_stats_map[date_str]["pass_count"] += 1
            if (task.correction_count or 0) > 0:
                daily_stats_map[date_str]["correction_count"] += 1

            daily_trends = []
            for date_str in sorted(daily_stats_map.keys()):
                day_data = daily_stats_map[date_str]
                day_total = day_data["total_reviews"]
                day_avg = sum(day_data["durations"]) / len(day_data["durations"]) if day_data["durations"] else None
                day_pass_rate = day_data["pass_count"] / day_total if day_total > 0 else 0.0
                day_correction_rate = day_data["correction_count"] / day_total if day_total > 0 else 0.0

                daily_trends.append({
                    "date": date_str,
                    "total_reviews": day_total,
                    "avg_processing_time_seconds": day_avg,
                    "pass_rate": day_pass_rate,
                    "correction_rate": day_correction_rate,
                })

            reviewer_stats = db.query(
                ReviewTask.completed_by,
                func.count(ReviewTask.id).label("count"),
                func.avg(ReviewTask.review_duration).label("avg_time"),
                func.sum(func.cast(ReviewTask.is_correct, Integer)).label("pass_count"),
            ).filter(
                and_(
                    ReviewTask.status == ReviewStatus.COMPLETED,
                    ReviewTask.completed_at >= start_date,
                    ReviewTask.completed_at <= end_date,
                    ReviewTask.completed_by.isnot(None),
                )
            ).group_by(
                ReviewTask.completed_by
            ).order_by(
                desc(func.count(ReviewTask.id))
            ).all()

            reviewer_leaderboard = [
                {
                    "reviewer": reviewer,
                    "total_reviews": count,
                    "avg_processing_time_seconds": float(avg_time) if avg_time else None,
                    "pass_rate": pass_count / count if count > 0 else 0.0,
                }
                for reviewer, count, avg_time, pass_count in reviewer_stats
            ]

            field_errors = db.query(
                ExtractedField.field_name,
                func.count(ExtractedField.id).label("total"),
                func.sum(func.cast(ExtractedField.reviewed, Integer)).label("reviewed"),
            ).join(
                ExtractionResult,
                ExtractedField.extraction_result_id == ExtractionResult.id
            ).filter(
                ExtractionResult.created_at >= start_date,
                ExtractionResult.created_at <= end_date,
            ).group_by(
                ExtractedField.field_name
            ).order_by(
                desc(func.count(ExtractedField.id))
            ).all()

            field_error_distribution = [
                {
                    "field_name": field_name,
                    "total_extracted": total,
                    "reviewed_count": reviewed or 0,
                    "review_rate": (reviewed or 0) / total if total > 0 else 0.0,
                }
                for field_name, total, reviewed in field_errors
            ]

            return {
                "total_reviewed": total_reviewed,
                "avg_processing_time_seconds": avg_processing_time,
                "overall_pass_rate": pass_rate,
                "overall_correction_rate": correction_rate,
                "avg_corrections_per_task": avg_corrections_per_task,
                "daily_trends": daily_trends,
                "reviewer_leaderboard": reviewer_leaderboard,
                "field_error_distribution": field_error_distribution,
            }

        except Exception as e:
            logger.error(f"Failed to get review efficiency statistics: {e}", exc_info=True)
            raise
        finally:
            db.close()

    def get_batch_review_task(self, task_id: int) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).first()
            if not task:
                return None

            doc = db.query(Document).filter(Document.id == task.document_id).first()
            if not doc:
                return None

            extracted_fields = []
            low_confidence_fields = []
            field_highlights = []

            if task.extraction_result_id:
                fields = db.query(ExtractedField).filter(
                    ExtractedField.extraction_result_id == task.extraction_result_id
                ).order_by(ExtractedField.page_number.asc()).all()

                for f in fields:
                    is_low_conf = f.is_low_confidence or f.validation_status in ["error", "warning"]

                    if is_low_conf:
                        color = "#ff6b6b" if f.validation_status == "error" else "#ffd93d"
                        low_confidence_fields.append({
                            "field_id": f.id,
                            "field_name": f.field_name,
                            "page_number": f.page_number,
                            "bounding_box": f.bounding_box,
                            "value": f.value,
                            "confidence": f.confidence or 0.0,
                            "is_low_confidence": True,
                            "validation_status": f.validation_status.value if f.validation_status else "unchecked",
                            "color": color,
                        })

                    field_highlights.append({
                        "field_id": f.id,
                        "field_name": f.field_name,
                        "page_number": f.page_number,
                        "bounding_box": f.bounding_box,
                        "value": f.value,
                        "confidence": f.confidence or 0.0,
                        "is_low_confidence": is_low_conf,
                        "validation_status": f.validation_status.value if f.validation_status else "unchecked",
                        "color": "#4ecdc4" if not is_low_conf else ("#ff6b6b" if f.validation_status == "error" else "#ffd93d"),
                    })

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

            page_image_urls = {}
            if doc.storage_path:
                for page in range(1, (doc.page_count or 1) + 1):
                    page_image_urls[page] = f"/api/v1/documents/{doc.id}/pages/{page}/preview"

            return {
                "task_id": task.id,
                "document_id": task.document_id,
                "document_filename": doc.filename,
                "document_preview_url": f"/api/v1/documents/{doc.id}/preview",
                "page_image_urls": page_image_urls,
                "extracted_fields": extracted_fields,
                "low_confidence_fields": low_confidence_fields,
                "field_highlights": field_highlights,
                "status": task.status.value,
                "started_at": task.started_at,
            }

        except Exception as e:
            logger.error(f"Failed to get batch review task {task_id}: {e}", exc_info=True)
            raise
        finally:
            db.close()

    def batch_confirm_review(self, request: BatchReviewConfirmRequest) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            task = db.query(ReviewTask).filter(ReviewTask.id == request.task_id).first()
            if not task:
                raise ValueError(f"Review task not found: {request.task_id}")

            if not task.extraction_result_id:
                raise ValueError(f"No extraction result found for task {request.task_id}")

            current_version = task.version
            from sqlalchemy import update as sqlalchemy_update
            from sqlalchemy.types import Integer

            fields_query = db.query(ExtractedField).filter(
                ExtractedField.extraction_result_id == task.extraction_result_id
            )

            if request.confirm_all:
                fields_to_confirm = fields_query.all()
            elif request.field_ids_to_confirm:
                fields_to_confirm = fields_query.filter(
                    ExtractedField.id.in_(request.field_ids_to_confirm)
                ).all()
            else:
                fields_to_confirm = []

            confirmed_count = 0
            for field in fields_to_confirm:
                if not field.reviewed:
                    field.reviewed = True
                    field.reviewed_by = request.completed_by
                    field.reviewed_at = datetime.utcnow()
                    field.reviewed_value = field.value
                    confirmed_count += 1

            correction_count = 0
            if request.corrections:
                correction_count = len(request.corrections)
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

            db.flush()

            all_fields_reviewed = fields_query.filter(
                ExtractedField.reviewed == False
            ).count() == 0

            is_correct = correction_count == 0

            completed_at = datetime.utcnow()
            stmt = (
                sqlalchemy_update(ReviewTask)
                .where(
                    and_(
                        ReviewTask.id == request.task_id,
                        ReviewTask.version == current_version,
                    )
                )
                .values(
                    status=ReviewStatus.COMPLETED if all_fields_reviewed else task.status,
                    completed_at=completed_at if all_fields_reviewed else task.completed_at,
                    completed_by=request.completed_by if all_fields_reviewed else task.completed_by,
                    is_correct=is_correct,
                    review_notes=request.review_notes or task.review_notes,
                    correction_count=(task.correction_count or 0) + correction_count,
                    version=current_version + 1,
                )
                .execution_options(synchronize_session=False)
            )

            result = db.execute(stmt)
            db.commit()

            if result.rowcount == 0:
                raise ValueError(
                    f"Task {request.task_id} has been modified by another user. "
                    "Please refresh and try again."
                )

            if all_fields_reviewed and task.started_at:
                task.review_duration = (completed_at - task.started_at).total_seconds()

            if all_fields_reviewed:
                doc = db.query(Document).filter(Document.id == task.document_id).first()
                if doc:
                    doc.status = DocumentStatus.COMPLETED

            db.commit()
            db.refresh(task)

            logger.info(
                f"Batch review for task {task.id} by {request.completed_by}. "
                f"Confirmed: {confirmed_count}, Corrections: {correction_count}, "
                f"All completed: {all_fields_reviewed}"
            )

            return {
                "task_id": task.id,
                "status": task.status.value,
                "completed_by": task.completed_by,
                "completed_at": task.completed_at,
                "review_duration_seconds": task.review_duration,
                "confirmed_count": confirmed_count,
                "correction_count": correction_count,
                "all_fields_completed": all_fields_reviewed,
                "is_correct": is_correct,
            }

        except Exception as e:
            logger.error(f"Failed to batch confirm review: {e}", exc_info=True)
            db.rollback()
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
            task.status = ReviewStatus.ESCALATED

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
