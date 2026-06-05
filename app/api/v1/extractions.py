from typing import Optional, List
from datetime import datetime
from fastapi import APIRouter, HTTPException, Query, Depends

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.extraction import ExtractionStatusEnum
from app.services.extraction_service import ExtractionService

logger = get_logger(__name__)
settings = get_settings()

router = APIRouter(prefix="/extractions", tags=["extractions"])


def get_extraction_service() -> ExtractionService:
    return ExtractionService()


@router.get("", response_model=APIResponse[PaginatedResponse])
async def list_extraction_results(
    document_id: Optional[int] = None,
    status: Optional[ExtractionStatusEnum] = None,
    model_version: Optional[str] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        results, total = extraction_service.list_extraction_results(
            document_id=document_id,
            status=status,
            model_version=model_version,
            page=page,
            page_size=page_size,
        )

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=results,
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to list extraction results: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{extraction_id}", response_model=APIResponse)
async def get_extraction_result(
    extraction_id: int,
    include_fields: bool = True,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        result = extraction_service.get_extraction_result(
            extraction_id,
            include_fields=include_fields,
        )
        if not result:
            raise HTTPException(status_code=404, detail="Extraction result not found")

        return APIResponse(success=True, data=result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get extraction result {extraction_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/{extraction_id}", response_model=APIResponse)
async def delete_extraction_result(
    extraction_id: int,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        success = extraction_service.delete_extraction_result(extraction_id)
        if not success:
            raise HTTPException(status_code=404, detail="Extraction result not found")

        return APIResponse(
            success=True,
            message="Extraction result deleted successfully",
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete extraction result {extraction_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/compare/{result_id_a}/{result_id_b}", response_model=APIResponse)
async def compare_extraction_results(
    result_id_a: int,
    result_id_b: int,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        comparison = extraction_service.compare_extraction_results(
            result_id_a,
            result_id_b,
        )

        return APIResponse(success=True, data=comparison)

    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to compare extraction results: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/statistics", response_model=APIResponse)
async def get_extraction_statistics(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        stats = extraction_service.get_extraction_statistics(
            start_date=start_date,
            end_date=end_date,
        )

        return APIResponse(success=True, data=stats)

    except Exception as e:
        logger.error(f"Failed to get extraction statistics: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/history/{document_id}/{field_name}", response_model=APIResponse)
async def get_field_history(
    document_id: int,
    field_name: str,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        history = extraction_service.get_field_history(
            document_id=document_id,
            field_name=field_name,
        )

        return APIResponse(success=True, data={"field_name": field_name, "history": history})

    except Exception as e:
        logger.error(f"Failed to get field history: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
