from datetime import datetime
from sqlalchemy import Column, Integer, String, Text, DateTime, Float, Boolean, JSON, Enum
from sqlalchemy.orm import relationship
import enum

from app.models.base import BaseModel, TimestampMixin


class ModelType(str, enum.Enum):
    EXTRACTION = "extraction"
    LAYOUT_ANALYSIS = "layout_analysis"
    TABLE_DETECTION = "table_detection"
    OCR = "ocr"
    MULTIMODAL = "multimodal"


class ModelStatus(str, enum.Enum):
    DRAFT = "draft"
    TESTING = "testing"
    STAGING = "staging"
    PRODUCTION = "production"
    ARCHIVED = "archived"


class ModelVersion(BaseModel, TimestampMixin):
    __tablename__ = "model_versions"

    model_name = Column(String(256), nullable=False, index=True)
    model_type = Column(Enum(ModelType), nullable=False, index=True)
    version = Column(String(64), nullable=False, index=True)
    description = Column(Text)

    status = Column(Enum(ModelStatus), default=ModelStatus.DRAFT, index=True)
    is_default = Column(Boolean, default=False, index=True)

    minio_bucket = Column(String(256))
    minio_path = Column(String(1024))
    local_path = Column(String(1024))

    architecture = Column(String(256))
    framework = Column(String(128))
    framework_version = Column(String(64))

    training_dataset = Column(String(1024))
    training_start_date = Column(DateTime)
    training_end_date = Column(DateTime)
    training_duration_hours = Column(Float)

    metrics = Column(JSON)
    validation_metrics = Column(JSON)
    test_metrics = Column(JSON)

    requirements = Column(JSON)
    hardware_requirements = Column(JSON)

    deployed_at = Column(DateTime)
    deployed_by = Column(String(256))
    deployment_config = Column(JSON)

    extraction_results = relationship(
        "ExtractionResult",
        back_populates="model_version_obj",
        lazy="dynamic",
    )
    ab_test_results = relationship(
        "ABTestResult",
        back_populates="model_version",
        lazy="dynamic",
    )

    __mapper_args__ = {
        "confirm_deleted_rows": False,
    }


class ABTestExperiment(BaseModel, TimestampMixin):
    __tablename__ = "ab_test_experiments"

    name = Column(String(256), nullable=False, index=True)
    description = Column(Text)

    model_type = Column(Enum(ModelType), nullable=False, index=True)
    status = Column(String(64), default="draft", index=True)

    control_model_version = Column(String(64))
    treatment_model_versions = Column(JSON)

    traffic_split = Column(JSON)
    target_metrics = Column(JSON)

    start_date = Column(DateTime)
    end_date = Column(DateTime)
    sample_size = Column(Integer)
    confidence_level = Column(Float, default=0.95)

    created_by = Column(String(256))
    approved_by = Column(String(256))
    approved_at = Column(DateTime)

    results_summary = Column(JSON)
    winner_model = Column(String(64))

    is_active = Column(Boolean, default=False, index=True)
    stopped_at = Column(DateTime)
    stopped_reason = Column(Text)


class ABTestResult(BaseModel, TimestampMixin):
    __tablename__ = "ab_test_results"

    experiment_id = Column(Integer, nullable=False, index=True)
    model_version_id = Column(Integer, ForeignKey("model_versions.id"), nullable=False, index=True)
    document_id = Column(Integer, nullable=False, index=True)
    extraction_result_id = Column(Integer, nullable=False)

    group = Column(String(64), index=True)
    metrics = Column(JSON)
    review_rate = Column(Float)
    average_confidence = Column(Float)
    processing_time = Column(Float)
    field_accuracy = Column(JSON)

    human_evaluated = Column(Boolean, default=False)
    human_score = Column(Float)
    evaluated_by = Column(String(256))
    evaluated_at = Column(DateTime)

    model_version = relationship("ModelVersion", back_populates="ab_test_results")
