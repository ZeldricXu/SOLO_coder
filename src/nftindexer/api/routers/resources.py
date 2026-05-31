from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException

from ...core.schemas import (
    ResourceCreateRequest,
    ResourceResponse,
    ResourceStatusResponse,
    BatchOperationRequest,
    BatchOperationResponse,
)
from ...utils import get_logger, generate_id
from ..deps import TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/resources", tags=["Resources"])


@router.post("", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def create_resource(
    request: ResourceCreateRequest,
    trace_id: TraceIdDep,
):
    try:
        resource_id = f"rsc_{generate_id()[:8]}"
        return ResourceResponse(
            code=201,
            message="Resource created",
            request_id=trace_id,
            data={
                "id": resource_id,
                "type": request.type,
                "status": "provisioning",
                "config": request.config,
                "labels": request.labels,
            },
        )
    except Exception as e:
        logger.error(f"Error creating resource: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{resource_id}/status", response_model=ResourceStatusResponse, dependencies=[ApiKeyDep])
async def get_resource_status(
    resource_id: str,
    trace_id: TraceIdDep,
):
    try:
        return ResourceStatusResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={
                "id": resource_id,
                "status": "running",
                "progress": 0.8,
            },
        )
    except Exception as e:
        logger.error(f"Error getting resource status: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/batch", response_model=BatchOperationResponse, dependencies=[ApiKeyDep])
async def batch_operations(
    request: BatchOperationRequest,
    trace_id: TraceIdDep,
):
    try:
        batch_id = f"batch_{generate_id()[:8]}"
        results = []

        for op in request.operations:
            results.append({
                "id": op.id,
                "success": True,
                "action": op.action,
            })

        return BatchOperationResponse(
            code=200,
            message="Batch operation complete",
            request_id=trace_id,
            data={
                "batch_id": batch_id,
                "results": results,
            },
        )
    except Exception as e:
        logger.error(f"Error processing batch operations: {e}")
        raise HTTPException(status_code=500, detail=str(e))
