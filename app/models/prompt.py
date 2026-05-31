from sqlalchemy import Column, String, Integer, Float, ForeignKey, Index, Boolean, DateTime
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import relationship
import uuid

from app.models.base import Base, TimestampMixin


class Prompt(Base, TimestampMixin):
    __tablename__ = "prompts"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False, index=True)
    version = Column(Integer, nullable=False)
    content = Column(String, nullable=False)
    template_variables = Column(JSONB, default=lambda: {})
    model_config = Column(JSONB, default=lambda: {})
    created_by = Column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=False)
    is_active = Column(Boolean, default=True, nullable=False)
    description = Column(String(1000))
    tags = Column(JSONB, default=lambda: [])
    meta_data = Column(JSONB, default=lambda: {})

    __table_args__ = (
        Index("ix_prompt_name_version", "name", "version", unique=True),
    )


class ABTest(Base, TimestampMixin):
    __tablename__ = "ab_tests"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False, index=True)
    description = Column(String(1000))
    control_prompt_id = Column(UUID(as_uuid=True), ForeignKey("prompts.id"), nullable=False)
    treatment_prompt_id = Column(UUID(as_uuid=True), ForeignKey("prompts.id"), nullable=False)
    traffic_split = Column(Float, default=0.5, nullable=False)
    status = Column(String(50), default="draft", nullable=False)
    start_time = Column(DateTime(timezone=True))
    end_time = Column(DateTime(timezone=True))
    created_by = Column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=False)
    primary_metric = Column(String(255))
    metrics = Column(JSONB, default=lambda: [])
    results = Column(JSONB, default=lambda: {})
    meta_data = Column(JSONB, default=lambda: {})


class PromptExperiment(Base, TimestampMixin):
    __tablename__ = "prompt_experiments"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False, index=True)
    prompt_id = Column(UUID(as_uuid=True), ForeignKey("prompts.id"), nullable=False)
    test_cases = Column(JSONB, default=lambda: [])
    evaluations = Column(JSONB, default=lambda: [])
    status = Column(String(50), default="created", nullable=False)
    created_by = Column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=False)
    results_summary = Column(JSONB, default=lambda: {})
    meta_data = Column(JSONB, default=lambda: {})
