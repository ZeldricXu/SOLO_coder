import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, Integer, String
from sqlalchemy.dialects.postgresql import JSON, UUID
from sqlalchemy.orm import Mapped, mapped_column

from etl_engine.models.base import Base, TimestampMixin


class DataSource(Base, TimestampMixin):
    __tablename__ = "data_sources"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String, unique=True, nullable=False)
    type: Mapped[str] = mapped_column(String, nullable=False)
    connection_config: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    pool_size: Mapped[int] = mapped_column(Integer, default=5)
    last_connected_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
