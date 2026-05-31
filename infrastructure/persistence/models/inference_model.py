from sqlalchemy import Column, String, DateTime, JSON, Integer, Boolean, Text
from datetime import datetime

from infrastructure.persistence.database import Base


class AIModelModel(Base):
    __tablename__ = "ai_models"

    model_id = Column(String, primary_key=True, index=True)
    model_name = Column(String, nullable=False)
    model_version = Column(String, nullable=False)
    model_type = Column(String, nullable=False)
    framework = Column(String, nullable=False)

    model_path = Column(String, nullable=False)
    input_schema = Column(JSON, default=dict)
    output_schema = Column(JSON, default=dict)

    description = Column(Text)
    labels = Column(JSON, default=list)

    size_bytes = Column(Integer, default=0)
    checksum = Column(String)

    gpu_required = Column(Boolean, default=False)
    min_memory_mb = Column(Integer, default=256)
    inference_timeout_ms = Column(Integer, default=30000)

    model_metadata = Column("metadata", JSON, default=dict)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class InferenceTaskModel(Base):
    __tablename__ = "inference_tasks"

    task_id = Column(String, primary_key=True, index=True)
    model_id = Column(String, index=True)
    input_data = Column(JSON, default=dict)

    status = Column(String, default="pending")
    priority = Column(Integer, default=0)

    device_id = Column(String, index=True)
    source = Column(String)

    scheduled_at = Column(DateTime)
    started_at = Column(DateTime)
    completed_at = Column(DateTime)
    timeout_at = Column(DateTime)

    error_message = Column(Text)
    retry_count = Column(Integer, default=0)
    max_retries = Column(Integer, default=3)

    callback_url = Column(String)
    model_metadata = Column("metadata", JSON, default=dict)

    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class InferenceResultModel(Base):
    __tablename__ = "inference_results"

    result_id = Column(String, primary_key=True, index=True)
    task_id = Column(String, index=True)
    model_id = Column(String, index=True)

    predictions = Column(JSON, default=list)
    confidence_scores = Column(JSON, default=list)
    raw_output = Column(JSON)

    inference_time_ms = Column(Integer, default=0)
    memory_usage_mb = Column(Integer, default=0)

    success = Column(Boolean, default=True)
    error_message = Column(Text)

    model_metadata = Column("metadata", JSON, default=dict)
    created_at = Column(DateTime, default=datetime.utcnow)
