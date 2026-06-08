from sqlalchemy import Column, Integer, String, Text, Float, Boolean, JSON, ForeignKey, Enum, DateTime
from sqlalchemy.orm import relationship
import enum

from app.models.base import BaseModel, TimestampMixin


class FieldDataType(str, enum.Enum):
    STRING = "string"
    NUMBER = "number"
    DATE = "date"
    BOOLEAN = "boolean"
    LIST = "list"
    OBJECT = "object"


class ExtractionStatus(str, enum.Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    NEEDS_REVIEW = "needs_review"


class FieldValidationStatus(str, enum.Enum):
    VALID = "valid"
    WARNING = "warning"
    ERROR = "error"
    UNCHECKED = "unchecked"


class ExtractionResult(BaseModel, TimestampMixin):
    __tablename__ = "extraction_results"

    document_id = Column(Integer, ForeignKey("documents.id"), nullable=False, index=True)
    model_version_id = Column(Integer, ForeignKey("model_versions.id"), nullable=True, index=True)
    status = Column(Enum(ExtractionStatus), default=ExtractionStatus.PENDING, index=True)

    schema_name = Column(String(256), nullable=False)
    schema_version = Column(String(64))

    overall_confidence = Column(Float, default=0.0)
    processing_time = Column(Float)
    model_name = Column(String(256))
    model_version = Column(String(64))

    raw_extraction = Column(JSON)
    structured_output = Column(JSON)

    is_ab_test = Column(Boolean, default=False)
    ab_test_group = Column(String(64))

    error_message = Column(Text)

    document = relationship("Document", back_populates="extraction_results")
    model_version_obj = relationship("ModelVersion", back_populates="extraction_results")
    extracted_fields = relationship(
        "ExtractedField",
        back_populates="extraction_result",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )
    review_tasks = relationship(
        "ReviewTask",
        back_populates="extraction_result",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )


class ExtractedField(BaseModel, TimestampMixin):
    __tablename__ = "extracted_fields"

    extraction_result_id = Column(Integer, ForeignKey("extraction_results.id"), nullable=False, index=True)
    field_name = Column(String(256), nullable=False, index=True)
    field_type = Column(Enum(FieldDataType), default=FieldDataType.STRING)

    value = Column(Text)
    normalized_value = Column(Text)

    confidence = Column(Float, default=0.0, index=True)
    is_low_confidence = Column(Boolean, default=False, index=True)

    page_number = Column(Integer)
    bounding_box = Column(JSON)
    text_block = Column(Text)

    validation_status = Column(Enum(FieldValidationStatus), default=FieldValidationStatus.UNCHECKED, index=True)
    validation_errors = Column(JSON)
    validation_warnings = Column(JSON)
    suggested_value = Column(Text)

    reviewed = Column(Boolean, default=False, index=True)
    reviewed_value = Column(Text)
    reviewed_by = Column(String(256))
    reviewed_at = Column(DateTime)

    is_used_for_training = Column(Boolean, default=False)

    extraction_result = relationship("ExtractionResult", back_populates="extracted_fields")
