from __future__ import annotations
from datetime import datetime
import enum
from typing import Optional, Union, List, Dict

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, JSON, String, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class AlertRuleType(str, enum.Enum):
    LOW_STOCK = "LOW_STOCK"
    HIGH_STOCK = "HIGH_STOCK"
    OUT_OF_STOCK = "OUT_OF_STOCK"
    EXPIRING = "EXPIRING"
    SLOW_MOVING = "SLOW_MOVING"


class ThresholdType(str, enum.Enum):
    QUANTITY = "QUANTITY"
    PERCENTAGE = "PERCENTAGE"
    DAYS = "DAYS"


class AlertLevel(str, enum.Enum):
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"


class AlertStatus(str, enum.Enum):
    OPEN = "OPEN"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    RESOLVED = "RESOLVED"
    CLOSED = "CLOSED"


class AlertRule(Base):
    __tablename__ = "alert_rules"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False, index=True)
    rule_type: Mapped[AlertRuleType] = mapped_column(
        Enum(AlertRuleType), nullable=False, index=True
    )
    threshold_type: Mapped[ThresholdType] = mapped_column(
        Enum(ThresholdType), nullable=False, index=True
    )
    threshold_value: Mapped[float] = mapped_column(nullable=False)
    warning_value: Mapped[float] = mapped_column(nullable=False)
    critical_value: Mapped[float] = mapped_column(nullable=False)
    sku_ids: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    category_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("categories.id"), nullable=True, index=True
    )
    warehouse_ids: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    is_active: Mapped[bool] = mapped_column(default=True, index=True)
    notify_channels: Mapped[Optional[Union[Dict, List]]] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, index=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow
    )

    category: Mapped["Category"] = relationship("Category", back_populates="alert_rules")
    alerts: Mapped[List["InventoryAlert"]] = relationship(
        "InventoryAlert", back_populates="rule", cascade="all, delete-orphan"
    )

    __table_args__ = (
        Index("ix_alert_rule_type_active", "rule_type", "is_active"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<AlertRule(id={self.id}, name='{self.name}', rule_type={self.rule_type}, "
            f"threshold_type={self.threshold_type}, is_active={self.is_active})>"
        )


class InventoryAlert(Base):
    __tablename__ = "inventory_alerts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    rule_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("alert_rules.id"), nullable=False, index=True
    )
    sku_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("skus.id"), nullable=False, index=True
    )
    warehouse_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("warehouses.id"), nullable=False, index=True
    )
    alert_level: Mapped[AlertLevel] = mapped_column(
        Enum(AlertLevel), nullable=False, index=True
    )
    alert_type: Mapped[AlertRuleType] = mapped_column(
        Enum(AlertRuleType), nullable=False, index=True
    )
    current_value: Mapped[float] = mapped_column(nullable=False)
    threshold_value: Mapped[float] = mapped_column(nullable=False)
    message: Mapped[str] = mapped_column(String(500), nullable=False)
    status: Mapped[AlertStatus] = mapped_column(
        Enum(AlertStatus), nullable=False, default=AlertStatus.OPEN, index=True
    )
    acknowledged_by: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("users.id"), nullable=True, index=True
    )
    acknowledged_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    resolved_by: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("users.id"), nullable=True, index=True
    )
    resolved_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=datetime.utcnow, index=True
    )

    rule: Mapped["AlertRule"] = relationship("AlertRule", back_populates="alerts")
    sku: Mapped["SKU"] = relationship("SKU")
    warehouse: Mapped["Warehouse"] = relationship("Warehouse")
    acknowledged_by_user: Mapped["User"] = relationship(
        "User", foreign_keys=[acknowledged_by]
    )
    resolved_by_user: Mapped["User"] = relationship(
        "User", foreign_keys=[resolved_by]
    )

    __table_args__ = (
        Index("ix_alert_status_level", "status", "alert_level"),
        Index("ix_alert_sku_warehouse", "sku_id", "warehouse_id"),
        Index("ix_alert_created_at", "created_at"),
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<InventoryAlert(id={self.id}, rule_id={self.rule_id}, sku_id={self.sku_id}, "
            f"warehouse_id={self.warehouse_id}, level={self.alert_level}, status={self.status})>"
        )
