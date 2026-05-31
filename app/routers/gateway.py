from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.modules.api_gateway import (
    get_current_user,
    Permission,
    require_permission,
    get_autoscaler_metrics,
    get_instance_metrics,
    get_circuit_breaker_metrics,
    get_rate_limiter_metrics,
    api_gateway,
    instance_manager,
    circuit_breaker_registry
)
from app.schemas import APIResponse
from app.logger import logger

router = APIRouter(prefix="/api/v1/gateway", tags=["API Gateway"])


@router.get("/autoscaler/metrics", response_model=APIResponse)
async def get_autoscaler_status(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    metrics = get_autoscaler_metrics()
    return APIResponse(code=200, data=metrics)


@router.post("/autoscaler/scale", response_model=APIResponse)
async def force_scale(
    target_count: int = Query(..., ge=1, le=20, description="Target instance count"),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    api_gateway.autoscaler.force_scale(target_count)
    
    return APIResponse(
        code=200,
        data={
            "message": f"Scaling to {target_count} instances",
            "target_count": target_count
        }
    )


@router.get("/instances", response_model=APIResponse)
async def list_instances(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    metrics = get_instance_metrics()
    return APIResponse(code=200, data=metrics)


@router.post("/instances", response_model=APIResponse)
async def register_instance(
    instance_id: str,
    weight: float = Query(1.0, ge=0.1, le=5.0, description="Instance weight for load balancing"),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    await instance_manager.register_instance(instance_id, weight)
    
    return APIResponse(
        code=201,
        data={
            "instance_id": instance_id,
            "weight": weight,
            "status": "registered"
        }
    )


@router.delete("/instances/{instance_id}", response_model=APIResponse)
async def deregister_instance(
    instance_id: str,
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    await instance_manager.deregister_instance(instance_id)
    
    return APIResponse(
        code=200,
        data={
            "instance_id": instance_id,
            "status": "deregistered"
        }
    )


@router.patch("/instances/{instance_id}/weight", response_model=APIResponse)
async def update_instance_weight(
    instance_id: str,
    weight: float = Query(..., ge=0.1, le=5.0, description="New weight"),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    await instance_manager.update_weight(instance_id, weight)
    
    return APIResponse(
        code=200,
        data={
            "instance_id": instance_id,
            "weight": weight,
            "status": "updated"
        }
    )


@router.get("/circuit-breakers", response_model=APIResponse)
async def get_circuit_breakers(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    metrics = get_circuit_breaker_metrics()
    return APIResponse(code=200, data={"circuits": metrics})


@router.post("/circuit-breakers/{circuit_name}/reset", response_model=APIResponse)
async def reset_circuit_breaker(
    circuit_name: str,
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    cb = circuit_breaker_registry.get(circuit_name)
    cb.force_close()
    
    return APIResponse(
        code=200,
        data={
            "circuit_name": circuit_name,
            "state": cb.state,
            "status": "reset"
        }
    )


@router.post("/circuit-breakers/reset-all", response_model=APIResponse)
async def reset_all_circuit_breakers(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    circuit_breaker_registry.reset_all()
    
    return APIResponse(
        code=200,
        data={"status": "all_circuits_reset"}
    )


@router.get("/rate-limiter/metrics", response_model=APIResponse)
async def get_rate_limiter_status(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    metrics = get_rate_limiter_metrics()
    return APIResponse(code=200, data=metrics)


@router.post("/autoscaler/start", response_model=APIResponse)
async def start_autoscaler(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    api_gateway.autoscaler.start()
    
    return APIResponse(
        code=200,
        data={"status": "autoscaler_started"}
    )


@router.post("/autoscaler/stop", response_model=APIResponse)
async def stop_autoscaler(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    api_gateway.autoscaler.stop()
    
    return APIResponse(
        code=200,
        data={"status": "autoscaler_stopped"}
    )


@router.get("/status", response_model=APIResponse)
async def get_gateway_status(
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    autoscaler = get_autoscaler_metrics()
    instances = get_instance_metrics()
    circuits = get_circuit_breaker_metrics()
    rate_limiter = get_rate_limiter_metrics()
    
    return APIResponse(
        code=200,
        data={
            "autoscaler": autoscaler,
            "instances": instances,
            "circuit_breakers": circuits,
            "rate_limiter": rate_limiter
        }
    )
