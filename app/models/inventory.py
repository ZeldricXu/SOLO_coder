from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, Numeric, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class Inventory(Base):
    __tablename__ = "inventories"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    sku_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    zone_id: Mapped[int] = mapped_column(Integer, ForeignKey("zones.id"), nullable=False, index=True)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    reserved_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    allocated_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    available_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    in_transit_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    unit_cost: Mapped[float] = mapped_column(Numeric(12, 4), nullable=False, default=0.0)
    total_value: Mapped[float] = mapped_column(Numeric(15, 2), nullable=False, default=0.0)
    last_counted_at: Mapped[datetime] = mapped_column(DateTime, nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )

    warehouse: Mapped["Warehouse"] = relationship("Warehouse", back_populates="inventories")
    zone: Mapped["Zone"] = relationship("Zone", back_populates="inventories")
    inventory_transactions: Mapped[list["InventoryTransaction"]] = relationship(
        "InventoryTransaction",
        primaryjoin="and_(foreign(InventoryTransaction.sku_id) == Inventory.sku_id, "
                    "foreign(InventoryTransaction.warehouse_id) == Inventory.warehouse_id, "
                    "foreign(InventoryTransaction.zone_id) == Inventory.zone_id)",
        viewonly=True,
    )

    __table_args__ = (
        UniqueConstraint("sku_id", "warehouse_id", "zone_id", name="uq_sku_warehouse_zone"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<Inventory(id={self.id}, sku_id={self.sku_id}, warehouse_id={self.warehouse_id}, "
            f"zone_id={self.zone_id}, qty={self.quantity}, available={self.available_quantity})>"
        )
