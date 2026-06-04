from sqlalchemy import Column, Integer, String, DateTime, Text, ForeignKey, Date
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from app.database import Base


class DutySchedule(Base):
    __tablename__ = "duty_schedules"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    duty_date = Column(Date, nullable=False, index=True)
    shift_type = Column(String(20), default="day")
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        {'sqlite_autoincrement': True},
    )


class HandoverReport(Base):
    __tablename__ = "handover_reports"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    schedule_id = Column(Integer, ForeignKey("duty_schedules.id"))
    from_user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    to_user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    content = Column(Text, nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
