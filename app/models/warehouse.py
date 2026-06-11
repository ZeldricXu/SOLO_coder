from __future__ import annotations

from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, Any, List

from sqlalchemy import Boolean, DateTime, Enum, Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class WarehouseType(PyEnum):
    MAIN = "MAIN"
    BRANCH = "BRANCH"
    FULFILLMENT = "FULFILLMENT"
    RETURNS = "RETURNS"
    VIRTUAL = "VIRTUAL"


def _default_sync_strategy():
    from app.utils.constants import SyncStrategy
    return SyncStrategy.REALTIME


def _default_scheduled_sync_time():
    from app.utils.constants import DEFAULT_SCHEDULED_SYNC_TIME
    return DEFAULT_SCHEDULED_SYNC_TIME


class Warehouse(Base):
    __tablename__ = "warehouses"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    code: Mapped[str] = mapped_column(String(50), unique=True, nullable=False, index=True)
    address: Mapped[str] = mapped_column(String(255), nullable=True)
    city: Mapped[str] = mapped_column(String(100), nullable=True, index=True)
    province: Mapped[str] = mapped_column(String(100), nullable=True, index=True)
    country: Mapped[str] = mapped_column(String(100), nullable=True, index=True)
    postal_code: Mapped[str] = mapped_column(String(20), nullable=True)
    contact_person: Mapped[str] = mapped_column(String(100), nullable=True)
    contact_phone: Mapped[str] = mapped_column(String(20), nullable=True)
    contact_email: Mapped[str] = mapped_column(String(100), nullable=True)
    warehouse_type: Mapped[WarehouseType] = mapped_column(
        Enum(WarehouseType), nullable=False, default=WarehouseType.MAIN, index=True
    )
    is_active: Mapped[bool] = mapped_column(default=True, index=True)
    capacity: Mapped[int] = mapped_column(Integer, nullable=True)
    utilization_rate: Mapped[float] = mapped_column(Float, nullable=True)
    sync_strategy: Mapped[Optional[Any]] = mapped_column(
        Enum("REALTIME", "SCHEDULED", "MANUAL", "VIRTUAL", name="sync_strategy_enum"),
        nullable=True,
        default=_default_sync_strategy,
        index=True,
    )
    is_virtual: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    last_snapshot_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    scheduled_sync_time: Mapped[str] = mapped_column(
        String(10), nullable=False, default=_default_scheduled_sync_time
    )
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )

    zones: Mapped[List["Zone"]] = relationship("Zone", back_populates="warehouse", cascade="all, delete-orphan")
    inventories: Mapped[List["Inventory"]] = relationship("Inventory", back_populates="warehouse")
    inventory_transactions: Mapped[List["InventoryTransaction"]] = relationship(
        "InventoryTransaction", back_populates="warehouse"
    )
    source_syncs: Mapped[List["InventorySync"]] = relationship(
        "InventorySync", back_populates="source_warehouse", foreign_keys="InventorySync.source_warehouse_id"
    )
    target_syncs: Mapped[List["InventorySync"]] = relationship(
        "InventorySync", back_populates="target_warehouse", foreign_keys="InventorySync.target_warehouse_id"
    )
    stocktake_plans: Mapped[List["StocktakePlan"]] = relationship(
        "StocktakePlan", back_populates="warehouse"
    )
    inventory_snapshots: Mapped[List["InventorySnapshot"]] = relationship(
        "InventorySnapshot", back_populates="warehouse", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return f"<Warehouse(id={self.id}, name='{self.name}', code='{self.code}', type={self.warehouse_type})>"


class Zone(Base):
    __tablename__ = "zones"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    code: Mapped[str] = mapped_column(String(50), nullable=False, index=True)
    area: Mapped[float] = mapped_column(Float, nullable=True)
    storage_type: Mapped[str] = mapped_column(String(50), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    warehouse: Mapped["Warehouse"] = relationship("Warehouse", back_populates="zones")
    inventories: Mapped[List["Inventory"]] = relationship("Inventory", back_populates="zone")
    inventory_transactions: Mapped[List["InventoryTransaction"]] = relationship(
        "InventoryTransaction", back_populates="zone"
    )

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return f"<Zone(id={self.id}, warehouse_id={self.warehouse_id}, name='{self.name}', code='{self.code}')>"
