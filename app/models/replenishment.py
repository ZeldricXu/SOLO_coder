from __future__ import annotations
from datetime import datetime, date
import enum
from typing import Optional, Union, List, Dict

from sqlalchemy import Date, DateTime, Enum, Float, ForeignKey, Integer, JSON, Numeric, String, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class ReplenishmentStatus(str, enum.Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    CONVERTED = "CONVERTED"


class ForecastPeriod(str, enum.Enum):
    DAILY = "DAILY"
    WEEKLY = "WEEKLY"
    MONTHLY = "MONTHLY"


class ForecastMethod(str, enum.Enum):
    MOVING_AVERAGE = "MOVING_AVERAGE"
    EXPONENTIAL_SMOOTHING = "EXPONENTIAL_SMOOTHING"
    ARIMA = "ARIMA"
    LINEAR_REGRESSION = "LINEAR_REGRESSION"


class ReplenishmentSuggestion(Base):
    __tablename__ = "replenishment_suggestions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    sku_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("skus.id"), nullable=False, index=True
    )
    supplier_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("suppliers.id"), nullable=False, index=True
    )
    warehouse_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("warehouses.id"), nullable=False, index=True
    )
    suggested_quantity: Mapped[int] = mapped_column(Integer, nullable=False)
    suggested_unit_price: Mapped[float] = mapped_column(Numeric(12, 4), nullable=False)
    estimated_total_cost: Mapped[float] = mapped_column(Numeric(15, 2), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    demand_forecast: Mapped[int] = mapped_column(Integer, nullable=False)
    current_stock: Mapped[int] = mapped_column(Integer, nullable=False)
    safety_stock: Mapped[int] = mapped_column(Integer, nullable=False)
    lead_time_days: Mapped[int] = mapped_column(Integer, nullable=False)
    expected_delivery_date: Mapped[date] = mapped_column(Date, nullable=False)
    status: Mapped[ReplenishmentStatus] = mapped_column(
        Enum(ReplenishmentStatus), nullable=False, default=ReplenishmentStatus.PENDING, index=True
    )
    purchase_order_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    created_by: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id"), nullable=False, index=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, index=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow
    )
    reviewed_by: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("users.id"), nullable=True, index=True
    )
    reviewed_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)

    sku: Mapped["SKU"] = relationship("SKU")
    supplier: Mapped["Supplier"] = relationship("Supplier")
    warehouse: Mapped["Warehouse"] = relationship("Warehouse")
    created_by_user: Mapped["User"] = relationship(
        "User", foreign_keys=[created_by]
    )
    reviewed_by_user: Mapped["User"] = relationship(
        "User", foreign_keys=[reviewed_by]
    )

    __table_args__ = (
        Index("ix_replenishment_status", "status"),
        Index("ix_replenishment_sku_warehouse", "sku_id", "warehouse_id"),
        Index("ix_replenishment_created_at", "created_at"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<ReplenishmentSuggestion(id={self.id}, sku_id={self.sku_id}, "
            f"supplier_id={self.supplier_id}, warehouse_id={self.warehouse_id}, "
            f"qty={self.suggested_quantity}, status={self.status})>"
        )


class SalesForecast(Base):
    __tablename__ = "sales_forecasts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    sku_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("skus.id"), nullable=False, index=True
    )
    forecast_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    forecast_period: Mapped[ForecastPeriod] = mapped_column(
        Enum(ForecastPeriod), nullable=False, index=True
    )
    forecast_method: Mapped[ForecastMethod] = mapped_column(
        Enum(ForecastMethod), nullable=False, index=True
    )
    historical_data: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    forecast_data: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    confidence_level: Mapped[float] = mapped_column(Float, nullable=False)
    mape: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    rmse: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    mae: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, index=True
    )

    sku: Mapped["SKU"] = relationship("SKU")

    __table_args__ = (
        Index("ix_forecast_sku_period_date", "sku_id", "forecast_period", "forecast_date"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<SalesForecast(id={self.id}, sku_id={self.sku_id}, "
            f"forecast_date='{self.forecast_date}', period={self.forecast_period}, "
            f"method={self.forecast_method}, confidence={self.confidence_level})>"
        )
