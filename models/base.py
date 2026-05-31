from datetime import datetime
from typing import Optional

from pydantic import BaseModel as PydanticBaseModel, ConfigDict
from sqlalchemy import Column, DateTime, String
from sqlalchemy.orm import declarative_mixin, Mapped, mapped_column

from core.utils import utc_now, generate_id


class BaseModel(PydanticBaseModel):
    model_config = ConfigDict(
        from_attributes=True,
        populate_by_name=True,
        use_enum_values=True,
        arbitrary_types_allowed=True,
    )


@declarative_mixin
class TimestampMixin:
    __allow_unmapped__ = True
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now, nullable=False
    )


class IdMixin:
    __allow_unmapped__ = True
    id: Mapped[str] = mapped_column(String(64), primary_key=True, default=lambda: generate_id("ent"))
