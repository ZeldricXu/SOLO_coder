from sqlalchemy import Column, Integer, String, DateTime, Text, ForeignKey
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from app.database import Base


class Preference(Base):
    __tablename__ = "preferences"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), unique=True, nullable=False)
    layout_config = Column(Text)
    updated_at = Column(DateTime(timezone=True), server_default=func.now())

    pinned_components = relationship("PinnedComponent", back_populates="preference", cascade="all, delete-orphan")


class PinnedComponent(Base):
    __tablename__ = "pinned_components"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    preference_id = Column(Integer, ForeignKey("preferences.id"), nullable=False)
    component_type = Column(String(50), nullable=False)
    component_key = Column(String(100), nullable=False)
    position = Column(Integer, default=0)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    preference = relationship("Preference", back_populates="pinned_components")


class LogTemplate(Base):
    __tablename__ = "log_templates"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    name = Column(String(100), nullable=False)
    query_config = Column(Text, nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
