"""Pydantic schemas for the doc_index module."""
from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field, field_validator

from devportal.core.schemas import Entity, PaginatedResponse


class DocumentSourceBase(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    source_type: str = Field(..., pattern="^(confluence|github|gitlab|local|url)$")
    config: dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    sync_interval: int = Field(3600, ge=60, le=86400)


class DocumentSourceCreate(DocumentSourceBase):
    pass


class DocumentSourceUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=255)
    source_type: Optional[str] = Field(None, pattern="^(confluence|github|gitlab|local|url)$")
    config: Optional[dict[str, Any]] = None
    enabled: Optional[bool] = None
    sync_interval: Optional[int] = Field(None, ge=60, le=86400)


class DocumentSourceResponse(Entity, DocumentSourceBase):
    last_sync_at: Optional[datetime] = None
    documents_count: int = 0

    class Config:
        from_attributes = True


class DocumentPermissionBase(BaseModel):
    principal_type: str = Field(..., pattern="^(user|group|role)$")
    principal_id: str = Field(..., min_length=1, max_length=255)
    permission: str = Field(..., pattern="^(read|write|admin)$")


class DocumentPermissionCreate(DocumentPermissionBase):
    document_id: str = Field(..., min_length=1, max_length=50)


class DocumentPermissionResponse(Entity, DocumentPermissionBase):
    document_id: str

    class Config:
        from_attributes = True


class DocumentBase(BaseModel):
    title: str = Field(..., min_length=1, max_length=1024)
    content: str = Field(..., min_length=1)
    summary: Optional[str] = None
    url: Optional[str] = Field(None, max_length=2048)
    source_document_id: Optional[str] = None
    path: Optional[str] = None
    mime_type: str = "text/markdown"
    language: str = Field("en", min_length=2, max_length=10)
    tags: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)
    permissions: list[DocumentPermissionBase] = Field(default_factory=list)


class DocumentCreate(DocumentBase):
    source_id: str = Field(..., min_length=1, max_length=50)


class DocumentUpdate(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=1024)
    content: Optional[str] = None
    summary: Optional[str] = None
    url: Optional[str] = Field(None, max_length=2048)
    path: Optional[str] = None
    mime_type: Optional[str] = None
    language: Optional[str] = Field(None, min_length=2, max_length=10)
    tags: Optional[list[str]] = None
    metadata: Optional[dict[str, Any]] = None


class DocumentResponse(Entity, DocumentBase):
    source_id: str
    source_name: Optional[str] = None
    indexed_at: datetime
    checksum: str

    class Config:
        from_attributes = True


class IndexJobBase(BaseModel):
    source_id: str = Field(..., min_length=1, max_length=50)
    job_type: str = Field(..., pattern="^(full|incremental|delete)$")


class IndexJobCreate(IndexJobBase):
    pass


class IndexJobResponse(Entity, IndexJobBase):
    phase: str
    progress: float
    started_at: datetime
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None
    documents_indexed: int = 0
    documents_failed: int = 0
    documents_deleted: int = 0
    error_details: Optional[dict[str, Any]] = None

    class Config:
        from_attributes = True


class SearchResult(BaseModel):
    document: DocumentResponse
    score: float
    highlights: list[str] = Field(default_factory=list)


class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=1024)
    source_ids: Optional[list[str]] = None
    tags: Optional[list[str]] = None
    mime_types: Optional[list[str]] = None
    languages: Optional[list[str]] = None
    date_from: Optional[datetime] = None
    date_to: Optional[datetime] = None
    limit: int = Field(20, ge=1, le=100)
    offset: int = Field(0, ge=0)
    include_highlights: bool = True

    @field_validator("query")
    @classmethod
    def validate_query(cls, v: str) -> str:
        if len(v.strip()) == 0:
            raise ValueError("Query cannot be empty")
        return v.strip()


class SearchResponse(BaseModel):
    query: str
    total: int
    limit: int
    offset: int
    execution_time_ms: int
    results: list[SearchResult]


class SyncRequest(BaseModel):
    source_id: str = Field(..., min_length=1, max_length=50)
    sync_type: str = Field("incremental", pattern="^(full|incremental)$")


class SyncResponse(BaseModel):
    job_id: str
    status: str
    message: str


class SearchAnalyticsQuery(BaseModel):
    date_from: Optional[datetime] = None
    date_to: Optional[datetime] = None
    user_id: Optional[str] = None
    limit: int = Field(100, ge=1, le=1000)


class SearchAnalyticsItem(BaseModel):
    query: str
    count: int
    avg_execution_time_ms: int
    avg_results_count: float


class SearchAnalyticsResponse(BaseModel):
    total_queries: int
    unique_queries: int
    avg_execution_time_ms: float
    top_queries: list[SearchAnalyticsItem]


# Paginated responses
class PaginatedDocumentSources(PaginatedResponse[DocumentSourceResponse]):
    pass


class PaginatedDocuments(PaginatedResponse[DocumentResponse]):
    pass


class PaginatedIndexJobs(PaginatedResponse[IndexJobResponse]):
    pass


class PaginatedPermissions(PaginatedResponse[DocumentPermissionResponse]):
    pass
