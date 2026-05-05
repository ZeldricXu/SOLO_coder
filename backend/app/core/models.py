from sqlalchemy import Column, Integer, String, Float, DateTime, Text, Boolean, JSON, Enum as SQLEnum
from sqlalchemy.sql import func
from app.core.database import Base
from datetime import datetime
import enum


class ModelStatus(str, enum.Enum):
    CANDIDATE = "candidate"
    VALIDATING = "validating"
    VALIDATED = "validated"
    VALIDATION_FAILED = "validation_failed"
    ACTIVE = "active"
    INACTIVE = "inactive"
    ROLLBACKED = "rollbacked"


class ValidationStatus(str, enum.Enum):
    PENDING = "pending"
    RUNNING = "running"
    PASSED = "passed"
    FAILED = "failed"


class ClassificationResult(Base):
    __tablename__ = "classification_results"

    id = Column(Integer, primary_key=True, index=True)
    result_id = Column(String(50), unique=True, index=True, nullable=False)
    request_id = Column(String(50), index=True, nullable=True)
    text = Column(Text, nullable=False)
    categories = Column(JSON, nullable=False)
    sentiment = Column(JSON, nullable=False)
    keywords = Column(JSON, nullable=False)
    model_version = Column(String(50), nullable=False)
    confidence_threshold = Column(Float, nullable=False)
    classified_at = Column(DateTime(timezone=True), server_default=func.now())
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())


class ModelVersion(Base):
    __tablename__ = "model_versions"

    id = Column(Integer, primary_key=True, index=True)
    model_id = Column(String(100), unique=True, index=True, nullable=False)
    model_type = Column(String(50), nullable=False)
    version = Column(String(50), nullable=False)
    labels = Column(JSON, nullable=False)
    training_samples = Column(Integer, default=0)
    accuracy = Column(Float, default=0.0)
    precision = Column(Float, default=0.0)
    recall = Column(Float, default=0.0)
    f1_score = Column(Float, default=0.0)
    model_path = Column(String(500), nullable=False)
    vectorizer_path = Column(String(500), nullable=False)
    is_active = Column(Boolean, default=False)
    status = Column(SQLEnum(ModelStatus), default=ModelStatus.CANDIDATE, nullable=False)
    validation_status = Column(SQLEnum(ValidationStatus), default=ValidationStatus.PENDING, nullable=True)
    validation_score = Column(Float, default=0.0)
    validation_threshold = Column(Float, default=0.7)
    previous_model_id = Column(String(100), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())
    validated_at = Column(DateTime(timezone=True), nullable=True)
    activated_at = Column(DateTime(timezone=True), nullable=True)
    description = Column(Text, nullable=True)


class ModelValidationRecord(Base):
    __tablename__ = "model_validation_records"

    id = Column(Integer, primary_key=True, index=True)
    validation_id = Column(String(100), unique=True, index=True, nullable=False)
    model_id = Column(String(100), index=True, nullable=False)
    status = Column(SQLEnum(ValidationStatus), default=ValidationStatus.PENDING, nullable=False)
    validation_samples = Column(Integer, default=0)
    accuracy = Column(Float, default=0.0)
    precision = Column(Float, default=0.0)
    recall = Column(Float, default=0.0)
    f1_score = Column(Float, default=0.0)
    threshold_passed = Column(Boolean, default=False)
    details = Column(JSON, nullable=True)
    error_message = Column(Text, nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())


class TrainingJob(Base):
    __tablename__ = "training_jobs"

    id = Column(Integer, primary_key=True, index=True)
    job_id = Column(String(100), unique=True, index=True, nullable=False)
    model_type = Column(String(50), nullable=False)
    status = Column(String(20), default="pending")
    training_samples = Column(Integer, default=0)
    test_size = Column(Float, default=0.2)
    random_state = Column(Integer, default=42)
    result_model_id = Column(String(100), nullable=True)
    error_message = Column(Text, nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())
