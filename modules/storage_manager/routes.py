from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, File, Form, UploadFile, HTTPException
from fastapi.responses import StreamingResponse

from models import ResponseModel, PaginatedResponse
from .service import storage_manager

router = APIRouter(prefix="/api/v1/storage", tags=["Storage Manager"])


@router.post("/upload", response_model=ResponseModel[Dict[str, Any]])
async def upload_file(
    file: UploadFile = File(...),
    directory: str = Form(""),
    backend: Optional[str] = Form(None),
):
    content = await file.read()
    result = await storage_manager.upload_file(
        filename=file.filename or "unnamed",
        data=content,
        directory=directory,
        backend=backend,
    )
    return ResponseModel(data=result)


@router.get("/files/{path:path}")
async def download_file(
    path: str,
    backend: Optional[str] = None,
):
    try:
        content = await storage_manager.download_file(path, backend)
        metadata = await storage_manager.get_file_metadata(path, backend)

        return StreamingResponse(
            iter([content]),
            media_type=metadata.get("mime_type", "application/octet-stream"),
            headers={
                "Content-Disposition": f"attachment; filename={path.split('/')[-1]}"
            },
        )
    except Exception as e:
        raise HTTPException(status_code=404, detail=str(e))


@router.delete("/files/{path:path}", response_model=ResponseModel)
async def delete_file(
    path: str,
    backend: Optional[str] = None,
):
    success = await storage_manager.delete_file(path, backend)
    return ResponseModel(data={"deleted": success})


@router.get("/files/{path:path}/metadata", response_model=ResponseModel[Dict[str, Any]])
async def get_file_metadata(
    path: str,
    backend: Optional[str] = None,
):
    metadata = await storage_manager.get_file_metadata(path, backend)
    return ResponseModel(data=metadata)


@router.get("/files/{path:path}/url", response_model=ResponseModel[str])
async def get_file_url(
    path: str,
    backend: Optional[str] = None,
    expires_in: int = 3600,
):
    url = await storage_manager.get_file_url(path, backend, expires_in)
    return ResponseModel(data=url)


@router.get("/files", response_model=PaginatedResponse[Dict[str, Any]])
async def list_files(
    page: int = 1,
    page_size: int = 50,
    prefix: str = "",
    backend: Optional[str] = None,
    recursive: bool = True,
):
    files = await storage_manager.list_files(prefix, backend, recursive)
    start = (page - 1) * page_size
    end = start + page_size
    return PaginatedResponse(
        data=files[start:end],
        total=len(files),
        page=page,
        page_size=page_size,
    )


@router.get("/stats", response_model=ResponseModel[Dict[str, Any]])
async def get_storage_stats(
    backend: Optional[str] = None,
):
    stats = await storage_manager.get_storage_stats(backend)
    return ResponseModel(data=stats)


@router.post("/cleanup", response_model=ResponseModel[Dict[str, Any]])
async def cleanup_old_files(
    max_age_days: int = 30,
    prefix: str = "",
    backend: Optional[str] = None,
):
    deleted = await storage_manager.cleanup_old_files(max_age_days, prefix, backend)
    return ResponseModel(data={"deleted_count": deleted})
