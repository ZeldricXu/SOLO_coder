from sqlalchemy import Column, Integer, String, DateTime, Text, ForeignKey, Float
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from app.database import Base


class SlowSQL(Base):
    __tablename__ = "slow_sqls"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    fingerprint = Column(String(64), unique=True, nullable=False, index=True)
    table_name = Column(String(100), index=True)
    sql_text = Column(Text, nullable=False)
    avg_duration_ms = Column(Float, default=0)
    exec_count = Column(Integer, default=0)
    first_seen = Column(DateTime(timezone=True), server_default=func.now())
    last_seen = Column(DateTime(timezone=True), server_default=func.now())

    explains = relationship("SQLExplain", back_populates="slow_sql", cascade="all, delete-orphan")


class SQLExplain(Base):
    __tablename__ = "sql_explains"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    slow_sql_id = Column(Integer, ForeignKey("slow_sqls.id"), nullable=False)
    plan_json = Column(Text)
    analysis = Column(Text)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    slow_sql = relationship("SlowSQL", back_populates="explains")
