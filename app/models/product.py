from __future__ import annotations
from datetime import datetime
from typing import Optional, Union, List, Dict
from sqlalchemy import Integer, String, Text, Boolean, DateTime, JSON, ForeignKey, Enum, Index, Float
from sqlalchemy.orm import Mapped, mapped_column, relationship
import enum

from app.core.database import Base


class ProductStatus(str, enum.Enum):
    DRAFT = "DRAFT"
    ACTIVE = "ACTIVE"
    INACTIVE = "INACTIVE"
    OBSOLETE = "OBSOLETE"


class Product(Base):
    __tablename__ = "products"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False, index=True)
    category: Mapped[Optional[str]] = mapped_column(String(100), nullable=True, index=True)
    brand: Mapped[Optional[str]] = mapped_column(String(100), nullable=True, index=True)
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    barcode: Mapped[Optional[str]] = mapped_column(String(50), nullable=True, unique=True, index=True)
    main_image: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    images: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    weight: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    volume: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    status: Mapped[ProductStatus] = mapped_column(Enum(ProductStatus), nullable=False, default=ProductStatus.DRAFT, index=True)
    is_virtual: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow)

    category_id: Mapped[Optional[int]] = mapped_column(Integer, ForeignKey("categories.id"), nullable=True, index=True)

    category_obj: Mapped[Optional["Category"]] = relationship("Category", back_populates="products")
    skus: Mapped[List["SKU"]] = relationship("SKU", back_populates="product", cascade="all, delete-orphan")

    __table_args__ = (
        Index("ix_product_category_brand", "category", "brand"),
        Index("ix_product_status_active", "status"),
    )

    def __repr__(self) -> str:
        return f"<Product(id={self.id}, name='{self.name}', status='{self.status}')>"
