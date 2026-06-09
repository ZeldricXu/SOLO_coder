from __future__ import annotations
from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, Union, List, Dict

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, Numeric, String, JSON, Index, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class DocumentType(PyEnum):
    PURCHASE_IN = "PURCHASE_IN"
    SALES_OUT = "SALES_OUT"
    TRANSFER = "TRANSFER"
    STOCKTAKE = "STOCKTAKE"
    DAMAGE = "DAMAGE"


class DocumentStatus(PyEnum):
    DRAFT = "DRAFT"
    CONFIRMED = "CONFIRMED"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"


class InventoryDocument(Base):
    __tablename__ = "inventory_documents"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    document_no: Mapped[str] = mapped_column(String(100), nullable=False, unique=True, index=True)
    document_type: Mapped[DocumentType] = mapped_column(
        Enum(DocumentType), nullable=False, index=True
    )
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    target_warehouse_id: Mapped[Optional[int]] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=True, index=True)
    status: Mapped[DocumentStatus] = mapped_column(
        Enum(DocumentStatus), nullable=False, default=DocumentStatus.DRAFT, index=True
    )
    total_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    total_amount: Mapped[float] = mapped_column(Numeric(15, 2), nullable=False, default=0.0)
    remark: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    reference_type: Mapped[Optional[str]] = mapped_column(String(50), nullable=True, index=True)
    reference_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    created_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )
    confirmed_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    completed_by: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)

    warehouse: Mapped["Warehouse"] = relationship("Warehouse", foreign_keys=[warehouse_id])
    target_warehouse: Mapped["Warehouse"] = relationship("Warehouse", foreign_keys=[target_warehouse_id])
    items: Mapped[List["DocumentItem"]] = relationship(
        "DocumentItem", back_populates="document", cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("document_no", name="uq_inventory_documents_document_no"),
        Index("ix_document_type_status", "document_type", "status"),
        Index("ix_document_warehouse_type", "warehouse_id", "document_type"),
        Index("ix_document_created_at", "created_at"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<InventoryDocument(id={self.id}, document_no='{self.document_no}', "
            f"type={self.document_type}, warehouse_id={self.warehouse_id}, "
            f"status={self.status}, total_qty={self.total_quantity})>"
        )


class DocumentItem(Base):
    __tablename__ = "document_items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    document_id: Mapped[int] = mapped_column(Integer, ForeignKey("inventory_documents.id"), nullable=False, index=True)
    sku_id: Mapped[int] = mapped_column(Integer, ForeignKey("skus.id"), nullable=False, index=True)
    batch_id: Mapped[Optional[int]] = mapped_column(Integer, ForeignKey("batches.id"), nullable=True, index=True)
    serial_numbers: Mapped[Optional[Union[List[str], Dict]]] = mapped_column(JSON, nullable=True)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    actual_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    unit_cost: Mapped[float] = mapped_column(Numeric(12, 4), nullable=False, default=0.0)
    total_cost: Mapped[float] = mapped_column(Numeric(15, 2), nullable=False, default=0.0)
    remark: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    document: Mapped["InventoryDocument"] = relationship("InventoryDocument", back_populates="items")
    sku: Mapped["SKU"] = relationship("SKU")
    batch: Mapped["Batch"] = relationship("Batch", back_populates="document_items")

    __table_args__ = (
        Index("ix_item_document_sku", "document_id", "sku_id"),
        Index("ix_item_batch", "batch_id"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<DocumentItem(id={self.id}, document_id={self.document_id}, "
            f"sku_id={self.sku_id}, batch_id={self.batch_id}, "
            f"qty={self.quantity}, actual_qty={self.actual_quantity})>"
        )
