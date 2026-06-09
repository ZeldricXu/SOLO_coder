from __future__ import annotations
from datetime import datetime
from typing import Optional, List

from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Integer, String, Table, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


role_permission = Table(
    "role_permissions",
    Base.metadata,
    Column("role_id", Integer, ForeignKey("roles.id", ondelete="CASCADE"), primary_key=True),
    Column("permission_id", Integer, ForeignKey("permissions.id", ondelete="CASCADE"), primary_key=True),
    Index("ix_role_permission_role_id", "role_id"),
    Index("ix_role_permission_permission_id", "permission_id"),
)


class Role(Base):
    __tablename__ = "roles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, index=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    code: Mapped[str] = mapped_column(String(100), nullable=False, unique=True, index=True)
    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False
    )

    permissions: Mapped[List["Permission"]] = relationship(
        "Permission",
        secondary=role_permission,
        backref="roles",
        lazy="selectin",
    )

    __table_args__ = (
        Index("ix_role_active", "is_active"),
    )

    def __repr__(self) -> str:
        return f"<Role(id={self.id}, name='{self.name}', code='{self.code}', is_active={self.is_active})>"
