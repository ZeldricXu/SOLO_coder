from __future__ import annotations
from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, String, Index, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class SerialNumberStatus(PyEnum):
    INSTOCK = "INSTOCK"
    ALLOCATED = "ALLOCATED"
    SHIPPED = "SHIPPED"
    RETURNED = "RETURNED"
    SCRAPPED = "SCRAPPED"


class TraceAction(PyEnum):
    RECEIVE = "RECEIVE"
    PUTAWAY = "PUTAWAY"
    TRANSFER = "TRANSFER"
    ALLOCATE = "ALLOCATE"
    SHIP = "SHIP"
    RETURN = "RETURN"
    SCRAP = "SCRAP"


class SerialNumber(Base):
    __tablename__ = "serial_numbers"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    serial_code: Mapped[str] = mapped_column(String(200), nullable=False, unique=True, index=True)
    sku_id: Mapped[int] = mapped_column(Integer, ForeignKey("skus.id"), nullable=False, index=True)
    batch_id: Mapped[int] = mapped_column(Integer, ForeignKey("batches.id"), nullable=True, index=True)
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    status: Mapped[SerialNumberStatus] = mapped_column(
        Enum(SerialNumberStatus), nullable=False, default=SerialNumberStatus.INSTOCK, index=True
    )
    production_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    expiration_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    received_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    shipped_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    current_location: Mapped[Optional[str]] = mapped_column(String(200), nullable=True, index=True)
    remark: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )

    sku: Mapped["SKU"] = relationship("SKU")
    batch: Mapped["Batch"] = relationship("Batch", back_populates="serial_numbers")
    warehouse: Mapped["Warehouse"] = relationship("Warehouse")
    traces: Mapped[List["SerialNumberTrace"]] = relationship(
        "SerialNumberTrace", back_populates="serial_number", cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("serial_code", name="uq_serial_numbers_serial_code"),
        Index("ix_serial_sku_batch", "sku_id", "batch_id"),
        Index("ix_serial_status_warehouse", "status", "warehouse_id"),
        Index("ix_serial_expiration", "expiration_date"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<SerialNumber(id={self.id}, serial_code='{self.serial_code}', "
            f"sku_id={self.sku_id}, batch_id={self.batch_id}, "
            f"warehouse_id={self.warehouse_id}, status={self.status})>"
        )


class SerialNumberTrace(Base):
    __tablename__ = "serial_number_traces"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    serial_number_id: Mapped[int] = mapped_column(Integer, ForeignKey("serial_numbers.id"), nullable=False, index=True)
    action: Mapped[TraceAction] = mapped_column(
        Enum(TraceAction), nullable=False, index=True
    )
    from_location: Mapped[Optional[str]] = mapped_column(String(200), nullable=True)
    to_location: Mapped[Optional[str]] = mapped_column(String(200), nullable=True)
    reference_type: Mapped[Optional[str]] = mapped_column(String(50), nullable=True, index=True)
    reference_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    operated_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    operated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    serial_number: Mapped["SerialNumber"] = relationship("SerialNumber", back_populates="traces")

    __table_args__ = (
        Index("ix_trace_serial_action", "serial_number_id", "action"),
        Index("ix_trace_reference", "reference_type", "reference_id"),
        Index("ix_trace_operated_at", "operated_at"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<SerialNumberTrace(id={self.id}, serial_number_id={self.serial_number_id}, "
            f"action={self.action}, from='{self.from_location}', to='{self.to_location}', "
            f"operated_at={self.operated_at})>"
        )
