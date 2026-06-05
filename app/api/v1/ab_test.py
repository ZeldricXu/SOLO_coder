from typing import Optional, List
from datetime import datetime
from fastapi import APIRouter, HTTPException, Query, Depends, Body

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.model import (
    ABTestExperimentCreate,
    ABTestExperimentUpdate,
    ABTestStatusEnum,
    ABTestResultCreate,
)
from app.services.ab_test_service import ABTestService

logger = get_logger(__name__)
settings = get_settings()

router = APIRouter(prefix="/ab-test", tags=["ab-test"])


def get_ab_test_service() -> ABTestService:
    return ABTestService()


@router.post("/experiments", response_model=APIResponse)
async def create_experiment(
    request: ABTestExperimentCreate,
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        experiment = ab_test_service.create_experiment(request)

        return APIResponse(
            success=True,
            message="A/B test experiment created successfully",
            data={
                "experiment_id": experiment.id,
                "experiment_name": experiment.experiment_name,
                "model_name": experiment.model_name,
                "variant_a_model_id": experiment.variant_a_model_id,
                "variant_b_model_id": experiment.variant_b_model_id,
                "status": experiment.status.value,
            },
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to create A/B test experiment: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/experiments", response_model=APIResponse[PaginatedResponse])
async def list_experiments(
    model_name: Optional[str] = None,
    status: Optional[ABTestStatusEnum] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        experiments, total = ab_test_service.list_experiments(
            model_name=model_name,
            status=status,
            page=page,
            page_size=page_size,
        )

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=experiments,
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to list A/B test experiments: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/experiments/{experiment_id}", response_model=APIResponse)
async def get_experiment(
    experiment_id: int,
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        experiment = ab_test_service.get_experiment(experiment_id)
        if not experiment:
            raise HTTPException(status_code=404, detail="Experiment not found")

        return APIResponse(
            success=True,
            data={
                "id": experiment.id,
                "experiment_name": experiment.experiment_name,
                "model_name": experiment.model_name,
                "status": experiment.status.value,
                "variant_a_model_id": experiment.variant_a_model_id,
                "variant_b_model_id": experiment.variant_b_model_id,
                "traffic_split_a": experiment.traffic_split_a,
                "traffic_split_b": experiment.traffic_split_b,
                "strategy": experiment.strategy.value,
                "primary_metric": experiment.primary_metric,
                "sample_size_a": experiment.sample_size_a,
                "sample_size_b": experiment.sample_size_b,
                "winner": experiment.winner,
                "created_at": experiment.created_at,
                "started_at": experiment.started_at,
                "ended_at": experiment.ended_at,
            },
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get A/B test experiment {experiment_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.put("/experiments/{experiment_id}", response_model=APIResponse)
async def update_experiment(
    experiment_id: int,
    update: ABTestExperimentUpdate,
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        experiment = ab_test_service.update_experiment(experiment_id, update)
        if not experiment:
            raise HTTPException(status_code=404, detail="Experiment not found")

        return APIResponse(
            success=True,
            message="Experiment updated successfully",
            data={
                "experiment_id": experiment.id,
                "status": experiment.status.value,
            },
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update A/B test experiment {experiment_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/experiments/{experiment_id}/start", response_model=APIResponse)
async def start_experiment(
    experiment_id: int,
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        experiment = ab_test_service.start_experiment(experiment_id)
        if not experiment:
            raise HTTPException(status_code=404, detail="Experiment not found")

        return APIResponse(
            success=True,
            message="Experiment started",
            data={
                "experiment_id": experiment.id,
                "status": experiment.status.value,
                "started_at": experiment.started_at,
            },
        )

    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to start A/B test experiment {experiment_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/experiments/{experiment_id}/stop", response_model=APIResponse)
async def stop_experiment(
    experiment_id: int,
    winner: Optional[str] = Body(None),
    notes: Optional[str] = Body(None),
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        experiment = ab_test_service.stop_experiment(
            experiment_id=experiment_id,
            winner=winner,
            notes=notes,
        )
        if not experiment:
            raise HTTPException(status_code=404, detail="Experiment not found")

        return APIResponse(
            success=True,
            message="Experiment stopped",
            data={
                "experiment_id": experiment.id,
                "status": experiment.status.value,
                "winner": experiment.winner,
                "ended_at": experiment.ended_at,
            },
        )

    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to stop A/B test experiment {experiment_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/experiments/{experiment_id}/results", response_model=APIResponse)
async def get_experiment_results(
    experiment_id: int,
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        results = ab_test_service.get_experiment_results(experiment_id)
        if not results:
            raise HTTPException(status_code=404, detail="Experiment not found")

        return APIResponse(success=True, data=results)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get A/B test experiment results {experiment_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/experiments/{experiment_id}/results", response_model=APIResponse)
async def record_experiment_result(
    experiment_id: int,
    variant: str = Body(..., embed=True),
    metric_name: str = Body(..., embed=True),
    metric_value: float = Body(..., embed=True),
    document_id: Optional[int] = Body(None),
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        request = ABTestResultCreate(
            experiment_id=experiment_id,
            variant=variant,
            document_id=document_id,
            metric_name=metric_name,
            metric_value=metric_value,
        )

        result = ab_test_service.record_result(request)

        return APIResponse(
            success=True,
            message="Result recorded",
            data={"result_id": result.id},
        )

    except Exception as e:
        logger.error(f"Failed to record A/B test result: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/active/{model_name}", response_model=APIResponse)
async def get_active_experiment(
    model_name: str,
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        experiment = ab_test_service.get_active_experiment(model_name)

        if not experiment:
            return APIResponse(
                success=True,
                data={"active": False, "experiment": None},
            )

        return APIResponse(
            success=True,
            data={
                "active": True,
                "experiment": {
                    "id": experiment.id,
                    "experiment_name": experiment.experiment_name,
                    "variant_a_model_id": experiment.variant_a_model_id,
                    "variant_b_model_id": experiment.variant_b_model_id,
                    "traffic_split_a": experiment.traffic_split_a,
                    "traffic_split_b": experiment.traffic_split_b,
                    "strategy": experiment.strategy.value,
                },
            },
        )

    except Exception as e:
        logger.error(f"Failed to get active A/B test experiment: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/route/{model_name}", response_model=APIResponse)
async def route_traffic(
    model_name: str,
    document_id: Optional[int] = Body(None),
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        routing = ab_test_service.route_traffic(
            model_name=model_name,
            document_id=document_id,
        )

        return APIResponse(success=True, data=routing)

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to route traffic: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/experiments/{experiment_id}", response_model=APIResponse)
async def delete_experiment(
    experiment_id: int,
    ab_test_service: ABTestService = Depends(get_ab_test_service),
):
    try:
        success = ab_test_service.delete_experiment(experiment_id)
        if not success:
            raise HTTPException(status_code=404, detail="Experiment not found")

        return APIResponse(
            success=True,
            message="Experiment deleted successfully",
        )

    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to delete A/B test experiment {experiment_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
