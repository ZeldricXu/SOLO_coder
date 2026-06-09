from __future__ import annotations
from datetime import datetime
from typing import Optional, Union, List, Dict
from sqlalchemy import Integer, String, Text, Boolean, DateTime, JSON, Enum, Index
from sqlalchemy.orm import Mapped, mapped_column
import enum

from app.core.database import Base


class AttributeDataType(str, enum.Enum):
    STRING = "STRING"
    NUMBER = "NUMBER"
    BOOLEAN = "BOOLEAN"
    SELECT = "SELECT"
    MULTISELECT = "MULTISELECT"


class Attribute(Base):
    __tablename__ = "attributes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    code: Mapped[str] = mapped_column(String(50), nullable=False, unique=True, index=True)
    data_type: Mapped[AttributeDataType] = mapped_column(Enum(AttributeDataType), nullable=False, index=True)
    options: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    is_required: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_searchable: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_filterable: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)

    __table_args__ = (
        Index("ix_attribute_searchable", "is_searchable"),
        Index("ix_attribute_filterable", "is_filterable"),
    )

    def __repr__(self) -> str:
        return f"<Attribute(id={self.id}, name='{self.name}', code='{self.code}', data_type='{self.data_type}')>"


class AttributeTemplate(Base):
    __tablename__ = "attribute_templates"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    attributes: Mapped[Union[Dict, List]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow)

    def __repr__(self) -> str:
        return f"<AttributeTemplate(id={self.id}, name='{self.name}')>"
