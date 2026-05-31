from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

from src.service.vector_service import VectorService

router = APIRouter()
_vector_service: Optional[VectorService] = None


def _get_service() -> VectorService:
    global _vector_service
    if _vector_service is None:
        from src.infrastructure.config.settings import get_settings
        settings = get_settings()
        _vector_service = VectorService(settings.vector)
    return _vector_service


class BuildIndexRequest(BaseModel):
    documents: List[Dict[str, Any]]


class AddDocumentRequest(BaseModel):
    doc_id: str
    vector: List[float]
    metadata: Optional[Dict[str, Any]] = None


class SearchRequest(BaseModel):
    query_vector: List[float]
    top_k: int = 10
    filters: Optional[Dict[str, Any]] = None
    min_score: Optional[float] = None


class BatchSearchRequest(BaseModel):
    query_vectors: List[List[float]]
    top_k: int = 10


class HybridSearchRequest(BaseModel):
    query_vector: List[float]
    keyword_results: List[str]
    top_k: int = 10
    vector_weight: float = 0.7
    keyword_weight: float = 0.3


class SaveIndexRequest(BaseModel):
    path: str


class LoadIndexRequest(BaseModel):
    path: str


@router.post("/build")
async def build_index(request: BuildIndexRequest):
    service = _get_service()
    return service.build_index(request.documents)


@router.post("/add")
async def add_document(request: AddDocumentRequest):
    service = _get_service()
    service.add_document(request.doc_id, request.vector, request.metadata)
    return {"status": "added", "doc_id": request.doc_id}


@router.delete("/remove/{doc_id}")
async def remove_document(doc_id: str):
    service = _get_service()
    removed = service.remove_document(doc_id)
    return {"status": "removed" if removed else "not_found", "doc_id": doc_id}


@router.post("/search")
async def search(request: SearchRequest):
    service = _get_service()
    return {"results": service.search(request.query_vector, request.top_k, request.filters, request.min_score)}


@router.post("/search/batch")
async def batch_search(request: BatchSearchRequest):
    service = _get_service()
    return {"results": service.batch_search(request.query_vectors, request.top_k)}


@router.post("/search/hybrid")
async def hybrid_search(request: HybridSearchRequest):
    service = _get_service()
    return {"results": service.hybrid_search(request.query_vector, request.keyword_results, request.top_k, request.vector_weight, request.keyword_weight)}


@router.get("/stats")
async def get_stats():
    service = _get_service()
    return service.get_index_stats()


@router.post("/save")
async def save_index(request: SaveIndexRequest):
    service = _get_service()
    service.save_index(request.path)
    return {"status": "saved", "path": request.path}


@router.post("/load")
async def load_index(request: LoadIndexRequest):
    service = _get_service()
    service.load_index(request.path)
    return {"status": "loaded", "path": request.path}


@router.get("/optimize/suggest")
async def suggest_optimization(
    total_vectors: Optional[int] = None,
    search_qps: float = 0.0,
    memory_limit_mb: Optional[float] = None,
    recall_target: float = 0.95,
):
    service = _get_service()
    return {"suggestions": service.suggest_optimization(total_vectors, search_qps, memory_limit_mb, recall_target)}
