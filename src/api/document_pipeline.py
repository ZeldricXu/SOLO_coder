from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from datetime import datetime
from src.core import (
    ApiResponse,
    get_trace_id,
    generate_id,
)
from src.domain import (
    PipelineRequest,
    PipelineResult,
    ParseRequest,
    ChunkRequest,
    VectorizeRequest,
    DocumentFormat,
    ChunkingStrategy,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/document-pipeline", tags=["Document Pipeline"])


@router.post("/parse", response_model=ApiResponse)
async def parse_document(
    request: ParseRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.document_pipeline.parse_document(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/chunk", response_model=ApiResponse)
async def chunk_document(
    request: ChunkRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.document_pipeline.chunk_document(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/vectorize", response_model=ApiResponse)
async def vectorize_chunks(
    request: VectorizeRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.document_pipeline.vectorize_chunks(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/process", response_model=ApiResponse)
async def process_document(
    request: PipelineRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.document_pipeline.run_pipeline(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/runs/{run_id}", response_model=ApiResponse)
async def get_run_status(
    run_id: str,
    container: DIContainer = Depends(get_container),
):
    result = container.document_pipeline.get_run_status(run_id)
    if not result:
        raise HTTPException(status_code=404, detail="Run not found")
    return ApiResponse.success(result)


@router.get("/formats", response_model=ApiResponse)
async def list_supported_formats():
    return ApiResponse.success([f.value for f in DocumentFormat])


@router.get("/chunking-strategies", response_model=ApiResponse)
async def list_chunking_strategies():
    return ApiResponse.success([s.value for s in ChunkingStrategy])


@router.post("/cache/invalidate/{document_id}", response_model=ApiResponse)
async def invalidate_cache(
    document_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.document_pipeline.invalidate_cache(document_id)
    return ApiResponse.success(result)


@router.get("/cache/stats", response_model=ApiResponse)
async def get_cache_stats(
    container: DIContainer = Depends(get_container),
):
    result = await container.document_pipeline.get_cache_stats()
    return ApiResponse.success(result)


@router.post("/cache/warm", response_model=ApiResponse)
async def warm_cache(
    entries: list,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.document_pipeline.warm_cache([tuple(e) for e in entries])
    return ApiResponse.success(result)
