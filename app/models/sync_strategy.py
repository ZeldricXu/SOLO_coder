from __future__ import annotations

from datetime import datetime
from enum import Enum as PyEnum
from typing import List

from sqlalchemy import DateTime, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class InventorySnapshot(Base):
    __tablename__ = "inventory_snapshots"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    warehouse_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("warehouses.id"), nullable=False, index=True
    )
    sku_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    available_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    snapshot_date: Mapped[datetime] = mapped_column(DateTime, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    warehouse: Mapped["Warehouse"] = relationship("Warehouse", back_populates="inventory_snapshots")

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<InventorySnapshot(id={self.id}, warehouse_id={self.warehouse_id}, "
            f"sku_id={self.sku_id}, quantity={self.quantity}, "
            f"snapshot_date={self.snapshot_date})>"
        )


__all__ = [
    "InventorySnapshot",
]
