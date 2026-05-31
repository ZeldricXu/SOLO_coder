from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from pydantic import BaseModel

from src.common.models import APIResponse
from src.document_index.models import Document, DocumentSource, SearchQuery, DocumentChunk
from src.document_index.indexer import DocumentIndexer
from src.document_index.sources import SourceManager

router = APIRouter(prefix="/documents", tags=["Document Index"])

_indexer: Optional[DocumentIndexer] = None
_source_manager: Optional[SourceManager] = None


def get_indexer() -> DocumentIndexer:
    global _indexer
    if _indexer is None:
        _indexer = DocumentIndexer()
    return _indexer


def get_source_manager() -> SourceManager:
    global _source_manager
    if _source_manager is None:
        _source_manager = SourceManager()
    return _source_manager


class IndexDocumentRequest(BaseModel):
    title: str
    content: str
    source: DocumentSource = DocumentSource.CUSTOM
    source_id: Optional[str] = None
    tags: List[str] = []
    categories: List[str] = []
    acl: List[str] = []
    metadata: Dict[str, Any] = {}


class SyncRequest(BaseModel):
    sources: List[DocumentSource] | None = None


@router.get("/stats")
async def get_index_stats(
    indexer: DocumentIndexer = Depends(get_indexer),
) -> APIResponse:
    return APIResponse(data=indexer.get_stats())


@router.post("/index", status_code=201)
async def index_document(
    request: IndexDocumentRequest,
    indexer: DocumentIndexer = Depends(get_indexer),
) -> APIResponse:
    doc = Document(
        title=request.title,
        content=request.content,
        source=request.source,
        source_id=request.source_id,
        tags=request.tags,
        categories=request.categories,
        acl=request.acl,
        metadata=request.metadata,
    )
    doc_id = await indexer.index_document(doc)
    return APIResponse(code=201, data={"doc_id": doc_id})


@router.post("/index/batch", status_code=201)
async def index_documents_batch(
    documents: List[IndexDocumentRequest],
    indexer: DocumentIndexer = Depends(get_indexer),
) -> APIResponse:
    docs = [Document(
        title=d.title,
        content=d.content,
        source=d.source,
        source_id=d.source_id,
        tags=d.tags,
        categories=d.categories,
        acl=d.acl,
        metadata=d.metadata,
    ) for d in documents]
    doc_ids = await indexer.index_documents(docs)
    return APIResponse(code=201, data={"indexed_count": len(doc_ids), "doc_ids": doc_ids})


@router.post("/index/upload")
async def upload_document(
    file: UploadFile = File(...),
    indexer: DocumentIndexer = Depends(get_indexer),
) -> APIResponse:
    content = await file.read()
    text = content.decode("utf-8", errors="ignore")
    doc = Document(
        title=file.filename or "uploaded_document",
        content=text,
        source=DocumentSource.LOCAL_FILE,
        source_id=file.filename,
        mime_type=file.content_type or "text/plain",
    )
    doc_id = await indexer.index_document(doc)
    return APIResponse(data={"doc_id": doc_id, "filename": file.filename})


@router.delete("/{doc_id}")
async def delete_document(
    doc_id: str,
    indexer: DocumentIndexer = Depends(get_indexer),
) -> APIResponse:
    deleted = await indexer.delete_document(doc_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Document not found")
    return APIResponse(data={"doc_id": doc_id, "deleted": True})


@router.post("/search")
async def search_documents(
    query: SearchQuery,
    indexer: DocumentIndexer = Depends(get_indexer),
) -> APIResponse:
    results = await indexer.search(query)
    return APIResponse(data=results.model_dump())


@router.post("/sync")
async def sync_documents(
    request: SyncRequest,
    source_manager: SourceManager = Depends(get_source_manager),
    indexer: DocumentIndexer = Depends(get_indexer),
) -> APIResponse:
    docs = await source_manager.sync_all()
    doc_ids = await indexer.index_documents(docs)
    return APIResponse(data={"synced_count": len(doc_ids), "doc_ids": doc_ids})


@router.get("/sources")
async def list_sources(
    source_manager: SourceManager = Depends(get_source_manager),
) -> APIResponse:
    return APIResponse(data={
        "available_sources": [s.value for s in source_manager._sources.keys()],
    })
