from sqlalchemy import Column, Integer, String, Text, DateTime, Float, Boolean, JSON, ForeignKey, Enum
from sqlalchemy.orm import relationship
import enum

from app.models.base import BaseModel, TimestampMixin


class DocumentType(str, enum.Enum):
    PDF = "pdf"
    WORD = "word"
    IMAGE = "image"
    TXT = "txt"
    UNKNOWN = "unknown"


class DocumentStatus(str, enum.Enum):
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


class DocumentPriority(str, enum.Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class Document(BaseModel, TimestampMixin):
    __tablename__ = "documents"

    batch_id = Column(Integer, ForeignKey("batchjob.id"), nullable=True, index=True)
    filename = Column(String(512), nullable=False, index=True)
    original_filename = Column(String(512), nullable=False)
    document_type = Column(Enum(DocumentType), default=DocumentType.UNKNOWN, index=True)
    mime_type = Column(String(128))
    file_size = Column(Integer)
    status = Column(Enum(DocumentStatus), default=DocumentStatus.UPLOADED, index=True)
    priority = Column(Enum(DocumentPriority), default=DocumentPriority.MEDIUM, index=True)

    storage_path = Column(String(1024), nullable=False)
    minio_bucket = Column(String(256))
    minio_object_name = Column(String(1024))

    page_count = Column(Integer, default=0)
    language = Column(String(32))

    error_message = Column(Text)
    error_stack = Column(Text)

    processing_started_at = Column(DateTime)
    processing_completed_at = Column(DateTime)
    processing_duration = Column(Float)

    preprocessing_metadata = Column(JSON)
    ocr_metadata = Column(JSON)
    layout_metadata = Column(JSON)

    uploaded_by = Column(String(256))
    client_id = Column(String(256), index=True)
    claim_number = Column(String(256), index=True)

    extraction_results = relationship(
        "ExtractionResult",
        back_populates="document",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )
    review_tasks = relationship(
        "ReviewTask",
        back_populates="document",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )
    tables = relationship(
        "TableStructure",
        back_populates="document",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )
    batch = relationship("BatchJob", back_populates="documents")

    def __repr__(self) -> str:
        return f"<Document(id={self.id}, filename='{self.filename}', status='{self.status}')>"
