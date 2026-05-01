from sqlalchemy import Column, String, Text, DateTime, ForeignKey, Enum as SQLEnum
from sqlalchemy.dialects.postgresql import UUID, ARRAY, JSONB
from sqlalchemy.orm import relationship
from datetime import datetime
import uuid
from app.core.database import Base
from app.models.schemas import RelationType


class KnowledgeCardModel(Base):
    __tablename__ = "knowledge_cards"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    content = Column(Text, nullable=False)
    source_url = Column(String(500), nullable=True)
    tags = Column(ARRAY(String), default=[])
    vector_embedding = Column(ARRAY(float), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    outgoing_relations = relationship(
        "KnowledgeRelation",
        foreign_keys="KnowledgeRelation.source_id",
        back_populates="source_card",
    )
    incoming_relations = relationship(
        "KnowledgeRelation",
        foreign_keys="KnowledgeRelation.target_id",
        back_populates="target_card",
    )


class KnowledgeRelationModel(Base):
    __tablename__ = "knowledge_relations"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    source_id = Column(UUID(as_uuid=True), ForeignKey("knowledge_cards.id"), nullable=False)
    target_id = Column(UUID(as_uuid=True), ForeignKey("knowledge_cards.id"), nullable=False)
    relation_type = Column(SQLEnum(RelationType), nullable=False)
    reason = Column(Text, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    source_card = relationship(
        "KnowledgeCardModel",
        foreign_keys=[source_id],
        back_populates="outgoing_relations",
    )
    target_card = relationship(
        "KnowledgeCardModel",
        foreign_keys=[target_id],
        back_populates="incoming_relations",
    )
