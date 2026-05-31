from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query, UploadFile, File, Form
from pydantic import BaseModel

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ..deps import StorageModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/storage", tags=["Decentralized Storage"])


class StoreJsonRequest(BaseModel):
    data: Dict[str, Any]
    name: Optional[str] = None
    description: Optional[str] = None
    storage_network: str = "ipfs"
    pin: bool = True


class PinRequest(BaseModel):
    cid: str
    storage_network: str = "ipfs"
    providers: List[str] = []


@router.post("/store", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def store_content(
    file: UploadFile = File(...),
    name: Optional[str] = Form(None),
    description: Optional[str] = Form(None),
    storage_network: str = Form("ipfs"),
    pin: bool = Form(True),
    storage: StorageModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        content = await file.read()
        result = await storage.store_content(
            content=content,
            content_type=file.content_type or "application/octet-stream",
            name=name or file.filename,
            description=description,
            storage_network=storage_network,
            pin=pin,
        )
        return ResourceResponse(
            code=201,
            message="Content stored successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error storing content: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/store/json", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def store_json(
    request: StoreJsonRequest,
    storage: StorageModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await storage.store_json(
            data=request.data,
            name=request.name,
            description=request.description,
            storage_network=request.storage_network,
            pin=request.pin,
        )
        return ResourceResponse(
            code=201,
            message="JSON stored successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error storing JSON: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/retrieve/{cid}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def retrieve_content(
    cid: str,
    storage_network: str = Query("ipfs"),
    storage: StorageModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        content = await storage.retrieve_content(cid, storage_network)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={
                "cid": cid,
                "storage_network": storage_network,
                "content_base64": content.decode("latin1") if isinstance(content, bytes) else content,
            },
        )
    except Exception as e:
        logger.error(f"Error retrieving content {cid}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/pin", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def pin_content(
    request: PinRequest,
    storage: StorageModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await storage.pin_content(
            cid=request.cid,
            storage_network=request.storage_network,
            providers=request.providers,
        )
        return ResourceResponse(
            code=200,
            message="Content pinned successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error pinning content {request.cid}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/pin/{cid}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def unpin_content(
    cid: str,
    storage_network: str = Query("ipfs"),
    storage: StorageModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        result = await storage.unpin_content(cid, storage_network)
        return ResourceResponse(
            code=200,
            message="Content unpinned successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error unpinning content {cid}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/contents", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_contents(
    storage_network: Optional[str] = None,
    is_pinned: Optional[bool] = None,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    storage: StorageModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        contents = await storage.list_content(
            storage_network=storage_network,
            is_pinned=is_pinned,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"contents": contents, "total": len(contents)},
        )
    except Exception as e:
        logger.error(f"Error listing stored contents: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/contents/{content_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_content(
    content_id: str,
    storage: StorageModuleDep,
    trace_id: TraceIdDep,
):
    try:
        content = await storage.get_content(content_id)
        if not content:
            raise HTTPException(status_code=404, detail="Content not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=content,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting content {content_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/health", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def check_storage_health(
    storage_network: str = Query("ipfs"),
    storage: StorageModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        health = await storage.check_health(storage_network)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=health,
        )
    except Exception as e:
        logger.error(f"Error checking storage health: {e}")
        raise HTTPException(status_code=500, detail=str(e))
