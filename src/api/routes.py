from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel

from src.api.dependencies import (
    get_core_handler,
    get_service_registry,
    get_task_orchestrator,
    get_task_scheduler,
)
from src.core.handler import CoreHandler, TaskOrchestrator
from src.logging_ import get_logger
from src.models import (
    APIResponse,
    BatchOperation,
    BatchOperationRequest,
    CreateResourceRequest,
    ServiceMetadata,
    Task,
    TaskGraph,
)
from src.registry.registry import ServiceRegistry
from src.scheduler.scheduler import TaskScheduler
from src.utils.errors import ResourceNotFoundError, ValidationError

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1", tags=["core"])


@router.get("/health", response_model=APIResponse)
async def health_check() -> APIResponse:
    return APIResponse(
        code=200,
        data={"status": "healthy", "timestamp": None},
        message="Service is running",
    )


@router.post("/resources", response_model=APIResponse, status_code=status.HTTP_201_CREATED)
async def create_resource(
    request: CreateResourceRequest,
    handler: CoreHandler = Depends(get_core_handler),
) -> APIResponse:
    result = await handler.create_resource(request.model_dump())
    if not result.success:
        raise HTTPException(status_code=result.code, detail=result.error)
    return APIResponse(code=result.code, data=result.data, message=result.message)


@router.get("/resources/{resource_id}/status", response_model=APIResponse)
async def get_resource_status(
    resource_id: str,
    handler: CoreHandler = Depends(get_core_handler),
) -> APIResponse:
    result = await handler.get_resource_status(resource_id)
    if not result.success:
        raise HTTPException(status_code=result.code, detail=result.error)
    return APIResponse(code=result.code, data=result.data, message=result.message)


@router.post("/resources/batch", response_model=APIResponse)
async def batch_operations(
    request: BatchOperationRequest,
    handler: CoreHandler = Depends(get_core_handler),
) -> APIResponse:
    operations = [op.model_dump() for op in request.operations]
    result = await handler.batch_operation(operations)
    if not result.success:
        raise HTTPException(status_code=result.code, detail=result.error)
    return APIResponse(code=result.code, data=result.data, message=result.message)


@router.get("/statistics", response_model=APIResponse)
async def get_statistics(
    handler: CoreHandler = Depends(get_core_handler),
) -> APIResponse:
    stats = handler.get_statistics()
    return APIResponse(code=200, data=stats, message="Statistics retrieved")


@router.get("/metrics", response_model=APIResponse)
async def get_metrics(
    limit: int = Query(100, ge=1, le=1000),
    orchestrator: TaskOrchestrator = Depends(get_task_orchestrator),
) -> APIResponse:
    metrics = orchestrator.get_metrics(limit)
    return APIResponse(
        code=200,
        data={"snapshots": [m.model_dump() for m in metrics]},
        message="Metrics retrieved",
    )


scheduler_router = APIRouter(prefix="/scheduler", tags=["scheduler"])


@scheduler_router.post("/tasks", response_model=APIResponse)
async def register_task(
    task: Task,
    scheduler: TaskScheduler = Depends(get_task_scheduler),
) -> APIResponse:
    scheduler.register_task(task)
    return APIResponse(
        code=201,
        data={"task_id": task.task_id, "name": task.name},
        message="Task registered",
    )


@scheduler_router.post("/graphs", response_model=APIResponse)
async def register_graph(
    graph: TaskGraph,
    scheduler: TaskScheduler = Depends(get_task_scheduler),
) -> APIResponse:
    scheduler.register_task_graph(graph)
    return APIResponse(
        code=201,
        data={"graph_id": graph.graph_id, "name": graph.name},
        message="Task graph registered",
    )


@scheduler_router.post("/graphs/{graph_id}/execute", response_model=APIResponse)
async def execute_graph(
    graph_id: str,
    orchestrator: TaskOrchestrator = Depends(get_task_orchestrator),
    scheduler: TaskScheduler = Depends(get_task_scheduler),
) -> APIResponse:
    graph = scheduler.get_graph(graph_id)
    if not graph:
        raise HTTPException(status_code=404, detail=f"Graph not found: {graph_id}")

    results = await orchestrator.execute_graph(graph)
    return APIResponse(
        code=200,
        data={
            "graph_id": graph_id,
            "results": {
                k: v.to_dict() if hasattr(v, "to_dict") else str(v)
                for k, v in results.items()
            },
        },
        message="Graph executed",
    )


@scheduler_router.get("/tasks", response_model=APIResponse)
async def list_tasks(
    scheduler: TaskScheduler = Depends(get_task_scheduler),
) -> APIResponse:
    tasks = scheduler.list_tasks()
    return APIResponse(
        code=200,
        data={"tasks": [t.model_dump() for t in tasks]},
        message="Tasks retrieved",
    )


@scheduler_router.get("/graphs", response_model=APIResponse)
async def list_graphs(
    scheduler: TaskScheduler = Depends(get_task_scheduler),
) -> APIResponse:
    graphs = scheduler.list_graphs()
    return APIResponse(
        code=200,
        data={"graphs": [g.model_dump() for g in graphs]},
        message="Graphs retrieved",
    )


@scheduler_router.get("/progress", response_model=APIResponse)
async def get_scheduler_progress(
    scheduler: TaskScheduler = Depends(get_task_scheduler),
) -> APIResponse:
    progress = scheduler.get_progress()
    return APIResponse(
        code=200,
        data={"progress": progress},
        message="Progress retrieved",
    )


registry_router = APIRouter(prefix="/registry", tags=["registry"])


@registry_router.post("/services", response_model=APIResponse, status_code=status.HTTP_201_CREATED)
async def register_service(
    metadata: ServiceMetadata,
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    node = registry.register(metadata)
    return APIResponse(
        code=201,
        data=node.to_dict(),
        message="Service registered",
    )


@registry_router.delete("/services/{service_id}", response_model=APIResponse)
async def unregister_service(
    service_id: str,
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    success = registry.unregister(service_id)
    if not success:
        raise HTTPException(status_code=404, detail=f"Service not found: {service_id}")
    return APIResponse(
        code=200,
        data={"service_id": service_id},
        message="Service unregistered",
    )


@registry_router.get("/services/{service_id}", response_model=APIResponse)
async def get_service(
    service_id: str,
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    try:
        node = registry.get(service_id)
        return APIResponse(code=200, data=node.to_dict(), message="Service retrieved")
    except ResourceNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))


@registry_router.get("/services", response_model=APIResponse)
async def search_services(
    q: Optional[str] = Query(None, description="Search query"),
    type: Optional[str] = Query(None, description="Service type filter"),
    language: Optional[str] = Query(None, description="Language filter"),
    tags: Optional[str] = Query(None, description="Comma-separated tags"),
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    tag_list = tags.split(",") if tags else None
    result = registry.search(
        query=q,
        type=type,
        language=language,
        tags=tag_list,
        page=page,
        page_size=page_size,
    )
    return APIResponse(
        code=200,
        data={
            "services": [s.to_dict() for s in result.services],
            "total": result.total,
            "page": result.page,
            "page_size": result.page_size,
            "facets": result.facets,
        },
        message="Services retrieved",
    )


@registry_router.post("/services/{source_id}/dependencies/{target_id}", response_model=APIResponse)
async def add_dependency(
    source_id: str,
    target_id: str,
    dependency_type: str = Query("runtime"),
    version_constraint: Optional[str] = None,
    description: Optional[str] = None,
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    try:
        edge = registry.add_dependency(
            source_service_id=source_id,
            target_service_id=target_id,
            dependency_type=dependency_type,
            version_constraint=version_constraint,
            description=description,
        )
        return APIResponse(code=201, data=edge.to_dict(), message="Dependency added")
    except ValidationError as e:
        raise HTTPException(status_code=400, detail=str(e))


@registry_router.get("/services/{service_id}/dependencies", response_model=APIResponse)
async def get_dependencies(
    service_id: str,
    transitive: bool = Query(False, description="Include transitive dependencies"),
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    try:
        if transitive:
            deps = registry.get_all_dependencies(service_id)
        else:
            deps = registry.get_dependencies(service_id)
        return APIResponse(
            code=200,
            data={"dependencies": [d.to_dict() for d in deps]},
            message="Dependencies retrieved",
        )
    except ResourceNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))


@registry_router.get("/services/{service_id}/dependents", response_model=APIResponse)
async def get_dependents(
    service_id: str,
    transitive: bool = Query(False, description="Include transitive dependents"),
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    try:
        if transitive:
            deps = registry.get_all_dependents(service_id)
        else:
            deps = registry.get_dependents(service_id)
        return APIResponse(
            code=200,
            data={"dependents": [d.to_dict() for d in deps]},
            message="Dependents retrieved",
        )
    except ResourceNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))


@registry_router.get("/graph/diagram", response_model=APIResponse)
async def get_dependency_diagram(
    format: str = Query("mermaid", pattern="^(mermaid|dot)$"),
    service_id: Optional[str] = None,
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    try:
        diagram = registry.generate_dependency_diagram(
            format=format,
            service_id=service_id,
        )
        return APIResponse(
            code=200,
            data={"format": format, "diagram": diagram},
            message="Diagram generated",
        )
    except ValidationError as e:
        raise HTTPException(status_code=400, detail=str(e))


@registry_router.get("/graph/topological", response_model=APIResponse)
async def get_topological_order(
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    try:
        order = registry.get_topological_order()
        return APIResponse(
            code=200,
            data={"order": order},
            message="Topological order retrieved",
        )
    except ValidationError as e:
        raise HTTPException(status_code=400, detail=str(e))


@registry_router.get("/graph/cycles", response_model=APIResponse)
async def detect_cycles(
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    cycles = registry.detect_cycles()
    return APIResponse(
        code=200,
        data={"cycles": cycles, "has_cycles": len(cycles) > 0},
        message="Cycle detection complete",
    )


@registry_router.get("/statistics", response_model=APIResponse)
async def get_registry_statistics(
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    stats = registry.get_statistics()
    return APIResponse(code=200, data=stats, message="Registry statistics retrieved")


@registry_router.post("/export", response_model=APIResponse)
async def export_registry(
    path: str = Query("./registry_export.json"),
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    registry.export_registry(path)
    return APIResponse(
        code=200,
        data={"export_path": path},
        message="Registry exported",
    )


@registry_router.post("/import", response_model=APIResponse)
async def import_registry(
    path: str = Query("./registry_export.json"),
    registry: ServiceRegistry = Depends(get_service_registry),
) -> APIResponse:
    services, dependencies = registry.import_registry(path)
    return APIResponse(
        code=200,
        data={
            "services_imported": services,
            "dependencies_imported": dependencies,
        },
        message="Registry imported",
    )


router.include_router(scheduler_router)
router.include_router(registry_router)
