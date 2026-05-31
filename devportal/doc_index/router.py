"""Router for the doc_index module."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from devportal.core.database import get_db
from devportal.core.dependencies import PermissionChecker, get_current_user
from devportal.core.models import User
from devportal.core.schemas import APIResponse, PaginatedResponse
from devportal.doc_index.schemas import (
    DocumentCreate,
    DocumentPermissionCreate,
    DocumentResponse,
    DocumentSourceCreate,
    DocumentSourceResponse,
    DocumentSourceUpdate,
    DocumentUpdate,
    IndexJobResponse,
    PaginatedDocuments,
    PaginatedDocumentSources,
    PaginatedIndexJobs,
    PaginatedPermissions,
    SearchAnalyticsQuery,
    SearchAnalyticsResponse,
    SearchRequest,
    SearchResponse,
    SyncRequest,
    SyncResponse,
)
from devportal.doc_index.services import (
    DocIndexService,
    DocumentService,
    DocumentSourceService,
    IndexJobService,
    SearchService,
)

router = APIRouter(prefix="/doc-index", tags=["doc_index"])


@router.get("/sources", response_model=APIResponse[PaginatedDocumentSources])
async def list_sources(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    enabled_only: bool = Query(False),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:read"])),
):
    """List document sources."""
    service = DocumentSourceService(db)
    sources, total = await service.list_sources(skip, limit, enabled_only)
    return APIResponse(
        code=200,
        data=PaginatedResponse(
            items=[
                DocumentSourceResponse(
                    **s.__dict__, documents_count=len(s.documents)
                )
                for s in sources
            ],
            total=total,
            skip=skip,
            limit=limit,
        ),
    )


@router.get("/sources/{source_id}", response_model=APIResponse[DocumentSourceResponse])
async def get_source(
    source_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:read"])),
):
    """Get a document source by ID."""
    service = DocumentSourceService(db)
    source = await service.get_source(source_id)
    return APIResponse(
        code=200,
        data=DocumentSourceResponse(
            **source.__dict__, documents_count=len(source.documents)
        ),
    )


@router.post("/sources", response_model=APIResponse[DocumentSourceResponse], status_code=201)
async def create_source(
    source_data: DocumentSourceCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Create a new document source."""
    service = DocumentSourceService(db)
    source = await service.create_source(source_data)
    return APIResponse(
        code=201,
        data=DocumentSourceResponse(
            **source.__dict__, documents_count=len(source.documents)
        ),
    )


@router.put("/sources/{source_id}", response_model=APIResponse[DocumentSourceResponse])
async def update_source(
    source_id: str,
    source_data: DocumentSourceUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Update a document source."""
    service = DocumentSourceService(db)
    source = await service.update_source(source_id, source_data)
    return APIResponse(
        code=200,
        data=DocumentSourceResponse(
            **source.__dict__, documents_count=len(source.documents)
        ),
    )


@router.delete("/sources/{source_id}", response_model=APIResponse[dict])
async def delete_source(
    source_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Delete a document source."""
    service = DocumentSourceService(db)
    await service.delete_source(source_id)
    return APIResponse(code=200, data={"message": "Source deleted successfully"})


@router.get("/documents", response_model=APIResponse[PaginatedDocuments])
async def list_documents(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    source_id: str | None = Query(None),
    tags: list[str] | None = Query(None),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """List documents with permission filtering."""
    service = DocumentService(db)
    documents, total = await service.list_documents(user, skip, limit, source_id, tags)
    return APIResponse(
        code=200,
        data=PaginatedResponse(
            items=[
                DocumentResponse(
                    **d.__dict__,
                    source_name=d.source.name if d.source else None,
                )
                for d in documents
            ],
            total=total,
            skip=skip,
            limit=limit,
        ),
    )


@router.get("/documents/{doc_id}", response_model=APIResponse[DocumentResponse])
async def get_document(
    doc_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Get a document by ID."""
    service = DocumentService(db)
    document = await service.get_document(doc_id, user)
    return APIResponse(
        code=200,
        data=DocumentResponse(
            **document.__dict__,
            source_name=document.source.name if document.source else None,
        ),
    )


@router.post("/documents", response_model=APIResponse[DocumentResponse], status_code=201)
async def create_document(
    doc_data: DocumentCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Create a new document."""
    service = DocumentService(db)
    document = await service.create_document(doc_data, user)
    return APIResponse(
        code=201,
        data=DocumentResponse(
            **document.__dict__,
            source_name=document.source.name if document.source else None,
        ),
    )


@router.put("/documents/{doc_id}", response_model=APIResponse[DocumentResponse])
async def update_document(
    doc_id: str,
    doc_data: DocumentUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Update a document."""
    service = DocumentService(db)
    document = await service.update_document(doc_id, doc_data, user)
    return APIResponse(
        code=200,
        data=DocumentResponse(
            **document.__dict__,
            source_name=document.source.name if document.source else None,
        ),
    )


@router.delete("/documents/{doc_id}", response_model=APIResponse[dict])
async def delete_document(
    doc_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Delete a document."""
    service = DocumentService(db)
    await service.delete_document(doc_id, user)
    return APIResponse(code=200, data={"message": "Document deleted successfully"})


@router.post("/documents/permissions", response_model=APIResponse[dict], status_code=201)
async def add_permission(
    perm_data: DocumentPermissionCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Add a permission to a document."""
    service = DocumentService(db)
    await service.add_permission(perm_data, user)
    return APIResponse(code=201, data={"message": "Permission added successfully"})


@router.delete("/documents/permissions/{perm_id}", response_model=APIResponse[dict])
async def remove_permission(
    perm_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Remove a permission from a document."""
    service = DocumentService(db)
    await service.remove_permission(perm_id, user)
    return APIResponse(code=200, data={"message": "Permission removed successfully"})


@router.post("/search", response_model=APIResponse[SearchResponse])
async def search_documents(
    request: SearchRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Search documents with full-text search and permission filtering."""
    service = SearchService(db)
    result = await service.search(request, user)
    return APIResponse(code=200, data=result)


@router.get("/search/analytics", response_model=APIResponse[SearchAnalyticsResponse])
async def get_search_analytics(
    date_from: str | None = Query(None),
    date_to: str | None = Query(None),
    user_id: str | None = Query(None),
    limit: int = Query(100, ge=1, le=1000),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:admin"])),
):
    """Get search analytics."""
    from datetime import datetime

    query = SearchAnalyticsQuery(
        date_from=datetime.fromisoformat(date_from) if date_from else None,
        date_to=datetime.fromisoformat(date_to) if date_to else None,
        user_id=user_id,
        limit=limit,
    )
    service = SearchService(db)
    result = await service.get_analytics(query)
    return APIResponse(code=200, data=result)


@router.post("/sync", response_model=APIResponse[SyncResponse])
async def sync_source(
    request: SyncRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:manage"])),
):
    """Sync a document source."""
    service = DocIndexService(db)
    result = await service.sync_source(request, user)
    return APIResponse(code=200, data=result)


@router.get("/jobs", response_model=APIResponse[PaginatedIndexJobs])
async def list_jobs(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    source_id: str | None = Query(None),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:read"])),
):
    """List index jobs."""
    service = IndexJobService(db)
    jobs, total = await service.list_jobs(skip, limit, source_id)
    return APIResponse(
        code=200,
        data=PaginatedResponse(
            items=[IndexJobResponse(**j.__dict__) for j in jobs],
            total=total,
            skip=skip,
            limit=limit,
        ),
    )


@router.get("/jobs/{job_id}", response_model=APIResponse[IndexJobResponse])
async def get_job(
    job_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["doc_index:read"])),
):
    """Get an index job by ID."""
    service = IndexJobService(db)
    job = await service.get_job(job_id)
    return APIResponse(code=200, data=IndexJobResponse(**job.__dict__))
