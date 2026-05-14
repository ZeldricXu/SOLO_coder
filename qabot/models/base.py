from datetime import datetime, timezone
from typing import List, Optional, Any, Dict
from pydantic import BaseModel, Field


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class KnowledgeBase(BaseModel):
    knowledge_title: str
    knowledge_content: str
    knowledge_category: str
    knowledge_tags: List[str] = Field(default_factory=list)
    knowledge_keywords: List[str] = Field(default_factory=list)


class KnowledgeCreate(KnowledgeBase):
    pass


class KnowledgeUpdate(BaseModel):
    knowledge_title: Optional[str] = None
    knowledge_content: Optional[str] = None
    knowledge_category: Optional[str] = None
    knowledge_tags: Optional[List[str]] = None
    knowledge_keywords: Optional[List[str]] = None


class Knowledge(KnowledgeBase):
    knowledge_id: str
    created_at: str = Field(default_factory=utc_now)
    updated_at: str = Field(default_factory=utc_now)
    view_count: int = 0
    needs_update: bool = False
    related_knowledge: List[str] = Field(default_factory=list)


class KnowledgeInList(BaseModel):
    knowledge_id: str
    knowledge_title: str
    knowledge_category: str
    knowledge_tags: List[str]
    view_count: int


class QARecordBase(BaseModel):
    user_id: str
    question: str


class QARecordCreate(QARecordBase):
    pass


class QARecord(BaseModel):
    qa_id: str
    user_id: str
    question: str
    matched_knowledge: Optional[str] = None
    reply_content: str
    reply_type: str
    match_score: Optional[float] = None
    intent_category: Optional[str] = None
    created_at: str = Field(default_factory=utc_now)
    satisfaction: Optional[int] = None


class ReplyTemplateBase(BaseModel):
    template_name: str
    template_content: str
    template_type: str


class ReplyTemplateCreate(ReplyTemplateBase):
    pass


class ReplyTemplate(ReplyTemplateBase):
    template_id: str
    created_at: str = Field(default_factory=utc_now)


class IntentBase(BaseModel):
    intent_name: str
    intent_keywords: List[str]
    intent_category: str


class IntentCreate(IntentBase):
    pass


class Intent(IntentBase):
    intent_id: str


class QAStatsBase(BaseModel):
    total_questions: int = 0
    matched_questions: int = 0
    unmatched_questions: int = 0
    total_satisfaction_score: int = 0
    satisfaction_count: int = 0


class QAStats(QAStatsBase):
    stat_id: str
    stat_date: str


class QAStatsResponse(BaseModel):
    stat_date: str
    total_questions: int
    matched_questions: int
    unmatched_questions: int
    avg_satisfaction: Optional[float]
    matched_rate: float


class RecommendRecord(BaseModel):
    recommend_id: str
    qa_id: str
    recommend_knowledge: List[str]
    recommend_type: str
    generated_at: str = Field(default_factory=utc_now)


class RecommendItem(BaseModel):
    knowledge_id: str
    knowledge_title: str
    knowledge_category: str
    score: float
    recommend_type: str


class QAResponse(BaseModel):
    reply: str
    reply_type: str
    match_score: Optional[float]
    matched_knowledge_id: Optional[str]
    intent_category: Optional[str]
    recommendations: List[RecommendItem]


class ApiResponse(BaseModel):
    code: int = 200
    data: Any
    message: str = "success"
