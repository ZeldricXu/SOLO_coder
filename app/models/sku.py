from __future__ import annotations
from datetime import datetime
from typing import Optional, Union, List, Dict
from sqlalchemy import Integer, String, DateTime, JSON, ForeignKey, Enum, Index, UniqueConstraint, Float
from sqlalchemy.orm import Mapped, mapped_column, relationship
import enum

from app.core.database import Base


class SkuStatus(str, enum.Enum):
    DRAFT = "DRAFT"
    ACTIVE = "ACTIVE"
    INACTIVE = "INACTIVE"
    DISCONTINUED = "DISCONTINUED"


class SkuLifecycleStatus(str, enum.Enum):
    CONCEPT = "CONCEPT"
    SAMPLE = "SAMPLE"
    PRODUCTION = "PRODUCTION"
    END_OF_LIFE = "END_OF_LIFE"


class SKU(Base):
    __tablename__ = "skus"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    sku_code: Mapped[str] = mapped_column(String(100), nullable=False, unique=True, index=True)
    product_id: Mapped[int] = mapped_column(Integer, ForeignKey("products.id"), nullable=False, index=True)
    attributes: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    cost_price: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    selling_price: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    weight: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    volume: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    status: Mapped[SkuStatus] = mapped_column(Enum(SkuStatus), nullable=False, default=SkuStatus.DRAFT, index=True)
    lifecycle_status: Mapped[SkuLifecycleStatus] = mapped_column(Enum(SkuLifecycleStatus), nullable=False, default=SkuLifecycleStatus.CONCEPT, index=True)
    minimum_stock: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    maximum_stock: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    reorder_point: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    safety_stock: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    lead_time_days: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow)

    product: Mapped["Product"] = relationship("Product", back_populates="skus")

    __table_args__ = (
        UniqueConstraint("product_id", "sku_code", name="uq_sku_product_code"),
        Index("ix_sku_status_lifecycle", "status", "lifecycle_status"),
        Index("ix_sku_reorder_point", "reorder_point"),
    )

    def __repr__(self) -> str:
        return f"<SKU(id={self.id}, sku_code='{self.sku_code}', product_id={self.product_id}, status='{self.status}')>"
