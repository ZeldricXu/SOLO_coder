from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ...dataclasses.requests import FilterConfig
from ..deps import EventListenerModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/events", tags=["Event Listener"])


class CreateFilterRequest(BaseModel):
    chain_id: int
    name: str
    contract_address: str
    event_signature: str
    topics: List[str] = []
    from_block: int = 0
    to_block: Optional[int] = None
    callback_url: Optional[str] = None
    callback_headers: Dict[str, str] = {}
    strategy: Optional[str] = None


@router.post("/filters", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def create_filter(
    request: CreateFilterRequest,
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        config = FilterConfig(
            chain_id=request.chain_id,
            name=request.name,
            contract_address=request.contract_address,
            event_signature=request.event_signature,
            topics=request.topics,
            from_block=request.from_block,
            to_block=request.to_block,
            callback_url=request.callback_url,
            callback_headers=request.callback_headers,
            strategy=request.strategy,
        )
        filter_obj = await event_listener.create_filter(config)
        return ResourceResponse(
            code=201,
            message="Filter created successfully",
            request_id=trace_id,
            data=filter_obj,
        )
    except Exception as e:
        logger.error(f"Error creating event filter: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/filters", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_filters(
    chain_id: Optional[int] = None,
    is_active: Optional[bool] = None,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    event_listener: EventListenerModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        filters = await event_listener.list_filters(
            chain_id=chain_id,
            is_active=is_active,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"filters": filters, "total": len(filters)},
        )
    except Exception as e:
        logger.error(f"Error listing event filters: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/filters/{filter_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_filter(
    filter_id: str,
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        filter_obj = await event_listener.get_filter(filter_id)
        if not filter_obj:
            raise HTTPException(status_code=404, detail="Filter not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=filter_obj,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting filter {filter_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/filters/{filter_id}/pause", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def pause_filter(
    filter_id: str,
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        await event_listener.pause_filter(filter_id)
        return ResourceResponse(
            code=200,
            message="Filter paused successfully",
            request_id=trace_id,
            data={"filter_id": filter_id, "status": "paused"},
        )
    except Exception as e:
        logger.error(f"Error pausing filter {filter_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/filters/{filter_id}/resume", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def resume_filter(
    filter_id: str,
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        await event_listener.resume_filter(filter_id)
        return ResourceResponse(
            code=200,
            message="Filter resumed successfully",
            request_id=trace_id,
            data={"filter_id": filter_id, "status": "active"},
        )
    except Exception as e:
        logger.error(f"Error resuming filter {filter_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/filters/{filter_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def delete_filter(
    filter_id: str,
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        await event_listener.delete_filter(filter_id)
        return ResourceResponse(
            code=200,
            message="Filter deleted successfully",
            request_id=trace_id,
            data={"filter_id": filter_id},
        )
    except Exception as e:
        logger.error(f"Error deleting filter {filter_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/filters/{filter_id}/logs", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_event_logs(
    filter_id: str,
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
    event_listener: EventListenerModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        logs = await event_listener.get_event_logs(filter_id, limit=limit, offset=offset)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"logs": logs, "total": len(logs)},
        )
    except Exception as e:
        logger.error(f"Error getting event logs for filter {filter_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/strategies", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_available_strategies(
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await event_listener.get_available_strategies()
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error getting available strategies: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/filter-strategies", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_filter_strategies(
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await event_listener.get_filter_strategies()
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error getting filter strategies: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/strategies/default", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def set_default_strategy(
    request: dict,
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        strategy_type = request.get("strategy_type")
        if not strategy_type:
            raise HTTPException(status_code=400, detail="strategy_type is required")
        result = await event_listener.set_default_strategy(strategy_type)
        return ResourceResponse(
            code=200,
            message="Default strategy updated successfully",
            request_id=trace_id,
            data=result,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error setting default strategy: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/filters/{filter_id}/strategy", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def set_filter_strategy(
    filter_id: str,
    request: dict,
    event_listener: EventListenerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        strategy_type = request.get("strategy_type")
        if not strategy_type:
            raise HTTPException(status_code=400, detail="strategy_type is required")
        result = await event_listener.set_filter_strategy(filter_id, strategy_type)
        return ResourceResponse(
            code=200,
            message="Filter strategy updated successfully",
            request_id=trace_id,
            data=result,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error setting filter strategy: {e}")
        raise HTTPException(status_code=500, detail=str(e))
