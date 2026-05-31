import time
from fastapi import APIRouter, HTTPException
from datetime import datetime, timezone

from wallethub import __version__
from wallethub.api.models.common import (
    HealthResponse,
    ResourceCreateRequest,
    ResourceStatusResponse,
    BatchOperationRequest,
    BatchOperationResponse,
    SuccessResponse,
    BatchResult,
)
from wallethub.utils import generate_id

router = APIRouter(tags=["Common"])

_start_time = time.time()


@router.get("/health", response_model=HealthResponse)
async def health_check():
    return HealthResponse(
        status="healthy",
        version=__version__,
        timestamp=datetime.now(timezone.utc),
        uptime_seconds=time.time() - _start_time,
    )


@router.post("/resources", response_model=SuccessResponse, status_code=201)
async def create_resource(request: ResourceCreateRequest):
    resource_id = generate_id("rsc")
    return SuccessResponse(
        code=201,
        message="Resource created",
        data={"id": resource_id, "status": "provisioning", "type": request.type},
    )


@router.get("/resources/{resource_id}/status", response_model=ResourceStatusResponse)
async def get_resource_status(resource_id: str):
    return ResourceStatusResponse(
        id=resource_id,
        status="completed",
        progress=1.0,
        phase="finished",
        started_at=datetime.now(timezone.utc),
        completed_at=datetime.now(timezone.utc),
    )


@router.post("/resources/batch", response_model=BatchOperationResponse)
async def batch_operations(request: BatchOperationRequest):
    batch_id = generate_id("batch")
    results = []

    for op in request.operations:
        result = BatchResult(
            id=op.id,
            success=True,
            result={"action": op.action, "status": "completed"},
        )
        results.append(result)

    return BatchOperationResponse(batch_id=batch_id, results=results)
