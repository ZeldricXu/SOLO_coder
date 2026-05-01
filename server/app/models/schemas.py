from pydantic import BaseModel, Field
from typing import Optional, List
from enum import Enum
from datetime import datetime
import uuid


class RelationType(str, Enum):
    contradicts = "contradicts"
    supports = "supports"
    explains = "explains"


class RelatedNode(BaseModel):
    target_id: str
    relation_type: RelationType
    reason: str


class KnowledgeCardBase(BaseModel):
    content: str
    source_url: Optional[str] = None
    tags: List[str] = Field(default_factory=list)


class KnowledgeCardCreate(KnowledgeCardBase):
    pass


class KnowledgeCard(KnowledgeCardBase):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    vector_embedding: Optional[List[float]] = None
    related_nodes: List[RelatedNode] = Field(default_factory=list)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        from_attributes = True


class SemanticSuggestion(BaseModel):
    card: KnowledgeCard
    similarity: float
    reason: str


class GraphNode(BaseModel):
    id: str
    label: str
    color: Optional[str] = None
    size: Optional[float] = None


class GraphLink(BaseModel):
    source: str
    target: str
    relation: str


class GraphData(BaseModel):
    nodes: List[GraphNode]
    links: List[GraphLink]


class IngestRequest(BaseModel):
    content: str
    source_url: Optional[str] = None
    tags: List[str] = Field(default_factory=list)


class SuggestRequest(BaseModel):
    text: str
