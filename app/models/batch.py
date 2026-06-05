from datetime import datetime
from sqlalchemy import Column, Integer, String, Text, DateTime, Float, Boolean, JSON, ForeignKey, Enum
from sqlalchemy.orm import relationship
import enum

from app.models.base import BaseModel, TimestampMixin


class BatchStatus(str, enum.Enum):
    CREATED = "created"
    UPLOADING = "uploading"
    UPLOADED = "uploaded"
    QUEUED = "queued"
    PROCESSING = "processing"
    PARTIALLY_COMPLETED = "partially_completed"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class BatchJob(BaseModel, TimestampMixin):
    __tablename__ = "batch_jobs"

    job_name = Column(String(256), nullable=False, index=True)
    description = Column(Text)

    status = Column(Enum(BatchStatus), default=BatchStatus.CREATED, index=True)
    priority = Column(Integer, default=5, index=True)

    total_documents = Column(Integer, default=0)
    processed_documents = Column(Integer, default=0)
    failed_documents = Column(Integer, default=0)
    completed_documents = Column(Integer, default=0)
    needs_review_documents = Column(Integer, default=0)

    celery_group_id = Column(String(256), index=True)

    submitted_by = Column(String(256))
    client_id = Column(String(256), index=True)

    submitted_at = Column(DateTime)
    processing_started_at = Column(DateTime)
    processing_completed_at = Column(DateTime)
    estimated_completion_at = Column(DateTime)

    zip_file_path = Column(String(1024))
    zip_file_size = Column(Integer)
    extract_dir = Column(String(1024))

    metadata = Column(JSON)
    processing_options = Column(JSON)
    extraction_schema = Column(JSON)

    progress_percentage = Column(Float, default=0.0)
    error_message = Column(Text)

    documents = relationship(
        "Document",
        back_populates="batch",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )
    batch_documents = relationship(
        "BatchDocument",
        back_populates="batch_job",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )


class BatchDocument(BaseModel, TimestampMixin):
    __tablename__ = "batch_documents"

    batch_id = Column(Integer, ForeignKey("batch_jobs.id"), nullable=False, index=True)
    document_id = Column(Integer, ForeignKey("documents.id"), nullable=False, index=True)
    original_path_in_zip = Column(String(1024))

    extraction_result_id = Column(Integer, ForeignKey("extraction_results.id"))
    processing_status = Column(String(64), default="pending", index=True)
    processing_order = Column(Integer)

    processing_started_at = Column(DateTime)
    processing_completed_at = Column(DateTime)
    error_message = Column(Text)

    batch_job = relationship("BatchJob", back_populates="batch_documents")
