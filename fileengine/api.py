from typing import Optional, List
from pathlib import Path
from fastapi import (
    FastAPI,
    APIRouter,
    UploadFile,
    File,
    Form,
    HTTPException,
    status,
    Query,
    Request,
)
from fastapi.responses import FileResponse, StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from .config import settings
from .logger import logger
from .upload import upload_manager
from .download import download_manager
from .converter import converter
from .parser import parser
from .compressor import compressor
from .storage import storage
from .metadata import metadata
from .task_queue import task_queue
from .cleanup import cleanup_manager
from .async_upload import async_upload
from .models import (
    ConvertRequest,
    ParseRequest,
    CompressRequest,
    ApiResponse,
    FileInfo,
)


router = APIRouter(prefix="/api/v1")


def create_app() -> FastAPI:
    app = FastAPI(
        title="FileEngine 文件处理服务",
        description="一个功能丰富的文件处理平台，支持上传、转换、解析、压缩解压等操作",
        version=settings.app_version,
        openapi_url="/openapi.json",
        docs_url="/docs",
        redoc_url="/redoc",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(router)

    @app.on_event("startup")
    async def startup_event():
        task_queue.start_workers(num_workers=2)

        if settings.enable_async_upload:
            async_upload.start_workers()

        if settings.enable_scheduled_cleanup:
            cleanup_manager.start_scheduler()

        from .redis_queue import redis_queue
        redis_available = redis_queue.is_available()

        logger.info(
            f"FileEngine API server started. Version: {settings.app_version}, "
            f"Redis queue: {'enabled' if redis_available else 'disabled'}, "
            f"Async upload: {'enabled' if settings.enable_async_upload else 'disabled'}, "
            f"Scheduled cleanup: {'enabled' if settings.enable_scheduled_cleanup else 'disabled'}"
        )

    @app.on_event("shutdown")
    async def shutdown_event():
        task_queue.stop_workers()

        if settings.enable_async_upload:
            async_upload.stop_workers()

        if settings.enable_scheduled_cleanup:
            cleanup_manager.stop_scheduler()

        logger.info("FileEngine API server stopped")

    return app


@router.post("/files/upload", response_model=ApiResponse)
async def upload_file(
    file: UploadFile = File(...),
    user_id: str = Form(default="anonymous"),
):
    try:
        file_data = await file.read()
        mime_type = file.content_type

        success, file_info, message = upload_manager.upload_file(
            file_data=file_data,
            filename=file.filename or "unknown",
            upload_user=user_id,
            mime_type=mime_type,
        )

        if success and file_info:
            return ApiResponse(
                code=200,
                message=message,
                data={
                    "file_id": file_info.file_id,
                    "upload_status": "completed",
                    "file_name": file_info.file_name,
                    "file_size": file_info.file_size,
                    "file_type": file_info.file_type,
                },
            )
        else:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=message,
            )
    except Exception as e:
        logger.error(f"Upload error: {str(e)}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Upload failed: {str(e)}",
        )


@router.post("/files/upload/init", response_model=ApiResponse)
async def init_chunk_upload(
    file_name: str = Form(...),
    total_size: int = Form(...),
    user_id: str = Form(default="anonymous"),
):
    result = upload_manager.init_chunk_upload(
        file_name=file_name,
        total_size=total_size,
        upload_user=user_id,
    )

    if result.get("success"):
        return ApiResponse(
            code=200,
            message=result.get("message", ""),
            data={
                "session_id": result.get("session_id"),
                "total_chunks": result.get("total_chunks"),
                "chunk_size": result.get("chunk_size"),
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=result.get("message", ""),
        )


@router.post("/files/upload/chunk", response_model=ApiResponse)
async def upload_chunk(
    session_id: str = Form(...),
    chunk_index: int = Form(...),
    chunk: UploadFile = File(...),
):
    chunk_data = await chunk.read()

    result = upload_manager.upload_chunk(
        session_id=session_id,
        chunk_index=chunk_index,
        chunk_data=chunk_data,
    )

    return ApiResponse(
        code=200,
        message=result.get("message", ""),
        data={
            "progress": result.get("progress", 0),
            "is_complete": result.get("is_complete", False),
        },
    )


@router.post("/files/upload/complete", response_model=ApiResponse)
async def complete_upload(session_id: str = Form(...)):
    result = upload_manager.complete_chunk_upload(session_id)

    if result.get("success"):
        return ApiResponse(
            code=200,
            message=result.get("message", ""),
            data={
                "file_id": result.get("file_id"),
                "file_name": result.get("file_name"),
                "file_size": result.get("file_size"),
                "upload_status": result.get("upload_status"),
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=result.get("message", ""),
        )


@router.get("/files/upload/progress", response_model=ApiResponse)
async def get_upload_progress(session_id: str = Query(...)):
    result = upload_manager.get_upload_progress(session_id)

    if result.get("success"):
        return ApiResponse(
            code=200,
            message="success",
            data=result,
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=result.get("message", ""),
        )


@router.post("/files/convert", response_model=ApiResponse)
async def convert_file(request: ConvertRequest):
    success, task, message = converter.create_convert_task(
        file_id=request.file_id,
        target_format=request.target_format,
        conversion_params=request.conversion_params,
        user_id=request.user_id,
    )

    if success and task:
        task_queue.add_task(
            task_type="convert",
            task_id=task.task_id,
            priority=5,
        )

        return ApiResponse(
            code=200,
            message=message,
            data={
                "task_id": task.task_id,
                "status": "processing",
                "source_file_id": task.source_file_id,
                "target_format": task.target_format,
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=message,
        )


@router.get("/files/convert/status", response_model=ApiResponse)
async def get_convert_status(task_id: str = Query(...)):
    task_status = converter.get_task_status(task_id)

    if task_status:
        return ApiResponse(
            code=200,
            message="success",
            data=task_status,
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Task not found",
        )


@router.post("/files/parse", response_model=ApiResponse)
async def parse_file(request: ParseRequest):
    file_info = metadata.get_file(request.file_id)
    if not file_info:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"File not found: {request.file_id}",
        )

    task_queue.add_task(
        task_type="parse",
        task_id=f"parse_{request.file_id}_{request.parse_type}",
        priority=5,
        extra_args={
            "file_id": request.file_id,
            "parse_type": request.parse_type,
            "params": request.parse_params,
        },
    )

    success, result, message = parser.parse(
        file_id=request.file_id,
        parse_type=request.parse_type,
        params=request.parse_params,
    )

    if success and result:
        return ApiResponse(
            code=200,
            message=message,
            data={
                "parse_id": result.parse_id,
                "file_id": result.file_id,
                "parse_type": result.parse_type,
                "parse_result": result.parse_result,
                "parse_status": result.parse_status,
                "parse_time": result.parse_time,
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=message,
        )


@router.post("/files/compress", response_model=ApiResponse)
async def compress_files(request: CompressRequest):
    task_queue.add_task(
        task_type="compress",
        task_id=f"compress_{'_'.join(request.file_ids)}",
        priority=5,
        extra_args={
            "file_ids": request.file_ids,
            "compress_format": request.compress_format,
            "params": request.compression_params,
        },
    )

    success, task, message = compressor.compress(
        file_ids=request.file_ids,
        compress_format=request.compress_format,
        params=request.compression_params,
    )

    if success and task:
        return ApiResponse(
            code=200,
            message=message,
            data={
                "compress_id": task.compress_id,
                "result_file_id": task.result_file_id,
                "compress_status": task.compress_status,
                "compress_format": task.compress_format,
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=message,
        )


@router.post("/files/extract", response_model=ApiResponse)
async def extract_archive(
    file_id: str = Form(...),
    user_id: str = Form(default="anonymous"),
):
    success, extracted_files, message = compressor.extract(
        file_id=file_id,
        user_id=user_id,
    )

    if success:
        return ApiResponse(
            code=200,
            message=message,
            data={
                "extracted_file_ids": extracted_files or [],
                "count": len(extracted_files) if extracted_files else 0,
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=message,
        )


@router.get("/files/download")
async def download_file(file_id: str = Query(...)):
    success, file_path, file_info, message = download_manager.get_file_for_download(file_id)

    if not success or not file_path or not file_info:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=message,
        )

    return FileResponse(
        path=str(file_path),
        filename=file_info.file_name,
        media_type=download_manager._guess_mime_type(file_path),
    )


@router.get("/files", response_model=ApiResponse)
async def list_files(user_id: Optional[str] = Query(None)):
    files = upload_manager.list_files(user_id)
    return ApiResponse(
        code=200,
        message="success",
        data={
            "files": files,
            "count": len(files),
        },
    )


@router.get("/files/{file_id}", response_model=ApiResponse)
async def get_file_info(file_id: str):
    file_info = metadata.get_file(file_id)
    if not file_info:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="File not found",
        )

    return ApiResponse(
        code=200,
        message="success",
        data=file_info.model_dump(),
    )


@router.delete("/files/{file_id}", response_model=ApiResponse)
async def delete_file(file_id: str):
    success = upload_manager.delete_file(file_id)
    if success:
        return ApiResponse(
            code=200,
            message="File deleted successfully",
            data={"file_id": file_id},
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="File not found",
        )


@router.get("/logs", response_model=ApiResponse)
async def get_logs(
    task_id: Optional[str] = Query(None),
    file_id: Optional[str] = Query(None),
    limit: int = Query(100, ge=1, le=1000),
):
    logs = logger.get_logs(task_id=task_id, file_id=file_id, limit=limit)
    return ApiResponse(
        code=200,
        message="success",
        data={
            "logs": logs,
            "count": len(logs),
        },
    )


@router.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "service": "FileEngine",
        "version": settings.app_version,
        "queue_size": task_queue.get_queue_size(),
    }


@router.get("/tasks/queue-size", response_model=ApiResponse)
async def get_queue_size():
    return ApiResponse(
        code=200,
        message="success",
        data={"queue_size": task_queue.get_queue_size()},
    )


@router.post("/storage/cleanup-expired", response_model=ApiResponse)
async def cleanup_expired():
    deleted_count = storage.cleanup_expired()
    return ApiResponse(
        code=200,
        message="Cleanup completed",
        data={"deleted_count": deleted_count},
    )


@router.post("/files/upload/async/init", response_model=ApiResponse)
async def init_async_upload(
    file_name: str = Form(...),
    total_size: int = Form(...),
    user_id: str = Form(default="anonymous"),
):
    result = upload_manager.init_async_upload(
        file_name=file_name,
        total_size=total_size,
        user_id=user_id,
    )

    if result.get("success"):
        return ApiResponse(
            code=200,
            message=result.get("message", ""),
            data={
                "upload_task_id": result.get("upload_task_id"),
                "session_id": result.get("session_id"),
                "total_chunks": result.get("total_chunks"),
                "chunk_size": result.get("chunk_size"),
                "total_size": result.get("total_size"),
                "status": result.get("status"),
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=result.get("message", ""),
        )


@router.post("/files/upload/async/chunk", response_model=ApiResponse)
async def upload_chunk_async(
    upload_task_id: str = Form(...),
    chunk_index: int = Form(...),
    chunk: UploadFile = File(...),
):
    chunk_data = await chunk.read()

    result = upload_manager.upload_chunk_async(
        upload_task_id=upload_task_id,
        chunk_index=chunk_index,
        chunk_data=chunk_data,
    )

    if result.get("success"):
        return ApiResponse(
            code=200,
            message=result.get("message", ""),
            data={
                "progress": result.get("progress", 0),
                "chunks_received": result.get("chunks_received"),
                "total_chunks": result.get("total_chunks"),
                "is_complete": result.get("is_complete", False),
            },
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=result.get("message", ""),
        )


@router.get("/files/upload/async/status", response_model=ApiResponse)
async def get_async_upload_status(upload_task_id: str = Query(...)):
    status_result = upload_manager.get_async_upload_status(upload_task_id)

    if status_result:
        return ApiResponse(
            code=200,
            message="success",
            data=status_result,
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Upload task not found",
        )


@router.get("/files/upload/async/list", response_model=ApiResponse)
async def list_async_uploads(
    status_filter: Optional[str] = Query(None),
):
    uploads = upload_manager.list_async_uploads(status=status_filter)
    return ApiResponse(
        code=200,
        message="success",
        data={
            "uploads": uploads,
            "count": len(uploads),
        },
    )


@router.post("/files/upload/async/cancel", response_model=ApiResponse)
async def cancel_async_upload(upload_task_id: str = Form(...)):
    success = upload_manager.cancel_async_upload(upload_task_id)

    if success:
        return ApiResponse(
            code=200,
            message="Upload cancelled",
            data={"upload_task_id": upload_task_id},
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Failed to cancel upload or upload not found",
        )


@router.get("/conversion/profiles", response_model=ApiResponse)
async def list_conversion_profiles():
    profiles = converter.list_conversion_profiles()
    return ApiResponse(
        code=200,
        message="success",
        data={
            "profiles": profiles,
            "count": len(profiles),
        },
    )


@router.get("/conversion/profiles/{profile_name}", response_model=ApiResponse)
async def get_conversion_profile(profile_name: str):
    profile = converter.get_profile_details(profile_name)

    if profile:
        return ApiResponse(
            code=200,
            message="success",
            data={"profile_name": profile_name, "config": profile},
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Profile not found",
        )


@router.get("/cleanup/status", response_model=ApiResponse)
async def get_cleanup_status():
    status_info = cleanup_manager.get_cleanup_status()
    return ApiResponse(
        code=200,
        message="success",
        data=status_info,
    )


@router.post("/cleanup/run", response_model=ApiResponse)
async def run_cleanup():
    result = cleanup_manager.run_cleanup()
    return ApiResponse(
        code=200,
        message="Cleanup executed",
        data=result,
    )


@router.post("/cleanup/run/{file_type}", response_model=ApiResponse)
async def run_cleanup_for_type(file_type: str):
    result = cleanup_manager.run_cleanup_for_type(file_type)

    if result.get("success"):
        return ApiResponse(
            code=200,
            message=f"Cleanup for {file_type} executed",
            data=result,
        )
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=result.get("message", ""),
        )


@router.get("/system/health", response_model=ApiResponse)
async def system_health_check():
    from .redis_queue import redis_queue

    return ApiResponse(
        code=200,
        message="healthy",
        data={
            "status": "healthy",
            "service": "FileEngine",
            "version": settings.app_version,
            "queue_size": task_queue.get_queue_size(),
            "redis_available": task_queue.is_redis_available(),
            "async_upload_enabled": settings.enable_async_upload,
            "scheduled_cleanup_enabled": settings.enable_scheduled_cleanup,
        },
    )


@router.get("/system/config", response_model=ApiResponse)
async def get_system_config():
    return ApiResponse(
        code=200,
        message="success",
        data={
            "app_name": settings.app_name,
            "app_version": settings.app_version,
            "max_file_size": settings.max_file_size,
            "chunk_size": settings.chunk_size,
            "file_expire_days": settings.file_expire_days,
            "enable_redis_queue": settings.enable_redis_queue,
            "enable_async_upload": settings.enable_async_upload,
            "async_upload_worker_count": settings.async_upload_worker_count,
            "enable_scheduled_cleanup": settings.enable_scheduled_cleanup,
            "cleanup_check_interval_seconds": settings.cleanup_check_interval_seconds,
        },
    )
