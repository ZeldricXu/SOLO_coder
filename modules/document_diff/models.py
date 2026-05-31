from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import Column, String, Integer, Float, JSON, DateTime, Enum as SQLEnum, Boolean, Text, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id, utc_now
from models.base import BaseModel


class ChangeType(str, Enum):
    INSERT = "insert"
    DELETE = "delete"
    MODIFY = "modify"
    UNCHANGED = "unchanged"


class ChangeSeverity(str, Enum):
    MINOR = "minor"
    MODERATE = "moderate"
    MAJOR = "major"
    CRITICAL = "critical"


class DocumentType(str, Enum):
    CONTRACT = "contract"
    AGREEMENT = "agreement"
    POLICY = "policy"
    SPECIFICATION = "specification"
    REPORT = "report"
    OTHER = "other"


class DiffAlgorithm(str, Enum):
    LINE_DIFF = "line_diff"
    WORD_DIFF = "word_diff"
    SEMANTIC_DIFF = "semantic_diff"
    STRUCTURED_DIFF = "structured_diff"


class DocumentVersion(Base):
    __tablename__ = "document_versions"

    version_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("docv")
    )
    document_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    version_number: Mapped[int] = mapped_column(Integer, default=1)
    title: Mapped[str] = mapped_column(String(512), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    document_type: Mapped[DocumentType] = mapped_column(
        SQLEnum(DocumentType), default=DocumentType.OTHER
    )
    key_clauses: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    meta_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)


class DocumentDiffResult(Base):
    __tablename__ = "document_diff_results"

    diff_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("difr")
    )
    base_version_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    compare_version_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    algorithm: Mapped[DiffAlgorithm] = mapped_column(
        SQLEnum(DiffAlgorithm), default=DiffAlgorithm.WORD_DIFF
    )
    line_diffs: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    word_diffs: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    highlighted_changes: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    clause_changes: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    change_summary: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    similarity_score: Mapped[float] = mapped_column(Float, default=0.0)
    insertions_count: Mapped[int] = mapped_column(Integer, default=0)
    deletions_count: Mapped[int] = mapped_column(Integer, default=0)
    modifications_count: Mapped[int] = mapped_column(Integer, default=0)
    critical_changes: Mapped[List[Dict[str, Any]]] = mapped_column(JSON, default=list)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    created_by: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class KeyClauseDefinition(Base):
    __tablename__ = "key_clause_definitions"

    clause_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("clsd")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    document_type: Mapped[DocumentType] = mapped_column(SQLEnum(DocumentType))
    keywords: Mapped[List[str]] = mapped_column(JSON, default=list)
    patterns: Mapped[List[str]] = mapped_column(JSON, default=list)
    severity: Mapped[ChangeSeverity] = mapped_column(
        SQLEnum(ChangeSeverity), default=ChangeSeverity.MODERATE
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class DocumentCreate(BaseModel):
    document_id: str
    title: str
    content: str
    document_type: DocumentType = DocumentType.OTHER
    key_clauses: List[Dict[str, Any]] = []
    meta_data: Dict[str, Any] = {}
    created_by: Optional[str] = None
    tenant_id: Optional[str] = None


class DocumentVersionResponse(BaseModel):
    version_id: str
    document_id: str
    version_number: int
    title: str
    content: Optional[str] = None
    document_type: DocumentType
    key_clauses: List[Dict[str, Any]]
    meta_data: Dict[str, Any]
    created_by: Optional[str]
    created_at: datetime


class DocumentDiffRequest(BaseModel):
    base_version_id: str
    compare_version_id: str
    algorithm: DiffAlgorithm = DiffAlgorithm.WORD_DIFF
    highlight_changes: bool = True
    detect_clause_changes: bool = True
    tenant_id: Optional[str] = None
    created_by: Optional[str] = None


class ChangeDetail(BaseModel):
    change_id: str
    change_type: ChangeType
    severity: ChangeSeverity
    line_number: Optional[int] = None
    position: Optional[int] = None
    length: Optional[int] = None
    original_text: str
    modified_text: str
    context_before: Optional[str] = None
    context_after: Optional[str] = None
    clause_reference: Optional[str] = None
    is_key_clause: bool = False


class HighlightedChange(BaseModel):
    html_content: str
    plain_content: str
    change_type: ChangeType
    position: int
    length: int


class ChangeSummary(BaseModel):
    total_changes: int
    insertions: int
    deletions: int
    modifications: int
    similarity_score: float
    critical_changes: int
    major_changes: int
    moderate_changes: int
    minor_changes: int
    affected_clauses: List[str]
    summary_text: str


class DocumentDiffResponse(BaseModel):
    diff_id: str
    base_version_id: str
    compare_version_id: str
    algorithm: DiffAlgorithm
    changes: List[ChangeDetail]
    highlighted_html: Optional[str] = None
    summary: ChangeSummary
    clause_changes: List[Dict[str, Any]]
    created_at: datetime


class ClauseDefinitionCreate(BaseModel):
    name: str
    description: Optional[str] = None
    document_type: DocumentType
    keywords: List[str] = []
    patterns: List[str] = []
    severity: ChangeSeverity = ChangeSeverity.MODERATE
    tenant_id: Optional[str] = None


class ClauseDefinitionResponse(BaseModel):
    clause_id: str
    name: str
    description: Optional[str]
    document_type: DocumentType
    keywords: List[str]
    patterns: List[str]
    severity: ChangeSeverity
    is_active: bool
    created_at: datetime
