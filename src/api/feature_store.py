from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from datetime import datetime
from src.core import ApiResponse, get_trace_id
from src.domain import (
    FeatureEntity,
    FeatureDefinition,
    FeatureLookupRequest,
    FeatureStoreRequest,
    HistoricalLookupRequest,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/feature-store", tags=["Feature Store"])


@router.post("/entities", response_model=ApiResponse)
async def register_entity(
    entity: FeatureEntity,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.register_entity(entity, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/entities", response_model=ApiResponse)
async def list_entities(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.list_entities(trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/features", response_model=ApiResponse)
async def register_feature(
    feature: FeatureDefinition,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.register_feature(feature, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/features", response_model=ApiResponse)
async def list_features(
    entity: Optional[str] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.list_features(entity, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/features/store", response_model=ApiResponse)
async def store_features(
    request: FeatureStoreRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.store_features(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/features/lookup", response_model=ApiResponse)
async def lookup_features(
    request: FeatureLookupRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.lookup_features(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/features/historical", response_model=ApiResponse)
async def historical_lookup(
    request: HistoricalLookupRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.historical_lookup(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/consistency/check", response_model=ApiResponse)
async def check_consistency(
    entity_id: str,
    feature_names: List[str],
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.check_consistency(
        entity_id, feature_names, trace_id or get_trace_id()
    )
    return ApiResponse.success(result)


@router.get("/stats/{feature_name}", response_model=ApiResponse)
async def get_feature_stats(
    feature_name: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.get_feature_stats(feature_name, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/cleanup", response_model=ApiResponse)
async def cleanup_expired(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    count = await container.feature_store.cleanup_expired(trace_id or get_trace_id())
    return ApiResponse.success({"cleaned_count": count})


@router.post("/features/batch-store", response_model=ApiResponse)
async def batch_store_features(
    requests: List[FeatureStoreRequest],
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.batch_store_features(requests, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/features/batch-lookup", response_model=ApiResponse)
async def batch_lookup_features(
    requests: List[FeatureLookupRequest],
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.feature_store.batch_lookup_features(requests, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/batch/stats", response_model=ApiResponse)
async def get_batch_stats(
    container: DIContainer = Depends(get_container),
):
    result = container.feature_store.get_batch_stats()
    return ApiResponse.success(result)
