from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from streamsql.api.schemas import LifecyclePolicyRequest, LifecyclePolicyResponse
from streamsql.services.lifecycle_service import LifecycleService
from streamsql.api.dependencies import get_lifecycle_service

router = APIRouter(prefix="/lifecycle", tags=["lifecycle"])


@router.post("/policy", response_model=LifecyclePolicyResponse)
def create_policy(
    request: LifecyclePolicyRequest,
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.create_policy(
            table_name=request.table_name,
            hot_ttl_days=request.hot_ttl_days,
            cold_ttl_days=request.cold_ttl_days,
            archive_ttl_days=request.archive_ttl_days,
            auto_cleanup=request.auto_cleanup,
        )
        return LifecyclePolicyResponse(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/policy/{table_name}")
def get_policy(
    table_name: str,
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.get_policy(table_name)
        if not result:
            raise HTTPException(status_code=404, detail="Policy not found")
        return {"code": 200, "data": result}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/policies")
def list_policies(
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.list_policies()
        return {"code": 200, "data": result, "total": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/migrate/{table_name}")
def migrate_data(
    table_name: str,
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.migrate_data(table_name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/archive/{table_name}")
def archive_table(
    table_name: str,
    data: list[dict],
    format_type: str = "json",
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.archive_table(table_name, data, format_type)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/cleanup")
def cleanup_expired(
    table_name: str | None = None,
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.cleanup_expired(table_name)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/storage-summary")
def get_storage_summary(
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.get_storage_summary()
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/tier/{table_name}")
def get_table_tier(
    table_name: str,
    timestamp_ms: int,
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.get_table_tier(table_name, timestamp_ms)
        return {"code": 200, "data": {"table": table_name, "tier": result}}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/tables")
def list_tiered_tables(
    tier: str | None = None,
    service: LifecycleService = Depends(get_lifecycle_service),
):
    try:
        result = service.list_tiered_tables(tier)
        return {"code": 200, "data": result, "total": len(result)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
