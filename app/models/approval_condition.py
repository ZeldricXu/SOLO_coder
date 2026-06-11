from __future__ import annotations
from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, Any

from sqlalchemy import Boolean, DateTime, Enum, ForeignKey, Integer, String, JSON, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class ConditionOperator(PyEnum):
    EQ = "EQ"
    GT = "GT"
    LT = "LT"
    GTE = "GTE"
    LTE = "LTE"
    IN = "IN"
    NOT_IN = "NOT_IN"
    CONTAINS = "CONTAINS"


class ConditionType(PyEnum):
    AMOUNT_RANGE = "AMOUNT_RANGE"
    CATEGORY = "CATEGORY"
    WAREHOUSE_REGION = "WAREHOUSE_REGION"
    ROLE = "ROLE"
    DEPARTMENT = "DEPARTMENT"


class ApprovalCondition(Base):
    __tablename__ = "approval_conditions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True, index=True)
    workflow_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("approval_workflows.id", ondelete="CASCADE"), nullable=True, index=True
    )
    node_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("approval_nodes.id", ondelete="CASCADE"), nullable=True, index=True
    )
    condition_type: Mapped[ConditionType] = mapped_column(Enum(ConditionType), nullable=False, index=True)
    field_name: Mapped[str] = mapped_column(String(100), nullable=False)
    operator: Mapped[ConditionOperator] = mapped_column(Enum(ConditionOperator), nullable=False)
    value: Mapped[Any] = mapped_column(JSON, nullable=False)
    target_node_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("approval_nodes.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False, index=True)

    workflow: Mapped[Optional["ApprovalWorkflow"]] = relationship(
        "ApprovalWorkflow", back_populates="conditions_list"
    )
    node: Mapped[Optional["ApprovalNode"]] = relationship(
        "ApprovalNode", back_populates="conditions_list", foreign_keys=[node_id]
    )
    target_node: Mapped[Optional["ApprovalNode"]] = relationship(
        "ApprovalNode", foreign_keys=[target_node_id]
    )

    __table_args__ = (
        Index("ix_approval_condition_workflow", "workflow_id"),
        Index("ix_approval_condition_node", "node_id"),
        Index("ix_approval_condition_type", "condition_type"),
    )

    def __repr__(self) -> str:
        return (
            f"<ApprovalCondition(id={self.id}, condition_type='{self.condition_type}', "
            f"field_name='{self.field_name}', operator='{self.operator}')>"
        )
