from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from datetime import datetime, timezone
from typing import Optional

from wallethub.api.models.storage_models import (
    StorageUploadRequest,
    StorageResponse,
    PinRequest,
)
from wallethub.core import StorageError, StorageNetwork

router = APIRouter(prefix="/storage", tags=["Storage"])


@router.post("/upload", response_model=StorageResponse, status_code=201)
async def upload_to_storage(request: StorageUploadRequest):
    try:
        from wallethub.modules.storage import StorageManager

        manager = StorageManager()
        content = await manager.store(
            data=request.data,
            network=StorageNetwork(request.network),
            pin=request.pin,
            metadata=request.metadata,
        )

        return StorageResponse(
            content_id=content.content_id,
            network=content.network.value,
            cid=content.cid,
            content_hash=content.content_hash,
            content_type=content.content_type,
            size=content.size,
            pinned=content.pinned,
            url=content.url,
            created_at=content.created_at,
        )
    except StorageError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.post("/upload/file", response_model=StorageResponse, status_code=201)
async def upload_file_to_storage(
    file: UploadFile = File(...),
    network: str = Form("ipfs"),
    pin: bool = Form(True),
):
    try:
        from wallethub.modules.storage import StorageManager

        content = await file.read()

        manager = StorageManager()
        result = await manager.store(
            data=content,
            network=StorageNetwork(network),
            pin=pin,
            metadata={"filename": file.filename, "content_type": file.content_type},
        )

        return StorageResponse(
            content_id=result.content_id,
            network=result.network.value,
            cid=result.cid,
            content_hash=result.content_hash,
            content_type=file.content_type or "application/octet-stream",
            size=result.size,
            pinned=result.pinned,
            url=result.url,
            created_at=result.created_at,
        )
    except StorageError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.get("/{cid}")
async def get_from_storage(cid: str, network: str = "ipfs"):
    try:
        from wallethub.modules.storage import StorageManager

        manager = StorageManager()
        content = await manager.retrieve(cid, StorageNetwork(network))

        return {"cid": cid, "network": network, "content": content.decode("utf-8", errors="replace")}
    except StorageError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.post("/pin", response_model=StorageResponse)
async def pin_content(request: PinRequest):
    try:
        from wallethub.modules.storage import StorageManager

        manager = StorageManager()
        await manager.pin(request.cid, StorageNetwork(request.network))

        return StorageResponse(
            content_id="",
            network=request.network,
            cid=request.cid,
            content_hash="",
            content_type="",
            size=0,
            pinned=True,
            url=manager.get_url(request.cid, StorageNetwork(request.network)),
            created_at=datetime.now(timezone.utc),
        )
    except StorageError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.get("/{cid}/url")
async def get_storage_url(cid: str, network: str = "ipfs"):
    from wallethub.modules.storage import StorageManager

    manager = StorageManager()
    url = manager.get_url(cid, StorageNetwork(network))
    return {"cid": cid, "network": network, "url": url}
