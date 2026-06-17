import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSON, UUID
from sqlalchemy.orm import Mapped, mapped_column

from etl_engine.models.base import Base, TimestampMixin


class PipelineExecution(Base, TimestampMixin):
    __tablename__ = "pipeline_executions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    pipeline_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("pipelines.id"), nullable=False)
    status: Mapped[str] = mapped_column(String, default="pending")
    trigger_type: Mapped[str] = mapped_column(String, nullable=False)
    started_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    total_rows_read: Mapped[int | None] = mapped_column(Integer, nullable=True)
    total_rows_written: Mapped[int | None] = mapped_column(Integer, nullable=True)
    quality_passed: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    execution_timeline: Mapped[dict | None] = mapped_column(JSON, nullable=True)
