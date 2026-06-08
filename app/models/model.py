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

    __mapper_args__ = {
        "confirm_deleted_rows": False,
    }


class ABTestExperiment(BaseModel, TimestampMixin):
    __tablename__ = "ab_test_experiments"

    experiment_name = Column(String(256), nullable=False, index=True)
    description = Column(Text)

    model_name = Column(String(256), nullable=False, index=True)
    variant_a_model_id = Column(Integer, nullable=False, index=True)
    variant_b_model_id = Column(Integer, nullable=False, index=True)

    traffic_split_a = Column(Float, default=50.0)
    traffic_split_b = Column(Float, default=50.0)
    strategy = Column(String(64), default="random")
    primary_metric = Column(String(128), default="accuracy")

    status = Column(String(64), default="draft", index=True)
    is_active = Column(Boolean, default=False, index=True)

    sample_size_a = Column(Integer, default=0)
    sample_size_b = Column(Integer, default=0)
    target_metrics = Column(JSON)

    start_date = Column(DateTime)
    end_date = Column(DateTime)
    started_at = Column(DateTime)
    ended_at = Column(DateTime)
    confidence_level = Column(Float, default=0.95)

    created_by = Column(String(256))
    approved_by = Column(String(256))
    approved_at = Column(DateTime)

    results_summary = Column(JSON)
    winner = Column(String(64))
    winner_model_id = Column(Integer)

    stopped_at = Column(DateTime)
    stopped_reason = Column(Text)
    notes = Column(Text)


class ABTestResult(BaseModel, TimestampMixin):
    __tablename__ = "ab_test_results"

    experiment_id = Column(Integer, nullable=False, index=True)
    variant = Column(String(64), nullable=False, index=True)
    document_id = Column(Integer, index=True)
    metric_name = Column(String(128), nullable=False, index=True)
    metric_value = Column(Float, nullable=False)

    metrics = Column(JSON)
    review_rate = Column(Float)
    average_confidence = Column(Float)
    processing_time = Column(Float)

    experiment = relationship("ABTestExperiment", foreign_keys=[experiment_id], primaryjoin="ABTestResult.experiment_id == ABTestExperiment.id")
