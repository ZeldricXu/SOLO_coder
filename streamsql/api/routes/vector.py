from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import (
    VectorIndexRequest,
    VectorIndexResponse,
    VectorSearchRequest,
    VectorSearchResponse,
)
from streamsql.services.vector_service import VectorService
from streamsql.api.dependencies import get_vector_service

router = APIRouter(prefix="/vector", tags=["vector"])


@router.post("/build-index", response_model=VectorIndexResponse)
def build_index(
    request: VectorIndexRequest,
    service: VectorService = Depends(get_vector_service),
):
    try:
        result = service.build_index(
            texts=request.texts,
            index_type=request.index_type,
            embedding_model=request.embedding_model,
        )
        return VectorIndexResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/search", response_model=VectorSearchResponse)
def search(
    request: VectorSearchRequest,
    service: VectorService = Depends(get_vector_service),
):
    try:
        result = service.search(query_text=request.query_text, top_k=request.top_k)
        return VectorSearchResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/add")
def add_vectors(
    texts: list[str],
    service: VectorService = Depends(get_vector_service),
):
    try:
        result = service.add_vectors(texts)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/embedding")
def get_embedding(
    text: str,
    model_type: str = "mock",
    service: VectorService = Depends(get_vector_service),
):
    try:
        result = service.get_embedding(text, model_type)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/models")
def get_available_models(
    service: VectorService = Depends(get_vector_service),
):
    return {"code": 200, "data": service.get_available_models()}


@router.get("/index-types")
def get_available_index_types(
    service: VectorService = Depends(get_vector_service),
):
    return {"code": 200, "data": service.get_available_index_types()}
