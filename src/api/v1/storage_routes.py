from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile

from src.shared.container import Container, container
from src.shared.types import (
    APIResponse,
    HexString,
    StoredContent,
)

router = APIRouter(prefix="/storage", tags=["storage"])


async def get_container() -> Container:
    return container


@router.get("/networks", response_model=APIResponse[List[str]])
async def list_storage_networks(
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        return APIResponse.success(data=storage.list_networks())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/upload", response_model=APIResponse[StoredContent])
async def upload_data(
    data: str = Form(...),
    network: Optional[str] = None,
    metadata: Optional[str] = Form(None),
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        metadata_dict = {}
        if metadata:
            import json
            metadata_dict = json.loads(metadata)
        content = await storage.upload(
            data=data,
            network=network,
            metadata=metadata_dict,
        )
        return APIResponse.success(data=content)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/upload/file", response_model=APIResponse[StoredContent])
async def upload_file(
    file: UploadFile = File(...),
    network: Optional[str] = None,
    metadata: Optional[str] = Form(None),
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter(network)

        file_data = await file.read()
        metadata_dict = {"original_filename": file.filename}
        if metadata:
            import json
            metadata_dict.update(json.loads(metadata))

        content = await adapter.upload_file(
            file_path=file.filename or "uploaded_file",
            metadata=metadata_dict,
        )
        return APIResponse.success(data=content)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/download/{cid}", response_model=APIResponse[HexString])
async def download_data(
    cid: str,
    network: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        data = await storage.download(cid, network)
        return APIResponse.success(data="0x" + data.hex())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/pin/{cid}", response_model=APIResponse[bool])
async def pin_content(
    cid: str,
    network: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        success = await storage.pin(cid, network)
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/pin/{cid}", response_model=APIResponse[bool])
async def unpin_content(
    cid: str,
    network: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        success = await storage.unpin(cid, network)
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/pin/{cid}", response_model=APIResponse[bool])
async def check_pinned(
    cid: str,
    network: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter(network)
        pinned = await adapter.is_pinned(cid)
        return APIResponse.success(data=pinned)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/pins", response_model=APIResponse[List[str]])
async def list_pinned_content(
    network: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter(network)
        pins = await adapter.list_pinned(limit, offset)
        return APIResponse.success(data=pins)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/gateway/{cid}", response_model=APIResponse[str])
async def get_gateway_url(
    cid: str,
    network: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter(network)
        url = await adapter.get_gateway_url(cid)
        return APIResponse.success(data=url)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/size/{cid}", response_model=APIResponse[int])
async def get_content_size(
    cid: str,
    network: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter(network)
        size = await adapter.get_content_size(cid)
        return APIResponse.success(data=size)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/ipfs/dag", response_model=APIResponse[str])
async def ipfs_dag_put(
    data: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter("ipfs")
        cid = await adapter.dag_put(data)
        return APIResponse.success(data=cid)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/ipfs/dag/{cid}", response_model=APIResponse[Dict[str, Any]])
async def ipfs_dag_get(
    cid: str,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter("ipfs")
        data = await adapter.dag_get(cid)
        return APIResponse.success(data=data)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/ipfs/ipns", response_model=APIResponse[str])
async def ipfs_add_ipns(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter("ipfs")
        name = await adapter.add_ipns(
            cid=request["cid"],
            key_name=request.get("key_name"),
        )
        return APIResponse.success(data=name)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/ipfs/ipns/{name}", response_model=APIResponse[str])
async def ipfs_resolve_ipns(
    name: str,
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter("ipfs")
        path = await adapter.resolve_ipns(name)
        return APIResponse.success(data=path)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/ipfs/pubsub", response_model=APIResponse[bool])
async def ipfs_pubsub_publish(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        storage = container.storage_service
        adapter = storage.get_adapter("ipfs")
        data = request["data"].encode() if isinstance(request["data"], str) else request["data"]
        success = await adapter.pubsub_publish(
            topic=request["topic"],
            data=data,
        )
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
