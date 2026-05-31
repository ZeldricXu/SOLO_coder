from fastapi import APIRouter, HTTPException
from datetime import datetime, timezone

from wallethub.api.models.events_models import (
    EventListenerCreateRequest,
    EventListenerResponse,
)
from wallethub.core import EventListenerError

router = APIRouter(prefix="/events", tags=["Events"])


@router.post("/listeners", response_model=EventListenerResponse, status_code=201)
async def create_event_listener(request: EventListenerCreateRequest):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter
        from wallethub.modules.events_listener import EventListenerManager

        adapter = ChainAdapter()
        client = adapter.get_client(request.chain)

        manager = EventListenerManager()
        listener = manager.create_listener(
            w3=client.w3,
            chain=request.chain,
            contract_address=request.contract_address,
            event_name=request.event_name,
            event_abi=request.event_abi,
            start_block=request.start_block,
        )

        return EventListenerResponse(
            listener_id=listener.listener_id,
            chain=listener.chain,
            contract_address=listener.contract_address,
            event_name=listener.event_name,
            start_block=listener.current_block,
            current_block=listener.current_block,
            status=listener.status.value,
            callback_url=request.callback_url,
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
        )
    except EventListenerError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.get("/listeners/{listener_id}", response_model=EventListenerResponse)
async def get_event_listener(listener_id: str):
    raise HTTPException(status_code=404, detail="Listener not found")


@router.post("/listeners/{listener_id}/start")
async def start_event_listener(listener_id: str):
    try:
        from wallethub.modules.events_listener import EventListenerManager

        manager = EventListenerManager()
        await manager.start_listener(listener_id)
        return {"message": "Listener started", "listener_id": listener_id}
    except EventListenerError as e:
        raise HTTPException(status_code=404, detail=e.message)


@router.post("/listeners/{listener_id}/stop")
async def stop_event_listener(listener_id: str):
    try:
        from wallethub.modules.events_listener import EventListenerManager

        manager = EventListenerManager()
        await manager.stop_listener(listener_id)
        return {"message": "Listener stopped", "listener_id": listener_id}
    except EventListenerError as e:
        raise HTTPException(status_code=404, detail=e.message)


@router.get("/listeners")
async def list_event_listeners():
    from wallethub.modules.events_listener import EventListenerManager

    manager = EventListenerManager()
    return {"listeners": manager.list_listeners()}
