from __future__ import annotations
from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List

from sqlalchemy import DateTime, Enum, Float, ForeignKey, Integer, String, Text, Index, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class PurchaseOrderStatus(PyEnum):
    DRAFT = "DRAFT"
    SUBMITTED = "SUBMITTED"
    APPROVING = "APPROVING"
    PARTIAL_APPROVED = "PARTIAL_APPROVED"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    PROCESSING = "PROCESSING"
    PARTIAL_RECEIVED = "PARTIAL_RECEIVED"
    RECEIVED = "RECEIVED"
    CANCELLED = "CANCELLED"
    CLOSED = "CLOSED"


class PurchaseOrder(Base):
    __tablename__ = "purchase_orders"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, index=True)
    order_no: Mapped[str] = mapped_column(String(50), nullable=False, unique=True, index=True)
    supplier_id: Mapped[int] = mapped_column(Integer, ForeignKey("suppliers.id"), nullable=False, index=True)
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    total_amount: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    status: Mapped[PurchaseOrderStatus] = mapped_column(
        Enum(PurchaseOrderStatus), nullable=False, default=PurchaseOrderStatus.DRAFT, index=True
    )
    order_date: Mapped[datetime] = mapped_column(DateTime, nullable=False, index=True)
    expected_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    actual_date: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    shipping_method: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    shipping_cost: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    tax_rate: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    tax_amount: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    discount_rate: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    discount_amount: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    grand_total: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    remark: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    created_by: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False
    )
    approved_by: Mapped[Optional[int]] = mapped_column(Integer, ForeignKey("users.id"), nullable=True, index=True)
    approved_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)

    supplier: Mapped["Supplier"] = relationship("Supplier")
    warehouse: Mapped["Warehouse"] = relationship("Warehouse")
    creator: Mapped["User"] = relationship("User", foreign_keys=[created_by])
    approver: Mapped[Optional["User"]] = relationship("User", foreign_keys=[approved_by])
    items: Mapped[List["PurchaseOrderItem"]] = relationship(
        "PurchaseOrderItem", back_populates="purchase_order", cascade="all, delete-orphan"
    )
    approval_records: Mapped[List["ApprovalRecord"]] = relationship(
        "ApprovalRecord",
        primaryjoin="and_(ApprovalRecord.resource_type=='PURCHASE_ORDER', "
                    "foreign(ApprovalRecord.resource_id)==PurchaseOrder.id)",
        overlaps="approval_records",
    )

    __table_args__ = (
        UniqueConstraint("order_no", name="uq_purchase_order_no"),
        Index("ix_purchase_order_supplier_status", "supplier_id", "status"),
        Index("ix_purchase_order_warehouse_status", "warehouse_id", "status"),
        Index("ix_purchase_order_date_status", "order_date", "status"),
        Index("ix_purchase_order_created_by", "created_by"),
    )

    def __repr__(self) -> str:
        return (
            f"<PurchaseOrder(id={self.id}, order_no='{self.order_no}', "
            f"supplier_id={self.supplier_id}, status='{self.status}')>"
        )


class PurchaseOrderItem(Base):
    __tablename__ = "purchase_order_items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, index=True)
    purchase_order_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("purchase_orders.id", ondelete="CASCADE"), nullable=False, index=True
    )
    sku_id: Mapped[int] = mapped_column(Integer, ForeignKey("skus.id"), nullable=False, index=True)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    unit_price: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    received_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    rejected_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    tax_rate: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    tax_amount: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    total_amount: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    remark: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False, index=True)

    purchase_order: Mapped["PurchaseOrder"] = relationship("PurchaseOrder", back_populates="items")
    sku: Mapped["SKU"] = relationship("SKU")

    __table_args__ = (
        Index("ix_purchase_order_item_po_sku", "purchase_order_id", "sku_id", unique=True),
        Index("ix_purchase_order_item_sku", "sku_id"),
    )

    def __repr__(self) -> str:
        return (
            f"<PurchaseOrderItem(id={self.id}, purchase_order_id={self.purchase_order_id}, "
            f"sku_id={self.sku_id}, quantity={self.quantity})>"
        )
