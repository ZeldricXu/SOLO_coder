"""Database models for the doc_index module."""
from __future__ import annotations

from typing import Optional

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from devportal.core.database import Base
from devportal.core.models import CoreEntity, ConfigModel, RunInstance, generate_id


class DocumentSource(CoreEntity):
    """Represents a source of documents (Confluence, GitHub, GitLab, etc.)."""

    __tablename__ = "doc_sources"

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    source_type: Mapped[str] = mapped_column(String(50), nullable=False)  # confluence, github, gitlab, local, url
    config: Mapped[dict] = mapped_column(JSON, nullable=False, default=dict)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    sync_interval: Mapped[int] = mapped_column(Integer, default=3600)  # seconds
    last_sync_at: Mapped[Optional[DateTime]] = mapped_column(DateTime, nullable=True)

    documents: Mapped[list["Document"]] = relationship(
        "Document", back_populates="source", cascade="all, delete-orphan"
    )
    index_jobs: Mapped[list["IndexJob"]] = relationship(
        "IndexJob", back_populates="source", cascade="all, delete-orphan"
    )


class Document(CoreEntity):
    """Represents an indexed document."""

    __tablename__ = "documents"

    title: Mapped[str] = mapped_column(String(1024), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    summary: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    url: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    source_id: Mapped[str] = mapped_column(String(50), ForeignKey("doc_sources.id"), nullable=False)
    source_document_id: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    path: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    mime_type: Mapped[str] = mapped_column(String(100), default="text/markdown")
    language: Mapped[str] = mapped_column(String(10), default="en")
    tags: Mapped[dict] = mapped_column(JSON, nullable=False, default=list)
    metadata: Mapped[dict] = mapped_column(JSON, nullable=False, default=dict)
    indexed_at: Mapped[DateTime] = mapped_column(DateTime, nullable=False)
    checksum: Mapped[str] = mapped_column(String(64), nullable=False)

    source: Mapped[DocumentSource] = relationship("DocumentSource", back_populates="documents")
    permissions: Mapped[list["DocumentPermission"]] = relationship(
        "DocumentPermission", back_populates="document", cascade="all, delete-orphan"
    )

    __table_args__ = (
        UniqueConstraint("source_id", "source_document_id", name="uix_source_document"),
    )


class DocumentPermission(CoreEntity):
    """Represents access permissions for a document."""

    __tablename__ = "document_permissions"

    document_id: Mapped[str] = mapped_column(String(50), ForeignKey("documents.id"), nullable=False)
    principal_type: Mapped[str] = mapped_column(String(50), nullable=False)  # user, group, role
    principal_id: Mapped[str] = mapped_column(String(255), nullable=False)
    permission: Mapped[str] = mapped_column(String(50), nullable=False)  # read, write, admin

    document: Mapped[Document] = relationship("Document", back_populates="permissions")

    __table_args__ = (
        UniqueConstraint(
            "document_id", "principal_type", "principal_id", "permission",
            name="uix_doc_permission"
        ),
    )


class IndexJob(RunInstance):
    """Represents a background indexing job."""

    __tablename__ = "index_jobs"

    source_id: Mapped[str] = mapped_column(String(50), ForeignKey("doc_sources.id"), nullable=False)
    job_type: Mapped[str] = mapped_column(String(50), nullable=False)  # full, incremental, delete
    documents_indexed: Mapped[int] = mapped_column(Integer, default=0)
    documents_failed: Mapped[int] = mapped_column(Integer, default=0)
    documents_deleted: Mapped[int] = mapped_column(Integer, default=0)
    error_details: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)

    source: Mapped[DocumentSource] = relationship("DocumentSource", back_populates="index_jobs")


class SearchQueryLog(CoreEntity):
    """Represents search queries for analytics."""

    __tablename__ = "search_query_logs"

    query: Mapped[str] = mapped_column(String(1024), nullable=False)
    user_id: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    results_count: Mapped[int] = mapped_column(Integer, default=0)
    execution_time_ms: Mapped[int] = mapped_column(Integer, default=0)
    filters: Mapped[dict] = mapped_column(JSON, nullable=False, default=dict)
