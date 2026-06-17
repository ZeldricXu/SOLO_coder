import uuid
from datetime import datetime

from sqlalchemy import Boolean, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSON, UUID
from sqlalchemy.orm import Mapped, mapped_column

from etl_engine.models.base import Base, TimestampMixin


class Pipeline(Base, TimestampMixin):
    __tablename__ = "pipelines"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    dag_definition: Mapped[dict] = mapped_column(JSON, nullable=False)
    schedule: Mapped[str | None] = mapped_column(String, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    max_retries: Mapped[int] = mapped_column(Integer, default=3)
    retry_delay_seconds: Mapped[int] = mapped_column(Integer, default=60)
    timeout_seconds: Mapped[int] = mapped_column(Integer, default=3600)
    sla_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
