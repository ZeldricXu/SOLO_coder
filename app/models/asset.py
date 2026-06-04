from sqlalchemy import Column, Integer, String, DateTime, Text, ForeignKey
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from app.database import Base


class Asset(Base):
    __tablename__ = "assets"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    name = Column(String(100), nullable=False)
    category = Column(String(50), nullable=False, index=True)
    ip = Column(String(45))
    port = Column(Integer)
    version = Column(String(50))
    owner = Column(String(50))
    status = Column(String(20), default="normal")
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    change_logs = relationship("AssetChangeLog", back_populates="asset", cascade="all, delete-orphan")


class AssetChangeLog(Base):
    __tablename__ = "asset_change_logs"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    asset_id = Column(Integer, ForeignKey("assets.id"), nullable=False)
    field_name = Column(String(50), nullable=False)
    old_value = Column(Text)
    new_value = Column(Text)
    operator_id = Column(Integer, ForeignKey("users.id"))
    changed_at = Column(DateTime(timezone=True), server_default=func.now())

    asset = relationship("Asset", back_populates="change_logs")
