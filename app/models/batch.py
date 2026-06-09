from __future__ import annotations
from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, Numeric, String, Index, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class InspectionStatus(PyEnum):
    PENDING = "PENDING"
    PASSED = "PASSED"
    FAILED = "FAILED"
    PARTIAL = "PARTIAL"


class Batch(Base):
    __tablename__ = "batches"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    batch_no: Mapped[str] = mapped_column(String(100), nullable=False, unique=True, index=True)
    sku_id: Mapped[int] = mapped_column(Integer, ForeignKey("skus.id"), nullable=False, index=True)
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    supplier_id: Mapped[int] = mapped_column(Integer, ForeignKey("suppliers.id"), nullable=True, index=True)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    remaining_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    unit_cost: Mapped[float] = mapped_column(Numeric(12, 4), nullable=False, default=0.0)
    production_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    expiration_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    received_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    manufacture_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    lot_number: Mapped[Optional[str]] = mapped_column(String(100), nullable=True, index=True)
    inspection_status: Mapped[InspectionStatus] = mapped_column(
        Enum(InspectionStatus), nullable=False, default=InspectionStatus.PENDING, index=True
    )
    quality_grade: Mapped[Optional[str]] = mapped_column(String(50), nullable=True, index=True)
    is_frozen: Mapped[bool] = mapped_column(default=False, index=True)
    frozen_reason: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    frozen_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    frozen_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    remark: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )

    sku: Mapped["SKU"] = relationship("SKU")
    warehouse: Mapped["Warehouse"] = relationship("Warehouse")
    supplier: Mapped["Supplier"] = relationship("Supplier")
    serial_numbers: Mapped[List["SerialNumber"]] = relationship(
        "SerialNumber", back_populates="batch"
    )
    document_items: Mapped[List["DocumentItem"]] = relationship(
        "DocumentItem", back_populates="batch"
    )

    __table_args__ = (
        UniqueConstraint("batch_no", name="uq_batches_batch_no"),
        Index("ix_batch_sku_warehouse", "sku_id", "warehouse_id"),
        Index("ix_batch_expiration_status", "expiration_date", "inspection_status"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<Batch(id={self.id}, batch_no='{self.batch_no}', sku_id={self.sku_id}, "
            f"warehouse_id={self.warehouse_id}, qty={self.quantity}, "
            f"remaining={self.remaining_quantity}, status={self.inspection_status})>"
        )
