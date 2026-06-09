from datetime import datetime
from enum import Enum as PyEnum

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, JSON, Numeric, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class StocktakePlanType(PyEnum):
    FULL = "FULL"
    PARTIAL = "PARTIAL"
    CYCLE = "CYCLE"


class StocktakePlanStatus(PyEnum):
    DRAFT = "DRAFT"
    PLANNED = "PLANNED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"


class StocktakeTaskStatus(PyEnum):
    PENDING = "PENDING"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"


class StocktakeResultStatus(PyEnum):
    UNCHECKED = "UNCHECKED"
    CHECKED = "CHECKED"
    ADJUSTED = "ADJUSTED"


class AdjustmentType(PyEnum):
    GAIN = "GAIN"
    LOSS = "LOSS"


class AdjustmentStatus(PyEnum):
    DRAFT = "DRAFT"
    APPROVED = "APPROVED"
    COMPLETED = "COMPLETED"


class StocktakePlan(Base):
    __tablename__ = "stocktake_plans"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    plan_no: Mapped[str] = mapped_column(String(50), unique=True, nullable=False, index=True)
    warehouse_id: Mapped[int] = mapped_column(Integer, ForeignKey("warehouses.id"), nullable=False, index=True)
    plan_type: Mapped[StocktakePlanType] = mapped_column(Enum(StocktakePlanType), nullable=False, index=True)
    status: Mapped[StocktakePlanStatus] = mapped_column(Enum(StocktakePlanStatus), nullable=False, index=True, default=StocktakePlanStatus.DRAFT)
    scheduled_date: Mapped[datetime] = mapped_column(DateTime, nullable=False, index=True)
    actual_start_date: Mapped[datetime] = mapped_column(DateTime, nullable=True, index=True)
    actual_end_date: Mapped[datetime] = mapped_column(DateTime, nullable=True, index=True)
    description: Mapped[str] = mapped_column(Text, nullable=True)
    created_by: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )

    warehouse: Mapped["Warehouse"] = relationship("Warehouse", back_populates="stocktake_plans")
    tasks: Mapped[list["StocktakeTask"]] = relationship(
        "StocktakeTask", back_populates="plan", cascade="all, delete-orphan"
    )

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<StocktakePlan(id={self.id}, plan_no={self.plan_no}, "
            f"warehouse_id={self.warehouse_id}, type={self.plan_type}, "
            f"status={self.status})>"
        )


class StocktakeTask(Base):
    __tablename__ = "stocktake_tasks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    plan_id: Mapped[int] = mapped_column(Integer, ForeignKey("stocktake_plans.id"), nullable=False, index=True)
    sku_ids: Mapped[list] = mapped_column(JSON, nullable=False)
    zone_ids: Mapped[list] = mapped_column(JSON, nullable=False)
    assignee_id: Mapped[int] = mapped_column(Integer, nullable=True, index=True)
    status: Mapped[StocktakeTaskStatus] = mapped_column(Enum(StocktakeTaskStatus), nullable=False, index=True, default=StocktakeTaskStatus.PENDING)
    assigned_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    started_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    completed_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    remark: Mapped[str] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    plan: Mapped["StocktakePlan"] = relationship("StocktakePlan", back_populates="tasks")
    results: Mapped[list["StocktakeResult"]] = relationship(
        "StocktakeResult", back_populates="task", cascade="all, delete-orphan"
    )

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<StocktakeTask(id={self.id}, plan_id={self.plan_id}, "
            f"assignee_id={self.assignee_id}, status={self.status})>"
        )


class StocktakeResult(Base):
    __tablename__ = "stocktake_results"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    task_id: Mapped[int] = mapped_column(Integer, ForeignKey("stocktake_tasks.id"), nullable=False, index=True)
    sku_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    batch_id: Mapped[str] = mapped_column(String(100), nullable=True, index=True)
    expected_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    counted_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    difference_quantity: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    variance_reason: Mapped[str] = mapped_column(Text, nullable=True)
    status: Mapped[StocktakeResultStatus] = mapped_column(Enum(StocktakeResultStatus), nullable=False, index=True, default=StocktakeResultStatus.UNCHECKED)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    task: Mapped["StocktakeTask"] = relationship("StocktakeTask", back_populates="results")
    adjustment: Mapped["StocktakeAdjustment"] = relationship(
        "StocktakeAdjustment", back_populates="result", uselist=False, cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("task_id", "sku_id", "batch_id", name="uq_task_sku_batch"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<StocktakeResult(id={self.id}, task_id={self.task_id}, "
            f"sku_id={self.sku_id}, expected={self.expected_quantity}, "
            f"counted={self.counted_quantity}, diff={self.difference_quantity})>"
        )


class StocktakeAdjustment(Base):
    __tablename__ = "stocktake_adjustments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    result_id: Mapped[int] = mapped_column(Integer, ForeignKey("stocktake_results.id"), nullable=False, unique=True, index=True)
    adjustment_type: Mapped[AdjustmentType] = mapped_column(Enum(AdjustmentType), nullable=False, index=True)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False)
    unit_cost: Mapped[float] = mapped_column(Numeric(12, 4), nullable=False, default=0.0)
    total_cost: Mapped[float] = mapped_column(Numeric(15, 2), nullable=False, default=0.0)
    status: Mapped[AdjustmentStatus] = mapped_column(Enum(AdjustmentStatus), nullable=False, index=True, default=AdjustmentStatus.DRAFT)
    approved_by: Mapped[int] = mapped_column(Integer, nullable=True, index=True)
    approved_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    created_by: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    result: Mapped["StocktakeResult"] = relationship("StocktakeResult", back_populates="adjustment")

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<StocktakeAdjustment(id={self.id}, result_id={self.result_id}, "
            f"type={self.adjustment_type}, qty={self.quantity}, "
            f"total_cost={self.total_cost}, status={self.status})>"
        )
