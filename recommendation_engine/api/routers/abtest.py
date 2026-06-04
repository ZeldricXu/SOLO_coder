from fastapi import APIRouter, Depends, HTTPException, status, Query, Body
from typing import Optional, List, Dict, Any

from recommendation_engine.models.schemas import ABTestExperiment
from recommendation_engine.api.dependencies import (
    get_abtest_router_svc,
    verify_api_key,
)
from recommendation_engine.ab_test_router import ABTestRouter

router = APIRouter(prefix="/api/v1/abtest", tags=["ab-test"], dependencies=[Depends(verify_api_key)])


@router.post("/experiments", status_code=status.HTTP_201_CREATED)
async def create_experiment(
    experiment: ABTestExperiment,
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        success = await service.create_experiment(experiment)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Failed to create experiment (may conflict with existing)",
            )
        return {"status": "created", "experiment_id": experiment.experiment_id}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to create experiment: {str(e)}",
        )


@router.get("/experiments")
async def list_experiments(
    layer: Optional[str] = Query(None),
    status: Optional[str] = Query(None, pattern=r"^(active|paused|ended)$"),
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        experiments = await service.list_experiments(layer, status)
        return {"experiments": experiments}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to list experiments: {str(e)}",
        )


@router.get("/experiments/{experiment_id}")
async def get_experiment(
    experiment_id: str,
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        experiment = await service.get_experiment(experiment_id)
        if not experiment:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Experiment {experiment_id} not found",
            )
        return experiment
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get experiment: {str(e)}",
        )


@router.put("/experiments/{experiment_id}/status")
async def update_experiment_status(
    experiment_id: str,
    status_data: Dict[str, str] = Body(...),
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        new_status = status_data.get("status")
        if new_status not in ["active", "paused", "ended"]:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Invalid status, must be active|paused|ended",
            )
        success = await service.update_experiment_status(experiment_id, new_status)
        return {"status": "success" if success else "failed", "updated": success}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to update experiment status: {str(e)}",
        )


@router.delete("/experiments/{experiment_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_experiment(
    experiment_id: str,
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        success = await service.delete_experiment(experiment_id)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Experiment {experiment_id} not found",
            )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to delete experiment: {str(e)}",
        )


@router.get("/assignment/{user_id}")
async def get_user_assignment(
    user_id: str,
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        assignments = await service.get_user_assignment(user_id)
        return {"user_id": user_id, "assignments": assignments}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get user assignment: {str(e)}",
        )


@router.get("/config/{user_id}")
async def get_experiment_config(
    user_id: str,
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        config = await service.get_experiment_config(user_id)
        return {"user_id": user_id, "config": config}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get experiment config: {str(e)}",
        )


@router.post("/refresh", status_code=status.HTTP_200_OK)
async def refresh_config(
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        await service.refresh_config()
        return {"status": "success", "message": "Config refreshed"}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to refresh config: {str(e)}",
        )


@router.get("/layers")
async def get_layers(
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        layers = service.get_layers()
        return {"layers": layers}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get layers: {str(e)}",
        )


@router.get("/stats")
async def get_abtest_stats(
    service: ABTestRouter = Depends(get_abtest_router_svc),
):
    try:
        stats = service.get_stats()
        return stats
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get stats: {str(e)}",
        )
