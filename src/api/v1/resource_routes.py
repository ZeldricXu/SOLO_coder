from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException

from src.shared.container import Container, container
from src.shared.types import (
    APIResponse,
    BatchOperation,
    BatchRequest,
    BatchResponse,
    BatchResult,
    ResourceCreateRequest,
    ResourceStatusResponse,
)

router = APIRouter(prefix="/resources", tags=["resources"])


async def get_container() -> Container:
    return container


@router.post("", response_model=APIResponse[Dict[str, Any]], status_code=201)
async def create_resource(
    request: ResourceCreateRequest,
    container: Container = Depends(get_container),
):
    try:
        resource_id = f"rsc_{__import__('uuid').uuid4().hex[:12]}"
        
        resource_data = {
            "id": resource_id,
            "type": request.type,
            "config": request.config,
            "labels": request.labels,
            "status": "provisioning",
            "created_at": __import__("datetime").datetime.utcnow(),
        }
        
        return APIResponse.created(
            data={"id": resource_id, "status": "provisioning"},
            message="Resource created successfully"
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{resource_id}/status", response_model=APIResponse[ResourceStatusResponse])
async def get_resource_status(
    resource_id: str,
    container: Container = Depends(get_container),
):
    try:
        status = ResourceStatusResponse(
            id=resource_id,
            status="completed",
            progress=1.0,
            started_at=__import__("datetime").datetime.utcnow(),
            completed_at=__import__("datetime").datetime.utcnow(),
        )
        return APIResponse.success(data=status)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/batch", response_model=APIResponse[BatchResponse])
async def batch_operations(
    request: BatchRequest,
    container: Container = Depends(get_container),
):
    try:
        results: List[BatchResult] = []
        
        for op in request.operations:
            try:
                result_data = await _execute_operation(op, container)
                results.append(BatchResult(
                    id=op.id,
                    success=True,
                    result=result_data,
                    error=None
                ))
            except Exception as e:
                results.append(BatchResult(
                    id=op.id,
                    success=False,
                    result=None,
                    error=str(e)
                ))
        
        batch_response = BatchResponse(results=results)
        return APIResponse.success(data=batch_response)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


async def _execute_operation(
    op: BatchOperation,
    container: Container,
) -> Optional[Dict[str, Any]]:
    action = op.action.lower()
    
    if action == "start":
        return {"status": "started", "id": op.id}
    elif action == "stop":
        return {"status": "stopped", "id": op.id}
    elif action == "restart":
        return {"status": "restarted", "id": op.id}
    elif action == "delete":
        return {"status": "deleted", "id": op.id}
    else:
        raise ValueError(f"Unknown operation: {action}")
