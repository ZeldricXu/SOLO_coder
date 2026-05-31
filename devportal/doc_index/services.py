"""Services for the doc_index module."""
from __future__ import annotations

import asyncio
import logging
import os
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Optional

from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload
from whoosh import index as whoosh_index
from whoosh.analysis import LanguageAnalyzer, StandardAnalyzer
from whoosh.fields import ID, KEYWORD, NUMERIC, STORED, TEXT, Schema
from whoosh.highlight import FragmentScorer, Highlighter, WholeFragmenter
from whoosh.qparser import AndGroup, MultifieldParser, OrGroup, QueryParser
from whoosh.query import Every, Phrase, Prefix, Term
from whoosh.sorting import Count, Facets, ScoreFacet, FieldFacet

from devportal.core.config import settings
from devportal.core.exceptions import ConflictError, NotFoundError, ValidationError
from devportal.core.models import User
from devportal.core.utils import (
    MetricsRecorder,
    generate_id,
    get_trace_id,
    processing_context,
    sha256_hash,
    utc_now,
)
from devportal.doc_index.models import (
    Document,
    DocumentPermission,
    DocumentSource,
    IndexJob,
    SearchQueryLog,
)
from devportal.doc_index.schemas import (
    DocumentCreate,
    DocumentPermissionCreate,
    DocumentSourceCreate,
    DocumentSourceUpdate,
    DocumentUpdate,
    IndexJobCreate,
    SearchAnalyticsQuery,
    SearchAnalyticsResponse,
    SearchRequest,
    SearchResponse,
    SearchResult,
    SyncRequest,
    SyncResponse,
)

logger = logging.getLogger(__name__)


class WhooshIndexManager:
    """Manages the Whoosh full-text search index."""

    def __init__(self, index_dir: str = settings.whoosh_index_dir):
        self.index_dir = Path(index_dir)
        self.index_dir.mkdir(parents=True, exist_ok=True)
        self._index: Optional[whoosh_index.Index] = None
        self._schema = self._create_schema()
        self._ensure_index()

    def _create_schema(self) -> Schema:
        """Create the Whoosh schema for document indexing."""
        return Schema(
            id=ID(stored=True, unique=True),
            title=TEXT(stored=True, analyzer=LanguageAnalyzer("en")),
            content=TEXT(stored=True, analyzer=LanguageAnalyzer("en")),
            summary=TEXT(stored=True, analyzer=LanguageAnalyzer("en")),
            path=ID(stored=True),
            url=STORED(),
            source_id=ID(stored=True),
            source_name=TEXT(stored=True),
            source_document_id=ID(stored=True),
            mime_type=ID(stored=True),
            language=ID(stored=True),
            tags=KEYWORD(stored=True, commas=True, lowercase=True),
            indexed_at=NUMERIC(stored=True, sortable=True),
            created_at=NUMERIC(stored=True, sortable=True),
            updated_at=NUMERIC(stored=True, sortable=True),
        )

    def _ensure_index(self) -> None:
        """Ensure the index exists, create if not."""
        index_path = str(self.index_dir)
        if whoosh_index.exists_in(index_path):
            self._index = whoosh_index.open_dir(index_path)
        else:
            self._index = whoosh_index.create_in(index_path, self._schema)

    @property
    def index(self) -> whoosh_index.Index:
        """Get the Whoosh index instance."""
        if self._index is None:
            self._ensure_index()
        assert self._index is not None
        return self._index

    async def add_document(self, doc: Document, source_name: str) -> None:
        """Add a document to the index."""
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, self._add_document_sync, doc, source_name)

    def _add_document_sync(self, doc: Document, source_name: str) -> None:
        """Synchronous document addition."""
        writer = self.index.writer()
        try:
            writer.update_document(
                id=doc.id,
                title=doc.title,
                content=doc.content,
                summary=doc.summary or "",
                path=doc.path or "",
                url=doc.url or "",
                source_id=doc.source_id,
                source_name=source_name,
                source_document_id=doc.source_document_id or "",
                mime_type=doc.mime_type,
                language=doc.language,
                tags=",".join(doc.tags if isinstance(doc.tags, list) else []),
                indexed_at=doc.indexed_at.timestamp(),
                created_at=doc.created_at.timestamp(),
                updated_at=doc.updated_at.timestamp(),
            )
            writer.commit()
        except Exception:
            writer.cancel()
            raise

    async def delete_document(self, doc_id: str) -> None:
        """Delete a document from the index."""
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, self._delete_document_sync, doc_id)

    def _delete_document_sync(self, doc_id: str) -> None:
        """Synchronous document deletion."""
        writer = self.index.writer()
        try:
            writer.delete_by_term("id", doc_id)
            writer.commit()
        except Exception:
            writer.cancel()
            raise

    async def delete_by_source(self, source_id: str) -> None:
        """Delete all documents from a source."""
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, self._delete_by_source_sync, source_id)

    def _delete_by_source_sync(self, source_id: str) -> None:
        """Synchronous source document deletion."""
        writer = self.index.writer()
        try:
            writer.delete_by_term("source_id", source_id)
            writer.commit()
        except Exception:
            writer.cancel()
            raise

    async def optimize(self) -> None:
        """Optimize the index."""
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, self._optimize_sync)

    def _optimize_sync(self) -> None:
        """Synchronous index optimization."""
        writer = self.index.writer()
        try:
            writer.commit(optimize=True)
        except Exception:
            writer.cancel()
            raise


class PermissionFilter:
    """Handles permission filtering for documents."""

    def __init__(self, db: AsyncSession):
        self.db = db

    async def get_allowed_document_ids(self, user: User) -> set[str]:
        """Get the set of document IDs the user has read access to."""
        if user.role == "admin":
            result = await self.db.execute(select(Document.id))
            return {row[0] for row in result.all()}

        conditions = [
            and_(
                DocumentPermission.principal_type == "user",
                DocumentPermission.principal_id == user.id,
                DocumentPermission.permission == "read",
            ),
        ]

        if user.groups:
            conditions.append(
                and_(
                    DocumentPermission.principal_type == "group",
                    DocumentPermission.principal_id.in_(user.groups),
                    DocumentPermission.permission == "read",
                )
            )

        if user.role:
            conditions.append(
                and_(
                    DocumentPermission.principal_type == "role",
                    DocumentPermission.principal_id == user.role,
                    DocumentPermission.permission == "read",
                )
            )

        stmt = select(DocumentPermission.document_id).where(or_(*conditions))
        result = await self.db.execute(stmt)
        return {row[0] for row in result.all()}

    async def check_permission(
        self, document_id: str, user: User, permission: str = "read"
    ) -> bool:
        """Check if a user has a specific permission on a document."""
        if user.role == "admin":
            return True

        conditions = [
            and_(
                DocumentPermission.document_id == document_id,
                DocumentPermission.principal_type == "user",
                DocumentPermission.principal_id == user.id,
                DocumentPermission.permission == permission,
            ),
        ]

        if user.groups:
            conditions.append(
                and_(
                    DocumentPermission.document_id == document_id,
                    DocumentPermission.principal_type == "group",
                    DocumentPermission.principal_id.in_(user.groups),
                    DocumentPermission.permission == permission,
                )
            )

        if user.role:
            conditions.append(
                and_(
                    DocumentPermission.document_id == document_id,
                    DocumentPermission.principal_type == "role",
                    DocumentPermission.principal_id == user.role,
                    DocumentPermission.permission == permission,
                )
            )

        stmt = select(DocumentPermission).where(or_(*conditions)).limit(1)
        result = await self.db.execute(stmt)
        return result.scalar_one_or_none() is not None


class DocumentSourceService:
    """Service for managing document sources."""

    def __init__(self, db: AsyncSession):
        self.db = db

    async def list_sources(
        self, skip: int = 0, limit: int = 50, enabled_only: bool = False
    ) -> tuple[list[DocumentSource], int]:
        """List document sources with pagination."""
        stmt = select(DocumentSource)
        if enabled_only:
            stmt = stmt.where(DocumentSource.enabled == True)
        stmt = stmt.options(selectinload(DocumentSource.documents)).offset(skip).limit(limit)
        result = await self.db.execute(stmt)
        sources = result.scalars().all()

        count_stmt = select(func.count()).select_from(DocumentSource)
        if enabled_only:
            count_stmt = count_stmt.where(DocumentSource.enabled == True)
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        return list(sources), total

    async def get_source(self, source_id: str) -> DocumentSource:
        """Get a document source by ID."""
        stmt = (
            select(DocumentSource)
            .where(DocumentSource.id == source_id)
            .options(selectinload(DocumentSource.documents))
        )
        result = await self.db.execute(stmt)
        source = result.scalar_one_or_none()
        if not source:
            raise NotFoundError(f"Document source not found: {source_id}")
        return source

    async def create_source(self, source_data: DocumentSourceCreate) -> DocumentSource:
        """Create a new document source."""
        stmt = select(DocumentSource).where(DocumentSource.name == source_data.name)
        result = await self.db.execute(stmt)
        if result.scalar_one_or_none():
            raise ConflictError(f"Document source already exists: {source_data.name}")

        source = DocumentSource(
            id=generate_id("src"),
            **source_data.model_dump(),
        )
        self.db.add(source)
        await self.db.commit()
        await self.db.refresh(source)
        return source

    async def update_source(
        self, source_id: str, source_data: DocumentSourceUpdate
    ) -> DocumentSource:
        """Update a document source."""
        source = await self.get_source(source_id)
        update_data = source_data.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(source, key, value)
        source.updated_at = utc_now()
        await self.db.commit()
        await self.db.refresh(source)
        return source

    async def delete_source(self, source_id: str) -> None:
        """Delete a document source and all associated documents."""
        source = await self.get_source(source_id)

        await self.db.execute(delete(Document).where(Document.source_id == source_id))
        await self.db.delete(source)
        await self.db.commit()

        index_manager = WhooshIndexManager()
        await index_manager.delete_by_source(source_id)


class DocumentService:
    """Service for managing documents."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.index_manager = WhooshIndexManager()
        self.permission_filter = PermissionFilter(db)

    async def list_documents(
        self,
        user: User,
        skip: int = 0,
        limit: int = 50,
        source_id: Optional[str] = None,
        tags: Optional[list[str]] = None,
    ) -> tuple[list[Document], int]:
        """List documents with pagination and permission filtering."""
        allowed_ids = await self.permission_filter.get_allowed_document_ids(user)
        if not allowed_ids:
            return [], 0

        stmt = (
            select(Document)
            .where(Document.id.in_(allowed_ids))
            .options(selectinload(Document.source), selectinload(Document.permissions))
        )

        if source_id:
            stmt = stmt.where(Document.source_id == source_id)
        if tags:
            for tag in tags:
                stmt = stmt.where(Document.tags.op("->")(func.concat("$[", func.row_number().over(), "]")) == tag)

        stmt = stmt.order_by(Document.updated_at.desc()).offset(skip).limit(limit)
        result = await self.db.execute(stmt)
        documents = result.scalars().all()

        count_stmt = select(func.count()).select_from(Document).where(Document.id.in_(allowed_ids))
        if source_id:
            count_stmt = count_stmt.where(Document.source_id == source_id)
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        return list(documents), total

    async def get_document(self, doc_id: str, user: User) -> Document:
        """Get a document by ID with permission check."""
        if not await self.permission_filter.check_permission(doc_id, user, "read"):
            raise NotFoundError(f"Document not found: {doc_id}")

        stmt = (
            select(Document)
            .where(Document.id == doc_id)
            .options(selectinload(Document.source), selectinload(Document.permissions))
        )
        result = await self.db.execute(stmt)
        document = result.scalar_one_or_none()
        if not document:
            raise NotFoundError(f"Document not found: {doc_id}")
        return document

    async def create_document(self, doc_data: DocumentCreate, user: User) -> Document:
        """Create a new document and index it."""
        source_service = DocumentSourceService(self.db)
        source = await source_service.get_source(doc_data.source_id)

        checksum = sha256_hash(doc_data.content)
        now = utc_now()

        doc = Document(
            id=generate_id("doc"),
            title=doc_data.title,
            content=doc_data.content,
            summary=doc_data.summary,
            url=doc_data.url,
            source_id=doc_data.source_id,
            source_document_id=doc_data.source_document_id,
            path=doc_data.path,
            mime_type=doc_data.mime_type,
            language=doc_data.language,
            tags=doc_data.tags,
            metadata=doc_data.metadata,
            indexed_at=now,
            checksum=checksum,
            created_at=now,
            updated_at=now,
        )
        self.db.add(doc)

        for perm in doc_data.permissions:
            permission = DocumentPermission(
                id=generate_id("perm"),
                document_id=doc.id,
                principal_type=perm.principal_type,
                principal_id=perm.principal_id,
                permission=perm.permission,
                created_at=now,
                updated_at=now,
            )
            self.db.add(permission)

        await self.db.commit()
        await self.db.refresh(doc)

        await self.index_manager.add_document(doc, source.name)
        return doc

    async def update_document(
        self, doc_id: str, doc_data: DocumentUpdate, user: User
    ) -> Document:
        """Update a document and re-index it."""
        if not await self.permission_filter.check_permission(doc_id, user, "write"):
            raise NotFoundError(f"Document not found: {doc_id}")

        document = await self.get_document(doc_id, user)
        update_data = doc_data.model_dump(exclude_unset=True)

        for key, value in update_data.items():
            setattr(document, key, value)

        if "content" in update_data:
            document.checksum = sha256_hash(update_data["content"])

        document.indexed_at = utc_now()
        document.updated_at = utc_now()

        await self.db.commit()
        await self.db.refresh(document)

        await self.index_manager.add_document(document, document.source.name)
        return document

    async def delete_document(self, doc_id: str, user: User) -> None:
        """Delete a document."""
        if not await self.permission_filter.check_permission(doc_id, user, "admin"):
            raise NotFoundError(f"Document not found: {doc_id}")

        document = await self.get_document(doc_id, user)
        await self.db.delete(document)
        await self.db.commit()

        await self.index_manager.delete_document(doc_id)

    async def add_permission(self, perm_data: DocumentPermissionCreate, user: User) -> DocumentPermission:
        """Add a permission to a document."""
        if not await self.permission_filter.check_permission(perm_data.document_id, user, "admin"):
            raise NotFoundError(f"Document not found: {perm_data.document_id}")

        existing = await self.db.execute(
            select(DocumentPermission).where(
                DocumentPermission.document_id == perm_data.document_id,
                DocumentPermission.principal_type == perm_data.principal_type,
                DocumentPermission.principal_id == perm_data.principal_id,
                DocumentPermission.permission == perm_data.permission,
            )
        )
        if existing.scalar_one_or_none():
            raise ConflictError("Permission already exists")

        now = utc_now()
        permission = DocumentPermission(
            id=generate_id("perm"),
            **perm_data.model_dump(),
            created_at=now,
            updated_at=now,
        )
        self.db.add(permission)
        await self.db.commit()
        await self.db.refresh(permission)
        return permission

    async def remove_permission(self, perm_id: str, user: User) -> None:
        """Remove a permission from a document."""
        stmt = select(DocumentPermission).where(DocumentPermission.id == perm_id)
        result = await self.db.execute(stmt)
        permission = result.scalar_one_or_none()
        if not permission:
            raise NotFoundError(f"Permission not found: {perm_id}")

        if not await self.permission_filter.check_permission(permission.document_id, user, "admin"):
            raise NotFoundError(f"Document not found: {permission.document_id}")

        await self.db.delete(permission)
        await self.db.commit()


class SearchService:
    """Service for searching documents with permission filtering."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.index_manager = WhooshIndexManager()
        self.permission_filter = PermissionFilter(db)

    async def search(self, request: SearchRequest, user: User) -> SearchResponse:
        """Search documents with full-text search and permission filtering."""
        start_time = time.time()
        allowed_ids = await self.permission_filter.get_allowed_document_ids(user)

        loop = asyncio.get_event_loop()
        results, total = await loop.run_in_executor(
            None, self._search_sync, request, allowed_ids
        )

        execution_time_ms = int((time.time() - start_time) * 1000)

        await self._log_query(request, user.id if user else None, total, execution_time_ms)

        return SearchResponse(
            query=request.query,
            total=total,
            limit=request.limit,
            offset=request.offset,
            execution_time_ms=execution_time_ms,
            results=results,
        )

    def _search_sync(
        self, request: SearchRequest, allowed_ids: set[str]
    ) -> tuple[list[SearchResult], int]:
        """Synchronous search implementation."""
        if not allowed_ids:
            return [], 0

        with self.index_manager.index.searcher() as searcher:
            parser = MultifieldParser(
                ["title", "content", "summary", "tags"],
                schema=self.index_manager._schema,
                group=OrGroup,
            )

            query = parser.parse(request.query)

            filter_queries = []
            if allowed_ids:
                from whoosh.query import Or as WhooshOr
                filter_queries.append(
                    WhooshOr([Term("id", doc_id) for doc_id in allowed_ids])
                )

            if request.source_ids:
                from whoosh.query import Or as WhooshOr
                filter_queries.append(
                    WhooshOr([Term("source_id", sid) for sid in request.source_ids])
                )

            if request.tags:
                from whoosh.query import And as WhooshAnd
                tag_queries = [Prefix("tags", tag.lower()) for tag in request.tags]
                filter_queries.append(WhooshAnd(tag_queries))

            if request.mime_types:
                from whoosh.query import Or as WhooshOr
                filter_queries.append(
                    WhooshOr([Term("mime_type", mt) for mt in request.mime_types])
                )

            if request.languages:
                from whoosh.query import Or as WhooshOr
                filter_queries.append(
                    WhooshOr([Term("language", lang) for lang in request.languages])
                )

            if request.date_from:
                filter_queries.append(
                    Term("indexed_at", request.date_from.timestamp(), boost=1.0)
                )

            from whoosh.query import And as WhooshAnd
            if filter_queries:
                query = WhooshAnd([query] + filter_queries)

            results = searcher.search_page(
                query,
                pagenum=(request.offset // request.limit) + 1,
                pagelen=request.limit,
                sortedby=ScoreFacet(),
            )

            search_results: list[SearchResult] = []
            highlighter = Highlighter(
                fragmenter=WholeFragmenter(charlimit=200),
                scorer=FragmentScorer,
                minscore=0,
            )

            for hit in results:
                if request.include_highlights:
                    highlights = []
                    for field in ["title", "content", "summary"]:
                        hl = highlighter.highlight_hit(hit, field)
                        if hl:
                            highlights.append(hl)
                else:
                    highlights = []

                created_at = datetime.fromtimestamp(hit.get("created_at", 0))
                updated_at = datetime.fromtimestamp(hit.get("updated_at", 0))
                indexed_at = datetime.fromtimestamp(hit.get("indexed_at", 0))

                doc_response = {
                    "id": hit["id"],
                    "title": hit["title"],
                    "content": hit["content"],
                    "summary": hit.get("summary"),
                    "url": hit.get("url"),
                    "source_id": hit["source_id"],
                    "source_name": hit.get("source_name"),
                    "source_document_id": hit.get("source_document_id"),
                    "path": hit.get("path"),
                    "mime_type": hit["mime_type"],
                    "language": hit["language"],
                    "tags": hit.get("tags", "").split(",") if hit.get("tags") else [],
                    "permissions": [],
                    "indexed_at": indexed_at,
                    "checksum": "",
                    "created_at": created_at,
                    "updated_at": updated_at,
                }

                from devportal.doc_index.schemas import DocumentResponse
                search_results.append(
                    SearchResult(
                        document=DocumentResponse(**doc_response),
                        score=hit.score,
                        highlights=highlights,
                    )
                )

            return search_results, results.total

    async def _log_query(
        self,
        request: SearchRequest,
        user_id: Optional[str],
        results_count: int,
        execution_time_ms: int,
    ) -> None:
        """Log a search query for analytics."""
        log = SearchQueryLog(
            id=generate_id("sql"),
            query=request.query,
            user_id=user_id,
            results_count=results_count,
            execution_time_ms=execution_time_ms,
            filters={
                "source_ids": request.source_ids,
                "tags": request.tags,
                "mime_types": request.mime_types,
                "languages": request.languages,
            },
        )
        self.db.add(log)
        await self.db.commit()

    async def get_analytics(self, query: SearchAnalyticsQuery) -> SearchAnalyticsResponse:
        """Get search analytics."""
        stmt = select(SearchQueryLog)
        if query.date_from:
            stmt = stmt.where(SearchQueryLog.created_at >= query.date_from)
        if query.date_to:
            stmt = stmt.where(SearchQueryLog.created_at <= query.date_to)
        if query.user_id:
            stmt = stmt.where(SearchQueryLog.user_id == query.user_id)
        stmt = stmt.order_by(SearchQueryLog.created_at.desc()).limit(query.limit)

        result = await self.db.execute(stmt)
        logs = result.scalars().all()

        if not logs:
            return SearchAnalyticsResponse(
                total_queries=0,
                unique_queries=0,
                avg_execution_time_ms=0,
                top_queries=[],
            )

        total_queries = len(logs)
        unique_queries = len(set(log.query for log in logs))
        avg_execution_time = sum(log.execution_time_ms for log in logs) / total_queries

        query_counts: dict[str, list[SearchQueryLog]] = {}
        for log in logs:
            if log.query not in query_counts:
                query_counts[log.query] = []
            query_counts[log.query].append(log)

        top_queries = sorted(
            query_counts.items(), key=lambda x: len(x[1]), reverse=True
        )[:10]

        from devportal.doc_index.schemas import SearchAnalyticsItem
        analytics_items = [
            SearchAnalyticsItem(
                query=q,
                count=len(items),
                avg_execution_time_ms=sum(i.execution_time_ms for i in items)
                // len(items),
                avg_results_count=sum(i.results_count for i in items) / len(items),
            )
            for q, items in top_queries
        ]

        return SearchAnalyticsResponse(
            total_queries=total_queries,
            unique_queries=unique_queries,
            avg_execution_time_ms=avg_execution_time,
            top_queries=analytics_items,
        )


class IndexJobService:
    """Service for managing index jobs."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.index_manager = WhooshIndexManager()

    async def list_jobs(
        self, skip: int = 0, limit: int = 50, source_id: Optional[str] = None
    ) -> tuple[list[IndexJob], int]:
        """List index jobs with pagination."""
        stmt = select(IndexJob).options(selectinload(IndexJob.source))
        if source_id:
            stmt = stmt.where(IndexJob.source_id == source_id)
        stmt = stmt.order_by(IndexJob.created_at.desc()).offset(skip).limit(limit)
        result = await self.db.execute(stmt)
        jobs = result.scalars().all()

        count_stmt = select(func.count()).select_from(IndexJob)
        if source_id:
            count_stmt = count_stmt.where(IndexJob.source_id == source_id)
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        return list(jobs), total

    async def get_job(self, job_id: str) -> IndexJob:
        """Get an index job by ID."""
        stmt = (
            select(IndexJob)
            .where(IndexJob.id == job_id)
            .options(selectinload(IndexJob.source))
        )
        result = await self.db.execute(stmt)
        job = result.scalar_one_or_none()
        if not job:
            raise NotFoundError(f"Index job not found: {job_id}")
        return job

    async def create_job(self, job_data: IndexJobCreate) -> IndexJob:
        """Create a new index job."""
        source_service = DocumentSourceService(self.db)
        await source_service.get_source(job_data.source_id)

        now = utc_now()
        job = IndexJob(
            id=generate_id("job"),
            entity_id=job_data.source_id,
            phase="pending",
            progress=0.0,
            started_at=now,
            **job_data.model_dump(),
            created_at=now,
            updated_at=now,
        )
        self.db.add(job)
        await self.db.commit()
        await self.db.refresh(job)

        asyncio.create_task(self._execute_job(job.id))

        return job

    async def _execute_job(self, job_id: str) -> None:
        """Execute an indexing job asynchronously."""
        try:
            async with processing_context() as ctx:
                ctx.emit_event("index_job.started", {"job_id": job_id})

                job = await self.get_job(job_id)
                job.phase = "running"
                job.progress = 0.0
                await self.db.commit()

                if job.job_type == "delete":
                    await self._execute_delete_job(job)
                else:
                    await self._execute_index_job(job)

                job.phase = "completed"
                job.progress = 1.0
                job.completed_at = utc_now()
                await self.db.commit()

                ctx.emit_event(
                    "index_job.completed",
                    {
                        "job_id": job_id,
                        "indexed": job.documents_indexed,
                        "failed": job.documents_failed,
                    },
                )
        except Exception as e:
            logger.exception(f"Index job {job_id} failed")
            try:
                job = await self.get_job(job_id)
                job.phase = "failed"
                job.error_detail = str(e)
                job.error_details = {"error": str(e), "type": type(e).__name__}
                job.completed_at = utc_now()
                await self.db.commit()
            except Exception:
                logger.exception(f"Failed to update job {job_id} status")

    async def _execute_index_job(self, job: IndexJob) -> None:
        """Execute a full or incremental index job."""
        source = await DocumentSourceService(self.db).get_source(job.source_id)
        aggregator = SourceAggregator(self.db)

        if job.job_type == "full":
            await self.db.execute(delete(Document).where(Document.source_id == source.id))
            await self.index_manager.delete_by_source(source.id)

        try:
            documents = await aggregator.fetch_documents(source)
            total = len(documents)

            for i, doc_data in enumerate(documents):
                try:
                    checksum = sha256_hash(doc_data["content"])
                    now = utc_now()

                    existing = await self.db.execute(
                        select(Document).where(
                            Document.source_id == source.id,
                            Document.source_document_id == doc_data["source_document_id"],
                        )
                    )
                    existing_doc = existing.scalar_one_or_none()

                    if existing_doc and existing_doc.checksum == checksum:
                        continue

                    if existing_doc:
                        existing_doc.title = doc_data["title"]
                        existing_doc.content = doc_data["content"]
                        existing_doc.summary = doc_data.get("summary")
                        existing_doc.url = doc_data.get("url")
                        existing_doc.path = doc_data.get("path")
                        existing_doc.mime_type = doc_data.get("mime_type", "text/markdown")
                        existing_doc.language = doc_data.get("language", "en")
                        existing_doc.tags = doc_data.get("tags", [])
                        existing_doc.metadata = doc_data.get("metadata", {})
                        existing_doc.checksum = checksum
                        existing_doc.indexed_at = now
                        existing_doc.updated_at = now
                        doc = existing_doc
                    else:
                        doc = Document(
                            id=generate_id("doc"),
                            source_id=source.id,
                            source_document_id=doc_data["source_document_id"],
                            title=doc_data["title"],
                            content=doc_data["content"],
                            summary=doc_data.get("summary"),
                            url=doc_data.get("url"),
                            path=doc_data.get("path"),
                            mime_type=doc_data.get("mime_type", "text/markdown"),
                            language=doc_data.get("language", "en"),
                            tags=doc_data.get("tags", []),
                            metadata=doc_data.get("metadata", {}),
                            checksum=checksum,
                            indexed_at=now,
                            created_at=now,
                            updated_at=now,
                        )
                        self.db.add(doc)

                    await self.index_manager.add_document(doc, source.name)
                    job.documents_indexed += 1
                except Exception as e:
                    logger.warning(f"Failed to index document {doc_data.get('source_document_id')}: {e}")
                    job.documents_failed += 1

                job.progress = min((i + 1) / total, 0.99) if total > 0 else 0.99
                if i % 10 == 0:
                    await self.db.commit()

            await self.db.commit()
            source.last_sync_at = utc_now()
            await self.db.commit()

        except Exception as e:
            logger.exception(f"Error fetching documents from source {source.id}")
            raise

    async def _execute_delete_job(self, job: IndexJob) -> None:
        """Execute a delete job."""
        source = await DocumentSourceService(self.db).get_source(job.source_id)

        docs = await self.db.execute(
            select(Document.id).where(Document.source_id == source.id)
        )
        doc_ids = [row[0] for row in docs.all()]
        job.documents_deleted = len(doc_ids)

        await self.db.execute(delete(Document).where(Document.source_id == source.id))
        await self.index_manager.delete_by_source(source.id)
        await self.db.commit()


class SourceAggregator:
    """Aggregates documents from different sources."""

    def __init__(self, db: AsyncSession):
        self.db = db

    async def fetch_documents(self, source: DocumentSource) -> list[dict[str, Any]]:
        """Fetch documents from a source based on its type."""
        if source.source_type == "local":
            return await self._fetch_local(source)
        elif source.source_type == "url":
            return await self._fetch_url(source)
        elif source.source_type == "confluence":
            return await self._fetch_confluence(source)
        elif source.source_type == "github":
            return await self._fetch_github(source)
        elif source.source_type == "gitlab":
            return await self._fetch_gitlab(source)
        else:
            raise ValidationError(f"Unsupported source type: {source.source_type}")

    async def _fetch_local(self, source: DocumentSource) -> list[dict[str, Any]]:
        """Fetch documents from local filesystem."""
        path = source.config.get("path")
        if not path:
            raise ValidationError("Local source requires 'path' in config")

        base_path = Path(path)
        if not base_path.exists():
            raise ValidationError(f"Path does not exist: {path}")

        documents: list[dict[str, Any]] = []
        extensions = source.config.get("extensions", [".md", ".rst", ".txt"])

        for file_path in base_path.rglob("*"):
            if file_path.is_file() and file_path.suffix in extensions:
                try:
                    content = file_path.read_text(encoding="utf-8")
                    rel_path = file_path.relative_to(base_path)

                    documents.append(
                        {
                            "source_document_id": str(rel_path),
                            "title": file_path.stem,
                            "content": content,
                            "path": str(rel_path),
                            "url": f"file://{file_path}",
                            "mime_type": self._get_mime_type(file_path.suffix),
                            "language": source.config.get("language", "en"),
                            "tags": source.config.get("default_tags", []),
                            "metadata": {"file_size": file_path.stat().st_size},
                        }
                    )
                except Exception as e:
                    logger.warning(f"Failed to read file {file_path}: {e}")

        return documents

    async def _fetch_url(self, source: DocumentSource) -> list[dict[str, Any]]:
        """Fetch documents from URLs."""
        urls = source.config.get("urls", [])
        if not urls:
            raise ValidationError("URL source requires 'urls' in config")

        import httpx

        documents: list[dict[str, Any]] = []
        async with httpx.AsyncClient() as client:
            for url in urls:
                try:
                    response = await client.get(url, timeout=30)
                    response.raise_for_status()

                    documents.append(
                        {
                            "source_document_id": url,
                            "title": url.split("/")[-1] or url,
                            "content": response.text,
                            "url": url,
                            "mime_type": response.headers.get(
                                "content-type", "text/plain"
                            ).split(";")[0],
                            "language": source.config.get("language", "en"),
                            "tags": source.config.get("default_tags", []),
                            "metadata": {"status_code": response.status_code},
                        }
                    )
                except Exception as e:
                    logger.warning(f"Failed to fetch URL {url}: {e}")

        return documents

    async def _fetch_confluence(self, source: DocumentSource) -> list[dict[str, Any]]:
        """Fetch documents from Confluence."""
        base_url = source.config.get("base_url")
        space_key = source.config.get("space_key")
        username = source.config.get("username")
        api_token = source.config.get("api_token")

        if not all([base_url, space_key]):
            raise ValidationError(
                "Confluence source requires 'base_url' and 'space_key' in config"
            )

        import httpx

        documents: list[dict[str, Any]] = []
        auth = (username, api_token) if username and api_token else None

        async with httpx.AsyncClient() as client:
            try:
                url = f"{base_url}/rest/api/content"
                params = {
                    "spaceKey": space_key,
                    "type": "page",
                    "limit": 100,
                    "expand": "body.storage,metadata",
                }

                while url:
                    response = await client.get(url, params=params, auth=auth, timeout=30)
                    response.raise_for_status()
                    data = response.json()

                    for page in data.get("results", []):
                        try:
                            content = page["body"]["storage"]["value"]
                            documents.append(
                                {
                                    "source_document_id": page["id"],
                                    "title": page["title"],
                                    "content": content,
                                    "url": f"{base_url}{page['_links']['webui']}",
                                    "mime_type": "text/html",
                                    "language": page.get("metadata", {})
                                    .get("language", {})
                                    .get("value", "en"),
                                    "tags": source.config.get("default_tags", []),
                                    "metadata": {
                                        "version": page.get("version", {}).get("number"),
                                        "author": page.get("history", {})
                                        .get("createdBy", {})
                                        .get("username"),
                                    },
                                }
                            )
                        except Exception as e:
                            logger.warning(f"Failed to process Confluence page {page.get('id')}: {e}")

                    next_link = data.get("_links", {}).get("next")
                    url = f"{base_url}{next_link}" if next_link else None
                    params = {}

            except Exception as e:
                logger.warning(f"Failed to fetch Confluence content: {e}")

        return documents

    async def _fetch_github(self, source: DocumentSource) -> list[dict[str, Any]]:
        """Fetch documents from GitHub."""
        repo = source.config.get("repo")
        branch = source.config.get("branch", "main")
        path = source.config.get("path", "")
        token = source.config.get("token")

        if not repo:
            raise ValidationError("GitHub source requires 'repo' in config")

        import httpx

        documents: list[dict[str, Any]] = []
        headers = {"Authorization": f"token {token}"} if token else {}

        async with httpx.AsyncClient() as client:
            try:
                url = f"https://api.github.com/repos/{repo}/contents/{path}"
                params = {"ref": branch}

                response = await client.get(url, params=params, headers=headers, timeout=30)
                response.raise_for_status()
                contents = response.json()

                if not isinstance(contents, list):
                    contents = [contents]

                for item in contents:
                    if item["type"] == "file" and item["name"].endswith(
                        (".md", ".rst", ".txt")
                    ):
                        try:
                            raw_url = item["download_url"]
                            raw_response = await client.get(
                                raw_url, headers=headers, timeout=30
                            )
                            raw_response.raise_for_status()

                            documents.append(
                                {
                                    "source_document_id": item["path"],
                                    "title": item["name"],
                                    "content": raw_response.text,
                                    "url": item["html_url"],
                                    "path": item["path"],
                                    "mime_type": self._get_mime_type(
                                        "." + item["name"].split(".")[-1]
                                    ),
                                    "language": source.config.get("language", "en"),
                                    "tags": source.config.get("default_tags", []),
                                    "metadata": {
                                        "sha": item["sha"],
                                        "size": item["size"],
                                    },
                                }
                            )
                        except Exception as e:
                            logger.warning(f"Failed to fetch GitHub file {item.get('path')}: {e}")

            except Exception as e:
                logger.warning(f"Failed to fetch GitHub content: {e}")

        return documents

    async def _fetch_gitlab(self, source: DocumentSource) -> list[dict[str, Any]]:
        """Fetch documents from GitLab."""
        project_id = source.config.get("project_id")
        branch = source.config.get("branch", "main")
        path = source.config.get("path", "")
        token = source.config.get("token")
        base_url = source.config.get("base_url", "https://gitlab.com")

        if not project_id:
            raise ValidationError("GitLab source requires 'project_id' in config")

        import httpx

        documents: list[dict[str, Any]] = []
        headers = {"PRIVATE-TOKEN": token} if token else {}

        async with httpx.AsyncClient() as client:
            try:
                url = f"{base_url}/api/v4/projects/{project_id}/repository/tree"
                params = {"ref": branch, "path": path, "per_page": 100}

                response = await client.get(url, params=params, headers=headers, timeout=30)
                response.raise_for_status()
                items = response.json()

                for item in items:
                    if item["type"] == "blob" and item["name"].endswith(
                        (".md", ".rst", ".txt")
                    ):
                        try:
                            raw_url = f"{base_url}/api/v4/projects/{project_id}/repository/files/{item['path']}/raw"
                            raw_params = {"ref": branch}
                            raw_response = await client.get(
                                raw_url, params=raw_params, headers=headers, timeout=30
                            )
                            raw_response.raise_for_status()

                            documents.append(
                                {
                                    "source_document_id": item["path"],
                                    "title": item["name"],
                                    "content": raw_response.text,
                                    "url": f"{base_url}/{project_id}/-/blob/{branch}/{item['path']}",
                                    "path": item["path"],
                                    "mime_type": self._get_mime_type(
                                        "." + item["name"].split(".")[-1]
                                    ),
                                    "language": source.config.get("language", "en"),
                                    "tags": source.config.get("default_tags", []),
                                    "metadata": {"id": item["id"]},
                                }
                            )
                        except Exception as e:
                            logger.warning(f"Failed to fetch GitLab file {item.get('path')}: {e}")

            except Exception as e:
                logger.warning(f"Failed to fetch GitLab content: {e}")

        return documents

    def _get_mime_type(self, extension: str) -> str:
        """Get MIME type based on file extension."""
        mime_types = {
            ".md": "text/markdown",
            ".rst": "text/x-rst",
            ".txt": "text/plain",
            ".html": "text/html",
            ".htm": "text/html",
            ".json": "application/json",
            ".yaml": "application/yaml",
            ".yml": "application/yaml",
        }
        return mime_types.get(extension.lower(), "text/plain")


class DocIndexService:
    """Main service for the doc_index module."""

    def __init__(self, db: AsyncSession):
        self.db = db
        self.source_service = DocumentSourceService(db)
        self.document_service = DocumentService(db)
        self.search_service = SearchService(db)
        self.job_service = IndexJobService(db)

    async def sync_source(self, request: SyncRequest, user: User) -> SyncResponse:
        """Sync a document source."""
        source = await self.source_service.get_source(request.source_id)

        if not source.enabled:
            raise ValidationError(f"Source {source.id} is disabled")

        job = await self.job_service.create_job(
            IndexJobCreate(source_id=request.source_id, job_type=request.sync_type)
        )

        return SyncResponse(
            job_id=job.id,
            status="started",
            message=f"Sync started for source {source.name}",
        )
