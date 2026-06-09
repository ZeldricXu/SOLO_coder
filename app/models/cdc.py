from datetime import datetime
from enum import Enum as PyEnum

from sqlalchemy import Boolean, DateTime, Enum, ForeignKey, Integer, JSON, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class CDCOperation(PyEnum):
    INSERT = "INSERT"
    UPDATE = "UPDATE"
    DELETE = "DELETE"


class CDCSourceSystem(PyEnum):
    ERP = "ERP"
    WMS = "WMS"
    OMS = "OMS"


class CDCEventType(PyEnum):
    INVENTORY_CHANGED = "INVENTORY_CHANGED"
    ORDER_CREATED = "ORDER_CREATED"


class CDCEventStatus(PyEnum):
    PENDING = "PENDING"
    PROCESSED = "PROCESSED"
    FAILED = "FAILED"


class CDCLog(Base):
    __tablename__ = "cdc_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    table_name: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    operation: Mapped[CDCOperation] = mapped_column(Enum(CDCOperation), nullable=False, index=True)
    record_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    old_data: Mapped[dict] = mapped_column(JSON, nullable=True)
    new_data: Mapped[dict] = mapped_column(JSON, nullable=True)
    source_system: Mapped[CDCSourceSystem] = mapped_column(Enum(CDCSourceSystem), nullable=False, index=True)
    processed: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime, nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    events: Mapped[list["CDCEvent"]] = relationship(
        "CDCEvent", back_populates="cdc_log", cascade="all, delete-orphan"
    )

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<CDCLog(id={self.id}, table={self.table_name}, "
            f"operation={self.operation}, record_id={self.record_id}, "
            f"source={self.source_system}, processed={self.processed})>"
        )


class CDCEvent(Base):
    __tablename__ = "cdc_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    cdc_log_id: Mapped[int] = mapped_column(Integer, ForeignKey("cdc_logs.id"), nullable=False, index=True)
    event_type: Mapped[CDCEventType] = mapped_column(Enum(CDCEventType), nullable=False, index=True)
    status: Mapped[CDCEventStatus] = mapped_column(Enum(CDCEventStatus), nullable=False, index=True, default=CDCEventStatus.PENDING)
    error_message: Mapped[str] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

    cdc_log: Mapped["CDCLog"] = relationship("CDCLog", back_populates="events")

    __table_args__ = (
        {"sqlite_autoincrement": True},
    )

    def __repr__(self) -> str:
        return (
            f"<CDCEvent(id={self.id}, cdc_log_id={self.cdc_log_id}, "
            f"type={self.event_type}, status={self.status})>"
        )
