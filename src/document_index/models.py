from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from src.common.models import generate_id, utc_now


class DocumentSource(str, Enum):
    CONFLUENCE = "confluence"
    GITHUB = "github"
    GITLAB = "gitlab"
    NOTION = "notion"
    GOOGLE_DOCS = "google_docs"
    LOCAL_FILE = "local_file"
    WEB = "web"
    CUSTOM = "custom"


class DocumentStatus(str, Enum):
    PENDING = "pending"
    INDEXED = "indexed"
    FAILED = "failed"
    ARCHIVED = "archived"


class DocumentChunk(BaseModel):
    chunk_id: str = Field(default_factory=lambda: generate_id("chk"))
    content: str
    metadata: Dict[str, Any] = Field(default_factory=dict)
    embedding: Optional[List[float]] = None
    position: int = 0


class Document(BaseModel):
    doc_id: str = Field(default_factory=lambda: generate_id("doc"))
    title: str
    content: str
    source: DocumentSource
    source_id: Optional[str] = None
    source_url: Optional[str] = None
    status: DocumentStatus = DocumentStatus.PENDING
    mime_type: str = "text/plain"
    language: str = "en"
    tags: List[str] = Field(default_factory=list)
    categories: List[str] = Field(default_factory=list)
    acl: List[str] = Field(default_factory=list)
    owner_id: Optional[str] = None
    last_modified_by: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
    chunks: List[DocumentChunk] = Field(default_factory=list)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)
    indexed_at: Optional[datetime] = None


class SearchQuery(BaseModel):
    query: str
    filters: Dict[str, Any] = Field(default_factory=dict)
    tags: List[str] = Field(default_factory=list)
    categories: List[str] = Field(default_factory=list)
    sources: List[DocumentSource] = Field(default_factory=list)
    user_id: Optional[str] = None
    user_roles: List[str] = Field(default_factory=list)
    page: int = 1
    page_size: int = 20


class SearchResult(BaseModel):
    doc_id: str
    title: str
    snippet: str
    source: DocumentSource
    score: float
    tags: List[str]
    source_url: Optional[str] = None
    highlighted: Optional[Dict[str, List[str]]] = None


class SearchResponse(BaseModel):
    results: List[SearchResult]
    total: int
    page: int
    page_size: int
    processing_time_ms: float
