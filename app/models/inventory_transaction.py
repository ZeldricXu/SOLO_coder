from datetime import datetime
from enum import Enum as PyEnum

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class TransactionType(PyEnum):
    IN = "IN"
    OUT = "OUT"
    TRANSFER = "TRANSFER"
    ADJUSTMENT = "ADJUSTMENT"
    COUNT = "COUNT"


class InventoryTransaction(Base):
    __tablename__ = "inventory_transactions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    sku_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    zone_id: Mapped[int] = mapped_column(Integer, ForeignKey("zones.id"), nullable=False, index=True)
    transaction_type: Mapped[TransactionType] = mapped_column(
        Enum(TransactionType), nullable=False, index=True
    )
    quantity: Mapped[int] = mapped_column(Integer, nullable=False)
    unit_cost: Mapped[float] = mapped_column(Numeric(12, 4), nullable=False, default=0.0)
    reference_type: Mapped[str] = mapped_column(String(50), nullable=True, index=True)
    reference_id: Mapped[int] = mapped_column(Integer, nullable=True, index=True)
    batch_id: Mapped[str] = mapped_column(String(100), nullable=True, index=True)
    serial_number: Mapped[str] = mapped_column(String(100), nullable=True, index=True)
    reason: Mapped[str] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    created_by: Mapped[int] = mapped_column(Integer, nullable=True, index=True)

    warehouse: Mapped["Warehouse"] = relationship("Warehouse", back_populates="inventory_transactions")
    zone: Mapped["Zone"] = relationship("Zone", back_populates="inventory_transactions")
    inventory: Mapped["Inventory"] = relationship(
        "Inventory",
        primaryjoin="and_(InventoryTransaction.sku_id == foreign(Inventory.sku_id), "
                    "InventoryTransaction.warehouse_id == foreign(Inventory.warehouse_id), "
                    "InventoryTransaction.zone_id == foreign(Inventory.zone_id))",
        viewonly=True,
    )

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<InventoryTransaction(id={self.id}, sku_id={self.sku_id}, "
            f"type={self.transaction_type}, qty={self.quantity}, "
            f"warehouse_id={self.warehouse_id})>"
        )
