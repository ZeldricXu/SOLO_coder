from sqlalchemy import Column, Integer, String, DateTime, Text, ForeignKey, Boolean, Float
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from app.database import Base


class AlertRule(Base):
    __tablename__ = "alert_rules"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    name = Column(String(100), nullable=False)
    level = Column(String(10), nullable=False)
    condition_expr = Column(Text, nullable=False)
    window_seconds = Column(Integer, default=300)
    threshold = Column(Float)
    notification_channels = Column(Text, nullable=False)
    enabled = Column(Boolean, default=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    alert_histories = relationship("AlertHistory", back_populates="rule")


class AlertHistory(Base):
    __tablename__ = "alert_history"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    rule_id = Column(Integer, ForeignKey("alert_rules.id"), index=True)
    service_id = Column(Integer, ForeignKey("services.id"), index=True)
    level = Column(String(10), nullable=False)
    message = Column(Text, nullable=False)
    status = Column(String(20), default="firing", index=True)
    ack_user_id = Column(Integer, ForeignKey("users.id"))
    triggered_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)
    ack_at = Column(DateTime(timezone=True))

    rule = relationship("AlertRule", back_populates="alert_histories")
