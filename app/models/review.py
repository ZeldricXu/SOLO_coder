from datetime import datetime
from sqlalchemy import Column, Integer, String, Text, DateTime, Boolean, JSON, ForeignKey, Enum, Float
from sqlalchemy.orm import relationship
import enum

from app.models.base import BaseModel, TimestampMixin


class ReviewStatus(str, enum.Enum):
    PENDING = "pending"
    ASSIGNED = "assigned"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    ESCALATED = "escalated"
    CANCELLED = "cancelled"


class ReviewPriority(str, enum.Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class ReviewTask(BaseModel, TimestampMixin):
    __tablename__ = "review_tasks"

    document_id = Column(Integer, ForeignKey("documents.id"), nullable=False, index=True)
    extraction_result_id = Column(Integer, ForeignKey("extraction_results.id"), nullable=True, index=True)

    status = Column(Enum(ReviewStatus), default=ReviewStatus.PENDING, index=True)
    priority = Column(Enum(ReviewPriority), default=ReviewPriority.MEDIUM, index=True)

    assigned_to = Column(String(256), index=True)
    assigned_at = Column(DateTime)
    started_at = Column(DateTime)
    completed_at = Column(DateTime)
    review_duration = Column(Float)

    fields_to_review = Column(JSON)
    review_notes = Column(Text)
    review_metadata = Column(JSON)

    completed_by = Column(String(256))
    is_correct = Column(Boolean)
    correction_count = Column(Integer, default=0)
    has_quality_issues = Column(Boolean, default=False)
    quality_issue_description = Column(Text)

    escalated = Column(Boolean, default=False)
    escalated_to = Column(String(256))
    escalated_reason = Column(Text)
    escalated_at = Column(DateTime)

    queued_at = Column(DateTime, default=datetime.utcnow, index=True)
    deadline_at = Column(DateTime)

    version = Column(Integer, default=1, nullable=False)

    document = relationship("Document", back_populates="review_tasks")
    extraction_result = relationship("ExtractionResult", back_populates="review_tasks")
    comments = relationship(
        "ReviewComment",
        back_populates="review_task",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )


class ReviewComment(BaseModel, TimestampMixin):
    __tablename__ = "review_comments"

    review_task_id = Column(Integer, ForeignKey("review_tasks.id"), nullable=False, index=True)
    comment_text = Column(Text, nullable=False)
    comment_type = Column(String(64))

    field_name = Column(String(256))
    old_value = Column(Text)
    new_value = Column(Text)

    commenter = Column(String(256), nullable=False)
    is_resolved = Column(Boolean, default=False)
    resolved_by = Column(String(256))
    resolved_at = Column(DateTime)

    bounding_box = Column(JSON)
    page_number = Column(Integer)

    review_task = relationship("ReviewTask", back_populates="comments")
