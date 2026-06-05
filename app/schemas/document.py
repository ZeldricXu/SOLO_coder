from typing import List, Optional, Dict, Any
from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict

from app.schemas.common import BoundingBox, TextBlock, ImageRegion, TableData


class DocumentTypeEnum(str, Enum):
    PDF = "pdf"
    WORD = "word"
    IMAGE = "image"
    TXT = "txt"
    UNKNOWN = "unknown"


class DocumentStatusEnum(str, Enum):
    UPLOADED = "uploaded"
    PREPROCESSING = "preprocessing"
    PREPROCESSED = "preprocessed"
    LAYOUT_ANALYZING = "layout_analyzing"
    LAYOUT_ANALYZED = "layout_analyzed"
    EXTRACTING = "extracting"
    EXTRACTED = "extracted"
    VALIDATING = "validating"
    VALIDATED = "validated"
    NEEDS_REVIEW = "needs_review"
    COMPLETED = "completed"
    FAILED = "failed"


class DocumentPriorityEnum(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class PageInfo(BaseModel):
    page_number: int
    width: float
    height: float
    text_blocks: List[TextBlock] = Field(default_factory=list)
    image_regions: List[ImageRegion] = Field(default_factory=list)
    tables: List[TableData] = Field(default_factory=list)
    ocr_confidence: Optional[float] = None


class StandardizedDocument(BaseModel):
    document_id: Optional[int] = None
    original_filename: str
    document_type: DocumentTypeEnum
    page_count: int
    pages: List[PageInfo] = Field(default_factory=list)
    language: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
    preprocessing_time: Optional[float] = None


class DocumentBase(BaseModel):
    original_filename: str
    document_type: Optional[DocumentTypeEnum] = DocumentTypeEnum.UNKNOWN
    priority: Optional[DocumentPriorityEnum] = DocumentPriorityEnum.MEDIUM
    client_id: Optional[str] = None
    claim_number: Optional[str] = None
    uploaded_by: Optional[str] = None


class DocumentCreate(DocumentBase):
    filename: str
    storage_path: str
    mime_type: Optional[str] = None
    file_size: Optional[int] = None
    minio_bucket: Optional[str] = None
    minio_object_name: Optional[str] = None


class DocumentUpdate(BaseModel):
    status: Optional[DocumentStatusEnum] = None
    priority: Optional[DocumentPriorityEnum] = None
    client_id: Optional[str] = None
    claim_number: Optional[str] = None
    error_message: Optional[str] = None


class DocumentResponse(DocumentBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    filename: str
    status: DocumentStatusEnum
    file_size: Optional[int] = None
    page_count: int = 0
    language: Optional[str] = None
    processing_started_at: Optional[datetime] = None
    processing_completed_at: Optional[datetime] = None
    processing_duration: Optional[float] = None
    created_at: datetime
    updated_at: datetime
    batch_id: Optional[int] = None


class DocumentDetailResponse(DocumentResponse):
    preprocessing_metadata: Optional[Dict[str, Any]] = None
    ocr_metadata: Optional[Dict[str, Any]] = None
    layout_metadata: Optional[Dict[str, Any]] = None
    error_stack: Optional[str] = None


class DocumentUploadResponse(BaseModel):
    document_id: int
    filename: str
    status: str
    message: str


class ProcessingOptions(BaseModel):
    skip_ocr: bool = False
    skip_layout_analysis: bool = False
    skip_table_extraction: bool = False
    run_async: bool = True
    priority: Optional[DocumentPriorityEnum] = None
    extraction_schema_name: Optional[str] = None
    model_version: Optional[str] = None


class DocumentProcessRequest(BaseModel):
    document_ids: List[int]
    options: Optional[ProcessingOptions] = ProcessingOptions()
