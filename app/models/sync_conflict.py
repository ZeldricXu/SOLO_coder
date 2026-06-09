from datetime import datetime
from enum import Enum as PyEnum

from sqlalchemy import DateTime, Enum, ForeignKey, Integer
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class ConflictType(PyEnum):
    QUANTITY_MISMATCH = "QUANTITY_MISMATCH"
    COST_MISMATCH = "COST_MISMATCH"
    STATUS_MISMATCH = "STATUS_MISMATCH"
    VERSION_CONFLICT = "VERSION_CONFLICT"


class ResolutionStrategy(PyEnum):
    SOURCE_WINS = "SOURCE_WINS"
    TARGET_WINS = "TARGET_WINS"
    MANUAL = "MANUAL"
    LAST_WRITE_WINS = "LAST_WRITE_WINS"
    MERGE = "MERGE"
    REJECT = "REJECT"


class ConflictStatus(PyEnum):
    PENDING = "PENDING"
    RESOLVED = "RESOLVED"
    IGNORED = "IGNORED"


class SyncConflict(Base):
    __tablename__ = "sync_conflicts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    sync_id: Mapped[int] = mapped_column(Integer, ForeignKey("inventory_syncs.id"), nullable=False, index=True)
    sku_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    source_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    target_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    conflict_type: Mapped[ConflictType] = mapped_column(Enum(ConflictType), nullable=False, index=True)
    resolution_strategy: Mapped[ResolutionStrategy] = mapped_column(
        Enum(ResolutionStrategy), nullable=True, index=True
    )
    resolved_by: Mapped[int] = mapped_column(Integer, nullable=True, index=True)
    resolved_at: Mapped[datetime] = mapped_column(DateTime, nullable=True, index=True)
    status: Mapped[ConflictStatus] = mapped_column(
        Enum(ConflictStatus), nullable=False, default=ConflictStatus.PENDING, index=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    sync: Mapped["InventorySync"] = relationship("InventorySync", back_populates="conflicts")

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<SyncConflict(id={self.id}, sync_id={self.sync_id}, sku_id={self.sku_id}, "
            f"type={self.conflict_type}, status={self.status})>"
        )
