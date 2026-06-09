from __future__ import annotations
from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List

from sqlalchemy import Boolean, DateTime, Enum, Float, ForeignKey, Integer, String, Text, Index, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class ResourceType(PyEnum):
    PURCHASE_ORDER = "PURCHASE_ORDER"
    STOCKTAKE = "STOCKTAKE"
    ADJUSTMENT = "ADJUSTMENT"


class NodeType(PyEnum):
    START = "START"
    APPROVAL = "APPROVAL"
    END = "END"


class ApprovalType(PyEnum):
    AND = "AND"
    OR = "OR"
    PERCENTAGE = "PERCENTAGE"


class ApprovalStatus(PyEnum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"


class ApprovalWorkflow(Base):
    __tablename__ = "approval_workflows"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, index=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    code: Mapped[str] = mapped_column(String(100), nullable=False, unique=True, index=True)
    resource_type: Mapped[ResourceType] = mapped_column(
        Enum(ResourceType), nullable=False, index=True
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False
    )

    nodes: Mapped[List["ApprovalNode"]] = relationship(
        "ApprovalNode", back_populates="workflow", cascade="all, delete-orphan", order_by="ApprovalNode.sort_order"
    )
    records: Mapped[List["ApprovalRecord"]] = relationship(
        "ApprovalRecord", back_populates="workflow"
    )

    __table_args__ = (
        UniqueConstraint("code", name="uq_approval_workflow_code"),
        Index("ix_approval_workflow_resource_type_active", "resource_type", "is_active"),
    )

    def __repr__(self) -> str:
        return (
            f"<ApprovalWorkflow(id={self.id}, name='{self.name}', "
            f"code='{self.code}', resource_type='{self.resource_type}')>"
        )


class ApprovalNode(Base):
    __tablename__ = "approval_nodes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, index=True)
    workflow_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("approval_workflows.id", ondelete="CASCADE"), nullable=False, index=True
    )
    node_name: Mapped[str] = mapped_column(String(200), nullable=False)
    node_type: Mapped[NodeType] = mapped_column(Enum(NodeType), nullable=False, index=True)
    approval_type: Mapped[Optional[ApprovalType]] = mapped_column(Enum(ApprovalType), nullable=True)
    pass_percentage: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    required_role_id: Mapped[Optional[int]] = mapped_column(Integer, ForeignKey("roles.id"), nullable=True, index=True)
    required_user_id: Mapped[Optional[int]] = mapped_column(Integer, ForeignKey("users.id"), nullable=True, index=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False, index=True)

    workflow: Mapped["ApprovalWorkflow"] = relationship("ApprovalWorkflow", back_populates="nodes")
    required_role: Mapped[Optional["Role"]] = relationship("Role")
    required_user: Mapped[Optional["User"]] = relationship("User")
    records: Mapped[List["ApprovalRecord"]] = relationship(
        "ApprovalRecord", back_populates="node"
    )

    __table_args__ = (
        Index("ix_approval_node_workflow_sort", "workflow_id", "sort_order", unique=True),
    )

    def __repr__(self) -> str:
        return (
            f"<ApprovalNode(id={self.id}, workflow_id={self.workflow_id}, "
            f"node_name='{self.node_name}', node_type='{self.node_type}')>"
        )


class ApprovalRecord(Base):
    __tablename__ = "approval_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, index=True)
    workflow_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("approval_workflows.id"), nullable=False, index=True
    )
    node_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("approval_nodes.id"), nullable=False, index=True
    )
    resource_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    resource_type: Mapped[ResourceType] = mapped_column(Enum(ResourceType), nullable=False, index=True)
    approver_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    status: Mapped[ApprovalStatus] = mapped_column(
        Enum(ApprovalStatus), nullable=False, default=ApprovalStatus.PENDING, index=True
    )
    approval_opinion: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    approved_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False, index=True)

    workflow: Mapped["ApprovalWorkflow"] = relationship("ApprovalWorkflow", back_populates="records")
    node: Mapped["ApprovalNode"] = relationship("ApprovalNode", back_populates="records")
    approver: Mapped["User"] = relationship("User")

    __table_args__ = (
        Index("ix_approval_record_resource", "resource_type", "resource_id"),
        Index("ix_approval_record_workflow_node", "workflow_id", "node_id"),
        Index("ix_approval_record_approver_status", "approver_id", "status"),
    )

    def __repr__(self) -> str:
        return (
            f"<ApprovalRecord(id={self.id}, resource_type='{self.resource_type}', "
            f"resource_id={self.resource_id}, status='{self.status}')>"
        )
