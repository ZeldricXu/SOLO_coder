from typing import Optional, List
from datetime import datetime
from fastapi import APIRouter, UploadFile, File, HTTPException, Query, Depends, Body

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.batch import BatchStatusEnum, BatchPriorityEnum
from app.schemas.document import DocumentPriorityEnum
from app.services.batch_service import BatchService
from app.tasks.batch import process_batch_task

logger = get_logger(__name__)
settings = get_settings()

router = APIRouter(prefix="/batches", tags=["batches"])


def get_batch_service() -> BatchService:
    return BatchService()


@router.post("/upload", response_model=APIResponse)
async def upload_batch(
    file: UploadFile = File(...),
    batch_name: str = Query(...),
    priority: BatchPriorityEnum = BatchPriorityEnum.MEDIUM,
    document_priority: DocumentPriorityEnum = DocumentPriorityEnum.MEDIUM,
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        file_data = await file.read()

        batch = batch_service.create_batch_from_zip(
            zip_data=file_data,
            batch_name=batch_name,
            priority=priority,
            document_priority=document_priority,
        )

        return APIResponse(
            success=True,
            message="Batch uploaded successfully",
            data={
                "batch_id": batch.id,
                "batch_name": batch.batch_name,
                "total_documents": batch.total_documents,
                "pending_documents": batch.pending_documents,
                "failed_documents": batch.failed_documents,
                "status": batch.status.value,
            },
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to upload batch: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("", response_model=APIResponse[PaginatedResponse])
async def list_batches(
    status: Optional[BatchStatusEnum] = None,
    priority: Optional[BatchPriorityEnum] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        batches, total = batch_service.list_batches(
            status=status,
            priority=priority,
            page=page,
            page_size=page_size,
        )

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=batches,
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to list batches: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{batch_id}", response_model=APIResponse)
async def get_batch(
    batch_id: int,
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        batch = batch_service.get_batch_with_details(batch_id)
        if not batch:
            raise HTTPException(status_code=404, detail="Batch not found")

        return APIResponse(success=True, data=batch)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get batch {batch_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/{batch_id}", response_model=APIResponse)
async def delete_batch(
    batch_id: int,
    delete_documents: bool = False,
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        success = batch_service.delete_batch(batch_id, delete_documents)
        if not success:
            raise HTTPException(status_code=404, detail="Batch not found")

        return APIResponse(
            success=True,
            message="Batch deleted successfully",
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete batch {batch_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{batch_id}/process", response_model=APIResponse)
async def process_batch(
    batch_id: int,
    async_processing: bool = True,
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        batch = batch_service.get_batch(batch_id)
        if not batch:
            raise HTTPException(status_code=404, detail="Batch not found")

        if async_processing:
            task = process_batch_task.delay(batch_id=batch_id)

            return APIResponse(
                success=True,
                message="Batch processing started",
                data={
                    "task_id": task.id,
                    "batch_id": batch_id,
                },
            )
        else:
            result = process_batch_task.apply(kwargs={"batch_id": batch_id})

            return APIResponse(
                success=True,
                message="Batch processing completed",
                data=result.result,
            )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to process batch {batch_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{batch_id}/progress", response_model=APIResponse)
async def get_batch_progress(
    batch_id: int,
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        progress = batch_service.get_batch_progress(batch_id)
        if not progress:
            raise HTTPException(status_code=404, detail="Batch not found")

        return APIResponse(success=True, data=progress)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get batch progress {batch_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{batch_id}/cancel", response_model=APIResponse)
async def cancel_batch(
    batch_id: int,
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        batch = batch_service.cancel_batch(batch_id)
        if not batch:
            raise HTTPException(status_code=404, detail="Batch not found")

        return APIResponse(
            success=True,
            message="Batch cancelled",
            data={"batch_id": batch.id, "status": batch.status.value},
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to cancel batch {batch_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/statistics", response_model=APIResponse)
async def get_batch_statistics(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    batch_service: BatchService = Depends(get_batch_service),
):
    try:
        stats = batch_service.get_batch_statistics(
            start_date=start_date,
            end_date=end_date,
        )

        return APIResponse(success=True, data=stats)

    except Exception as e:
        logger.error(f"Failed to get batch statistics: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
