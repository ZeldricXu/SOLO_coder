from fastapi import APIRouter, Depends, Query
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional

from app.database import get_db
from app.schemas import (
    FeatureCreate,
    FeatureUpdate,
    FeatureResponse,
    FeatureVersionCreate,
    FeatureVersionResponse,
    BaseResponse,
    PaginatedResponse,
    FeatureDataBatch,
)
from app.feature_store.service import FeatureStoreService
from app.logging import LogContext

router = APIRouter(prefix="/api/v1/features", tags=["Feature Store"])


@router.post("", response_model=BaseResponse[FeatureResponse])
async def create_feature(
    feature_in: FeatureCreate,
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    feature = await service.create_feature(feature_in)
    return BaseResponse(
        code=201,
        data=feature,
        request_id=LogContext.get_request_id(),
        message="Feature created successfully",
    )


@router.get("/{feature_id}", response_model=BaseResponse[FeatureResponse])
async def get_feature(
    feature_id: UUID,
    include_versions: bool = Query(True, description="Include feature versions"),
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    feature = await service.get_feature(feature_id, include_versions=include_versions)
    return BaseResponse(data=feature, request_id=LogContext.get_request_id())


@router.get("", response_model=BaseResponse[PaginatedResponse[FeatureResponse]])
async def list_features(
    namespace: Optional[str] = Query(None, description="Filter by namespace"),
    entity_type: Optional[str] = Query(None, description="Filter by entity type"),
    name_pattern: Optional[str] = Query(None, description="Filter by name pattern"),
    is_online: Optional[bool] = Query(None, description="Filter by online status"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    skip = (page - 1) * page_size
    features, total = await service.list_features(
        namespace=namespace,
        entity_type=entity_type,
        name_pattern=name_pattern,
        is_online=is_online,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=features,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.put("/{feature_id}", response_model=BaseResponse[FeatureResponse])
async def update_feature(
    feature_id: UUID,
    feature_in: FeatureUpdate,
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    feature = await service.update_feature(feature_id, feature_in)
    return BaseResponse(data=feature, request_id=LogContext.get_request_id())


@router.delete("/{feature_id}", response_model=BaseResponse)
async def delete_feature(
    feature_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    await service.delete_feature(feature_id)
    return BaseResponse(
        message="Feature deleted successfully",
        request_id=LogContext.get_request_id(),
    )


@router.post("/{feature_id}/versions", response_model=BaseResponse[FeatureVersionResponse])
async def create_version(
    feature_id: UUID,
    version_in: FeatureVersionCreate,
    db: AsyncSession = Depends(get_db),
):
    version_in.feature_id = feature_id
    service = FeatureStoreService(db)
    version = await service.create_version(version_in)
    return BaseResponse(
        code=201,
        data=version,
        request_id=LogContext.get_request_id(),
        message="Feature version created successfully",
    )


@router.post("/online", response_model=BaseResponse[dict])
async def get_online_features(
    batch_in: FeatureDataBatch,
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    results = await service.batch_get_features(batch_in)
    return BaseResponse(data=results, request_id=LogContext.get_request_id())


@router.post("/offline", response_model=BaseResponse[list])
async def get_offline_features(
    batch_in: FeatureDataBatch,
    start_time: Optional[int] = Query(None, description="Start timestamp"),
    end_time: Optional[int] = Query(None, description="End timestamp"),
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    results = await service.get_offline_features(
        entity_ids=batch_in.entity_ids,
        feature_names=batch_in.feature_names,
        namespace=batch_in.namespace,
        start_time=start_time,
        end_time=end_time,
    )
    return BaseResponse(data=results, request_id=LogContext.get_request_id())


@router.get("/{feature_id}/consistency", response_model=BaseResponse[dict])
async def check_consistency(
    feature_id: UUID,
    entity_ids: str = Query(..., description="Comma-separated entity IDs"),
    db: AsyncSession = Depends(get_db),
):
    service = FeatureStoreService(db)
    entity_list = entity_ids.split(",")
    result = await service.check_consistency(feature_id, entity_list)
    return BaseResponse(data=result, request_id=LogContext.get_request_id())
