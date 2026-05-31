from __future__ import annotations

from fastapi import APIRouter, HTTPException

from streamsql.api.schemas import (
    ResourceCreateRequest,
    ResourceStatusResponse,
    ResourceListResponse,
    BatchOperationRequest,
    BatchOperationResponse,
    ResourceResponse,
)
from streamsql.core.models import generate_id

router = APIRouter(prefix="/resources", tags=["resources"])

_resources: dict[str, dict] = {}


@router.post("", response_model=ResourceStatusResponse, status_code=201)
def create_resource(request: ResourceCreateRequest):
    try:
        resource_id = generate_id("rsc")
        resource = {
            "id": resource_id,
            "type": request.type,
            "config": request.config,
            "labels": request.labels,
            "status": "provisioning",
            "progress": 0.0,
        }
        _resources[resource_id] = resource

        return ResourceStatusResponse(
            code=201,
            message="Resource created",
            data=ResourceResponse(**resource),
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{resource_id}/status", response_model=ResourceStatusResponse)
def get_resource_status(resource_id: str):
    try:
        resource = _resources.get(resource_id)
        if not resource:
            raise HTTPException(status_code=404, detail="Resource not found")

        if resource["progress"] < 1.0:
            resource["progress"] = min(resource["progress"] + 0.25, 1.0)
            if resource["progress"] >= 1.0:
                resource["status"] = "running"

        return ResourceStatusResponse(
            code=200,
            message="success",
            data=ResourceResponse(**resource),
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("", response_model=ResourceListResponse)
def list_resources(status: str | None = None, resource_type: str | None = None):
    try:
        resources = list(_resources.values())
        if status:
            resources = [r for r in resources if r["status"] == status]
        if resource_type:
            resources = [r for r in resources if r["type"] == resource_type]

        return ResourceListResponse(
            code=200,
            message="success",
            data=[ResourceResponse(**r) for r in resources],
            total=len(resources),
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/{resource_id}")
def delete_resource(resource_id: str):
    try:
        if resource_id not in _resources:
            raise HTTPException(status_code=404, detail="Resource not found")
        del _resources[resource_id]
        return {"code": 200, "message": "Resource deleted", "data": {"id": resource_id}}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/batch", response_model=BatchOperationResponse)
def batch_operations(request: BatchOperationRequest):
    try:
        results = []
        for op in request.operations:
            action = op.get("action")
            resource_id = op.get("id")

            if action == "start" and resource_id in _resources:
                _resources[resource_id]["status"] = "running"
                _resources[resource_id]["progress"] = 1.0
                results.append({"id": resource_id, "action": "start", "success": True})
            elif action == "stop" and resource_id in _resources:
                _resources[resource_id]["status"] = "stopped"
                results.append({"id": resource_id, "action": "stop", "success": True})
            elif action == "delete" and resource_id in _resources:
                del _resources[resource_id]
                results.append({"id": resource_id, "action": "delete", "success": True})
            else:
                results.append({"id": resource_id, "action": action, "success": False, "error": "Not found or invalid action"})

        return BatchOperationResponse(
            code=200,
            message="Batch operations completed",
            data={"batch_id": generate_id("batch"), "results": results},
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
