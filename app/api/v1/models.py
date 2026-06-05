from typing import Optional, List
from datetime import datetime
from fastapi import APIRouter, HTTPException, Query, Depends, Body

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.model import (
    ModelVersionCreate,
    ModelVersionUpdate,
    ModelStatusEnum,
    ModelTypeEnum,
)
from app.services.model_service import ModelService

logger = get_logger(__name__)
settings = get_settings()

router = APIRouter(prefix="/models", tags=["models"])


def get_model_service() -> ModelService:
    return ModelService()


@router.post("", response_model=APIResponse)
async def register_model(
    request: ModelVersionCreate,
    model_service: ModelService = Depends(get_model_service),
):
    try:
        model = model_service.register_model(request)

        return APIResponse(
            success=True,
            message="Model registered successfully",
            data={
                "model_id": model.id,
                "model_name": model.model_name,
                "version": model.version,
                "model_type": model.model_type.value,
                "status": model.status.value,
            },
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to register model: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("", response_model=APIResponse[PaginatedResponse])
async def list_models(
    model_name: Optional[str] = None,
    model_type: Optional[ModelTypeEnum] = None,
    status: Optional[ModelStatusEnum] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    model_service: ModelService = Depends(get_model_service),
):
    try:
        models, total = model_service.list_models(
            model_name=model_name,
            model_type=model_type,
            status=status,
            page=page,
            page_size=page_size,
        )

        items = [
            {
                "id": m.id,
                "model_name": m.model_name,
                "version": m.version,
                "model_type": m.model_type.value,
                "status": m.status.value,
                "description": m.description,
                "created_at": m.created_at,
            }
            for m in models
        ]

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=items,
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to list models: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{model_id}", response_model=APIResponse)
async def get_model(
    model_id: int,
    model_service: ModelService = Depends(get_model_service),
):
    try:
        model = model_service.get_model(model_id)
        if not model:
            raise HTTPException(status_code=404, detail="Model not found")

        return APIResponse(
            success=True,
            data={
                "id": model.id,
                "model_name": model.model_name,
                "version": model.version,
                "model_type": model.model_type.value,
                "status": model.status.value,
                "description": model.description,
                "model_path": model.model_path,
                "performance_metrics": model.performance_metrics,
                "metadata": model.metadata,
                "created_at": model.created_at,
            },
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get model {model_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/name/{model_name}", response_model=APIResponse)
async def get_model_by_name(
    model_name: str,
    version: Optional[str] = None,
    model_service: ModelService = Depends(get_model_service),
):
    try:
        if version:
            model = model_service.get_model_by_name_version(model_name, version)
        else:
            model = model_service.get_production_model(model_name)

        if not model:
            raise HTTPException(status_code=404, detail="Model not found")

        return APIResponse(
            success=True,
            data={
                "id": model.id,
                "model_name": model.model_name,
                "version": model.version,
                "model_type": model.model_type.value,
                "status": model.status.value,
            },
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get model {model_name}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/name/{model_name}/versions", response_model=APIResponse)
async def get_model_versions(
    model_name: str,
    include_archived: bool = False,
    model_service: ModelService = Depends(get_model_service),
):
    try:
        versions = model_service.get_available_versions(
            model_name=model_name,
            include_archived=include_archived,
        )

        items = [
            {
                "id": v.id,
                "version": v.version,
                "status": v.status.value,
                "description": v.description,
                "performance_metrics": v.performance_metrics,
                "created_at": v.created_at,
            }
            for v in versions
        ]

        return APIResponse(
            success=True,
            data={"model_name": model_name,
                "versions": items,
            },
        )

    except Exception as e:
        logger.error(f"Failed to get model versions: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.put("/{model_id}", response_model=APIResponse)
async def update_model(
    model_id: int,
    update: ModelVersionUpdate,
    model_service: ModelService = Depends(get_model_service),
):
    try:
        model = model_service.update_model(model_id, update)
        if not model:
            raise HTTPException(status_code=404, detail="Model not found")

        return APIResponse(
            success=True,
            message="Model updated successfully",
            data={
                "model_id": model.id,
                "model_name": model.model_name,
                "version": model.version,
                "status": model.status.value,
            },
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update model {model_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{model_id}/status", response_model=APIResponse)
async def set_model_status(
    model_id: int,
    status: ModelStatusEnum = Body(..., embed=True),
    model_service: ModelService = Depends(get_model_service),
):
    try:
        model = model_service.set_model_status(model_id, status)
        if not model:
            raise HTTPException(status_code=404, detail="Model not found")

        return APIResponse(
            success=True,
            message=f"Model status set to {status.value}",
            data={
                "model_id": model.id,
                "status": model.status.value,
            },
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to set model status {model_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/{model_id}", response_model=APIResponse)
async def delete_model(
    model_id: int,
    model_service: ModelService = Depends(get_model_service),
):
    try:
        success = model_service.delete_model(model_id)
        if not success:
            raise HTTPException(status_code=404, detail="Model not found")

        return APIResponse(
            success=True,
            message="Model deleted successfully",
        )

    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to delete model {model_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{model_id}/statistics", response_model=APIResponse)
async def get_model_statistics(
    model_id: int,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    model_service: ModelService = Depends(get_model_service),
):
    try:
        stats = model_service.get_model_statistics(
            model_id=model_id,
            start_date=start_date,
            end_date=end_date,
        )

        if not stats:
            raise HTTPException(status_code=404, detail="Model not found")

        return APIResponse(success=True, data=stats)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get model statistics {model_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/compare", response_model=APIResponse)
async def compare_models(
    model_ids: List[int] = Body(..., embed=True),
    metric: str = Body("accuracy"),
    model_service: ModelService = Depends(get_model_service),
):
    try:
        comparison = model_service.compare_models(
            model_ids=model_ids,
            metric=metric,
        )

        return APIResponse(success=True, data=comparison)

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to compare models: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
