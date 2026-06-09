from datetime import datetime
from enum import Enum as PyEnum

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class SyncType(PyEnum):
    FULL = "FULL"
    INCREMENTAL = "INCREMENTAL"


class SyncStatus(PyEnum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class InventorySync(Base):
    __tablename__ = "inventory_syncs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    source_warehouse_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("warehouses.id"), nullable=False, index=True
    )
    target_warehouse_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("warehouses.id"), nullable=False, index=True
    )
    sync_type: Mapped[SyncType] = mapped_column(Enum(SyncType), nullable=False, index=True)
    sync_status: Mapped[SyncStatus] = mapped_column(
        Enum(SyncStatus), nullable=False, default=SyncStatus.PENDING, index=True
    )
    record_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    success_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    failed_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    started_at: Mapped[datetime] = mapped_column(DateTime, nullable=True, index=True)
    completed_at: Mapped[datetime] = mapped_column(DateTime, nullable=True, index=True)
    error_message: Mapped[str] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    source_warehouse: Mapped["Warehouse"] = relationship(
        "Warehouse", back_populates="source_syncs", foreign_keys=[source_warehouse_id]
    )
    target_warehouse: Mapped["Warehouse"] = relationship(
        "Warehouse", back_populates="target_syncs", foreign_keys=[target_warehouse_id]
    )
    conflicts: Mapped[list["SyncConflict"]] = relationship(
        "SyncConflict", back_populates="sync", cascade="all, delete-orphan"
    )

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<InventorySync(id={self.id}, source_warehouse_id={self.source_warehouse_id}, "
            f"target_warehouse_id={self.target_warehouse_id}, type={self.sync_type}, "
            f"status={self.sync_status})>"
        )
