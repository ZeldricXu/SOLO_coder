from typing import List, Optional, Dict, Any
from datetime import datetime
from pydantic import BaseModel, Field, ConfigDict

from app.models.review import ReviewStatus, ReviewPriority

ReviewStatusEnum = ReviewStatus
ReviewPriorityEnum = ReviewPriority


class ReviewTaskBase(BaseModel):
    document_id: int
    extraction_result_id: Optional[int] = None
    priority: ReviewPriorityEnum = ReviewPriorityEnum.MEDIUM
    fields_to_review: Optional[List[Dict[str, Any]]] = None
    deadline_at: Optional[datetime] = None


class ReviewTaskCreate(ReviewTaskBase):
    pass


class ReviewTaskUpdate(BaseModel):
    status: Optional[ReviewStatusEnum] = None
    priority: Optional[ReviewPriorityEnum] = None
    assigned_to: Optional[str] = None
    review_notes: Optional[str] = None
    is_correct: Optional[bool] = None
    has_quality_issues: Optional[bool] = None
    quality_issue_description: Optional[str] = None
    escalated: Optional[bool] = None
    escalated_to: Optional[str] = None
    escalated_reason: Optional[str] = None


class FieldCorrection(BaseModel):
    field_id: int
    field_name: str
    old_value: Optional[str] = None
    new_value: str
    comment: Optional[str] = None


class ReviewTaskCompleteRequest(BaseModel):
    task_id: int
    completed_by: str
    is_correct: bool = True
    corrections: Optional[List[FieldCorrection]] = None
    review_notes: Optional[str] = None
    has_quality_issues: bool = False
    quality_issue_description: Optional[str] = None


class ReviewTaskResponse(ReviewTaskBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: ReviewStatusEnum
    assigned_to: Optional[str] = None
    assigned_at: Optional[datetime] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    review_duration: Optional[float] = None
    review_notes: Optional[str] = None
    completed_by: Optional[str] = None
    is_correct: Optional[bool] = None
    correction_count: int = 0
    has_quality_issues: bool = False
    quality_issue_description: Optional[str] = None
    escalated: bool = False
    escalated_to: Optional[str] = None
    escalated_reason: Optional[str] = None
    escalated_at: Optional[datetime] = None
    queued_at: datetime
    created_at: datetime
    updated_at: datetime


class ReviewCommentBase(BaseModel):
    review_task_id: int
    comment_text: str
    comment_type: Optional[str] = None
    field_name: Optional[str] = None
    old_value: Optional[str] = None
    new_value: Optional[str] = None
    page_number: Optional[int] = None


class ReviewCommentCreate(ReviewCommentBase):
    commenter: str


class ReviewCommentResponse(ReviewCommentBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    commenter: str
    is_resolved: bool = False
    resolved_by: Optional[str] = None
    resolved_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime


class ReviewQueueItem(BaseModel):
    task_id: int
    document_id: int
    document_filename: str
    document_type: str
    status: ReviewStatusEnum
    priority: ReviewPriorityEnum
    fields_count: int
    fields_to_review: Optional[List[str]] = None
    assigned_to: Optional[str] = None
    queued_at: datetime
    waiting_time_seconds: float


class ReviewStatistics(BaseModel):
    total_tasks: int = 0
    pending_tasks: int = 0
    in_progress_tasks: int = 0
    completed_tasks: int = 0
    escalated_tasks: int = 0
    average_review_time: Optional[float] = None
    average_corrections_per_task: float = 0.0
    high_priority_pending: int = 0
    tasks_past_deadline: int = 0


class TrainingDataExportRequest(BaseModel):
    start_date: Optional[datetime] = None
    end_date: Optional[datetime] = None
    field_names: Optional[List[str]] = None
    only_reviewed: bool = True
    include_low_confidence: bool = False
    export_format: str = "json"
