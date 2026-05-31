from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException

from src.shared.container import Container, container
from src.shared.types import (
    Address,
    APIResponse,
    BlockNumber,
    Chain,
    EventLog,
)

router = APIRouter(prefix="/events", tags=["events"])


async def get_container() -> Container:
    return container


@router.post("/{chain}/register", response_model=APIResponse[str])
async def register_event_callback(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        listener = container.get_event_listener(chain)
        
        async def dummy_callback(log: EventLog, decoded: Dict[str, Any]):
            pass
        
        callback_id = await listener.register_callback(
            event_name=request["event_name"],
            contract_address=request["contract_address"],
            callback=dummy_callback,
            abi=request.get("abi"),
            from_block=request.get("from_block"),
            filter_params=request.get("filter_params"),
        )
        
        return APIResponse.success(data=callback_id)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/{chain}/callback/{callback_id}", response_model=APIResponse[bool])
async def unregister_event_callback(
    chain: Chain,
    callback_id: str,
    container: Container = Depends(get_container),
):
    try:
        listener = container.get_event_listener(chain)
        success = await listener.unregister_callback(callback_id)
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/start", response_model=APIResponse[bool])
async def start_listening(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        listener = container.get_event_listener(chain)
        await listener.start_listening()
        return APIResponse.success(data=True)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/stop", response_model=APIResponse[bool])
async def stop_listening(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        listener = container.get_event_listener(chain)
        await listener.stop_listening()
        return APIResponse.success(data=True)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/status", response_model=APIResponse[Dict[str, Any]])
async def get_listener_status(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        listener = container.get_event_listener(chain)
        callbacks = listener.get_registered_callbacks()
        return APIResponse.success(data={
            "is_listening": listener.is_listening(),
            "callback_count": len(callbacks),
            "callbacks": list(callbacks.keys()),
        })
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/query", response_model=APIResponse[List[EventLog]])
async def query_past_events(
    chain: Chain,
    contract_address: Address,
    event_name: str,
    from_block: BlockNumber,
    to_block: Optional[BlockNumber] = None,
    container: Container = Depends(get_container),
):
    try:
        listener = container.get_event_listener(chain)
        events = await listener.fetch_past_events(
            contract_address=contract_address,
            event_name=event_name,
            from_block=from_block,
            to_block=to_block,
        )
        return APIResponse.success(data=events)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
