from fastapi import APIRouter, Depends, Query, UploadFile, File
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional
from fastapi.responses import StreamingResponse, Response

from app.database import get_db
from app.schemas import (
    StorageObjectCreate,
    StorageObjectResponse,
    StorageMetadataCreate,
    PresignedUrlRequest,
    PresignedUrlResponse,
    BaseResponse,
    PaginatedResponse,
)
from app.storage.service import StorageService
from app.logging import LogContext

router = APIRouter(prefix="/api/v1/storage", tags=["Storage"])


@router.post("/upload", response_model=BaseResponse[StorageObjectResponse])
async def upload_object(
    bucket: str = Query(..., description="Bucket name"),
    key: str = Query(..., description="Object key"),
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    data = await file.read()
    storage_obj = await service.upload_object(
        bucket=bucket,
        key=key,
        data=data,
        content_type=file.content_type,
    )
    return BaseResponse(
        code=201,
        data=storage_obj,
        request_id=LogContext.get_request_id(),
        message="Object uploaded successfully",
    )


@router.get("/download")
async def download_object(
    bucket: str = Query(..., description="Bucket name"),
    key: str = Query(..., description="Object key"),
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    data, storage_obj = await service.download_object(bucket, key)
    return Response(
        content=data,
        media_type=storage_obj.content_type or "application/octet-stream",
        headers={
            "Content-Disposition": f"attachment; filename={storage_obj.key.split('/')[-1]}"
        },
    )


@router.get("/objects/{object_id}", response_model=BaseResponse[StorageObjectResponse])
async def get_object(
    object_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    storage_obj = await service.get_object(object_id)
    return BaseResponse(data=storage_obj, request_id=LogContext.get_request_id())


@router.get("/objects", response_model=BaseResponse[PaginatedResponse[StorageObjectResponse]])
async def list_objects(
    bucket: str = Query(..., description="Bucket name"),
    prefix: Optional[str] = Query(None, description="Key prefix"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    skip = (page - 1) * page_size
    objects, total = await service.list_objects(
        bucket=bucket,
        prefix=prefix,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=objects,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.delete("/objects", response_model=BaseResponse)
async def delete_object(
    bucket: str = Query(..., description="Bucket name"),
    key: str = Query(..., description="Object key"),
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    await service.delete_object(bucket, key)
    return BaseResponse(
        message="Object deleted successfully",
        request_id=LogContext.get_request_id(),
    )


@router.post("/presigned-url", response_model=BaseResponse[PresignedUrlResponse])
async def create_presigned_url(
    request: PresignedUrlRequest,
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    response = await service.create_presigned_url(request)
    return BaseResponse(data=response, request_id=LogContext.get_request_id())


@router.post("/metadata", response_model=BaseResponse)
async def add_metadata(
    metadata_in: StorageMetadataCreate,
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    metadata = await service.add_metadata(metadata_in)
    return BaseResponse(
        code=201,
        data={"id": str(metadata.id)},
        request_id=LogContext.get_request_id(),
        message="Metadata added successfully",
    )


@router.get("/objects/{object_id}/metadata", response_model=BaseResponse[list])
async def get_object_metadata(
    object_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    metadata = await service.get_object_metadata(object_id)
    return BaseResponse(data=metadata, request_id=LogContext.get_request_id())


@router.post("/objects/{object_id}/archive", response_model=BaseResponse[StorageObjectResponse])
async def archive_object(
    object_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    storage_obj = await service.archive_object(object_id)
    return BaseResponse(
        data=storage_obj,
        request_id=LogContext.get_request_id(),
        message="Object archived successfully",
    )


@router.post("/objects/{object_id}/restore", response_model=BaseResponse[StorageObjectResponse])
async def restore_object(
    object_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    storage_obj = await service.restore_object(object_id)
    return BaseResponse(
        data=storage_obj,
        request_id=LogContext.get_request_id(),
        message="Object restored successfully",
    )


@router.get("/stats", response_model=BaseResponse[dict])
async def get_storage_stats(
    bucket: str = Query(..., description="Bucket name"),
    db: AsyncSession = Depends(get_db),
):
    service = StorageService(db)
    stats = await service.get_storage_stats(bucket)
    return BaseResponse(data=stats, request_id=LogContext.get_request_id())
