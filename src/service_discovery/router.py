from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from src.common.models import APIResponse
from src.service_discovery.models import (
    HealthStatus,
    ServiceHealth,
    ServiceMetadata,
    ServiceQuery,
    ServiceRegistrationRequest,
    ServiceType,
)
from src.service_discovery.registry import ServiceRegistry

router = APIRouter(prefix="/discovery", tags=["Service Discovery"])

_registry: ServiceRegistry | None = None


def get_registry() -> ServiceRegistry:
    global _registry
    if _registry is None:
        _registry = ServiceRegistry()
    return _registry


class HealthUpdateRequest(BaseModel):
    health_status: HealthStatus
    message: str = ""
    metrics: Dict[str, Any] = {}


@router.get("/services")
async def list_services(
    name: Optional[str] = None,
    type: Optional[ServiceType] = None,
    status: Optional[str] = None,
    owner: Optional[str] = None,
    tags: Optional[List[str]] = Query(default=None),
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    query = ServiceQuery(
        name=name,
        type=type,
        status=status,
        owner=owner,
        tags=tags or [],
    )
    services = registry.query(query)
    return APIResponse(data=[s.model_dump() for s in services])


@router.post("/services", status_code=201)
async def register_service(
    request: ServiceRegistrationRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    service = ServiceMetadata(**request.model_dump())
    result = registry.register(service)
    return APIResponse(code=201, data=result.model_dump())


@router.get("/services/{service_id}")
async def get_service(
    service_id: str,
    include_health: bool = True,
    include_dependencies: bool = True,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    details = registry.get_with_details(service_id)
    if not details:
        raise HTTPException(status_code=404, detail="Service not found")
    return APIResponse(data={
        "service": details["service"].model_dump(),
        "health": details["health"].model_dump() if details["health"] and include_health else None,
        "dependents": details["dependents"] if include_dependencies else [],
        "dependencies": details["dependencies"] if include_dependencies else [],
    })


@router.put("/services/{service_id}")
async def update_service(
    service_id: str,
    request: ServiceRegistrationRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    existing = registry.get(service_id)
    if not existing:
        raise HTTPException(status_code=404, detail="Service not found")

    updated = ServiceMetadata(
        service_id=service_id,
        **request.model_dump(),
        created_at=existing.created_at,
    )
    registry.register(updated)
    return APIResponse(data=updated.model_dump())


@router.delete("/services/{service_id}", status_code=204)
async def unregister_service(
    service_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    if not registry.unregister(service_id):
        raise HTTPException(status_code=404, detail="Service not found")


@router.get("/services/{service_id}/health")
async def get_service_health(
    service_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    health = registry.get_health(service_id)
    if not health:
        raise HTTPException(status_code=404, detail="Service not found")
    return APIResponse(data=health.model_dump())


@router.post("/services/{service_id}/health")
async def update_service_health(
    service_id: str,
    request: HealthUpdateRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    if not registry.get(service_id):
        raise HTTPException(status_code=404, detail="Service not found")

    health = ServiceHealth(service_id=service_id, **request.model_dump())
    registry.update_health(health)
    return APIResponse(data=health.model_dump())


@router.get("/services/{service_id}/dependents")
async def get_service_dependents(
    service_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    if not registry.get(service_id):
        raise HTTPException(status_code=404, detail="Service not found")
    dependents = registry.get_dependents(service_id)
    return APIResponse(data=[d.model_dump() for d in dependents])


@router.get("/search")
async def search_services(
    q: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    if not q.strip():
        return APIResponse(data=[])
    results = registry.search(q)
    return APIResponse(data=[s.model_dump() for s in results])


@router.get("/graph")
async def get_dependency_graph(
    registry: ServiceRegistry = Depends(get_registry),
) -> APIResponse:
    graph = registry.get_dependency_graph()
    return APIResponse(data=graph.model_dump())
