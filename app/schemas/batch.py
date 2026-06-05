from typing import List, Optional, Dict, Any
from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict

from app.schemas.document import ProcessingOptions


class BatchStatusEnum(str, Enum):
    CREATED = "created"
    UPLOADING = "uploading"
    UPLOADED = "uploaded"
    QUEUED = "queued"
    PROCESSING = "processing"
    PARTIALLY_COMPLETED = "partially_completed"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class BatchJobBase(BaseModel):
    job_name: str
    description: Optional[str] = None
    priority: int = 5
    client_id: Optional[str] = None
    submitted_by: Optional[str] = None
    processing_options: Optional[ProcessingOptions] = None
    extraction_schema: Optional[Dict[str, Any]] = None
    metadata: Optional[Dict[str, Any]] = None


class BatchJobCreate(BatchJobBase):
    zip_file_path: Optional[str] = None
    zip_file_size: Optional[int] = None


class BatchJobUpdate(BaseModel):
    status: Optional[BatchStatusEnum] = None
    priority: Optional[int] = None
    description: Optional[str] = None
    error_message: Optional[str] = None


class BatchDocumentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    batch_id: int
    document_id: int
    original_path_in_zip: Optional[str] = None
    processing_status: str = "pending"
    processing_started_at: Optional[datetime] = None
    processing_completed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    created_at: datetime


class BatchJobResponse(BatchJobBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: BatchStatusEnum
    total_documents: int = 0
    processed_documents: int = 0
    failed_documents: int = 0
    completed_documents: int = 0
    needs_review_documents: int = 0
    progress_percentage: float = 0.0
    celery_group_id: Optional[str] = None
    zip_file_path: Optional[str] = None
    zip_file_size: Optional[int] = None
    submitted_at: Optional[datetime] = None
    processing_started_at: Optional[datetime] = None
    processing_completed_at: Optional[datetime] = None
    estimated_completion_at: Optional[datetime] = None
    error_message: Optional[str] = None
    created_at: datetime
    updated_at: datetime


class BatchJobDetailResponse(BatchJobResponse):
    batch_documents: List[BatchDocumentResponse] = Field(default_factory=list)


class BatchUploadResponse(BaseModel):
    batch_id: int
    job_name: str
    status: str
    total_documents: int
    message: str


class BatchProcessRequest(BaseModel):
    batch_id: int
    options: Optional[ProcessingOptions] = None


class BatchProgressResponse(BaseModel):
    batch_id: int
    status: str
    progress_percentage: float
    total_documents: int
    processed_documents: int
    failed_documents: int
    completed_documents: int
    needs_review_documents: int
    processing_started_at: Optional[datetime] = None
    estimated_completion_at: Optional[datetime] = None
    current_processing_document: Optional[str] = None
    recent_errors: Optional[List[str]] = None


class BatchCancelResponse(BaseModel):
    batch_id: int
    status: str
    message: str
    cancelled_documents: int
