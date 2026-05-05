import uuid
from datetime import datetime
from typing import Optional
from fastapi import APIRouter, UploadFile, File, Form, HTTPException, Depends, BackgroundTasks, Request
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from app.core.database import get_db
from app.models.models import UploadTask
from app.models.schemas import (
    UploadResponse,
    TaskStatusResponse,
    ErrorResponse
)
from app.core.di_provider import (
    get_document_service,
    DocumentService,
    get_file_extractor,
    FileExtractor
)
from app.core.background_tasks import submit_log_task

router = APIRouter()


async def process_upload_task_v2(
    task_id: str,
    file_content: bytes,
    file_name: str,
    collection_name: str,
    db: AsyncSession,
    document_service: DocumentService
):
    try:
        result = await db.execute(
            select(UploadTask).where(UploadTask.id == task_id)
        )
        task = result.scalar_one_or_none()
        if not task:
            return

        task.status = "processing"
        await db.commit()

        processing_result = await document_service.process_and_store(
            file_content=file_content,
            filename=file_name,
            collection_name=collection_name
        )

        task.status = "completed"
        task.total_chunks = processing_result.total_chunks
        task.processed_chunks = processing_result.success_chunks
        task.completed_at = datetime.utcnow()
        await db.commit()

    except Exception as e:
        result = await db.execute(
            select(UploadTask).where(UploadTask.id == task_id)
        )
        task = result.scalar_one_or_none()
        if task:
            task.status = "failed"
            task.error_message = str(e)
            await db.commit()


@router.post(
    "/upload",
    response_model=UploadResponse,
    responses={
        400: {"model": ErrorResponse},
        500: {"model": ErrorResponse}
    }
)
async def upload_file(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    collection_name: str = Form(...),
    db: AsyncSession = Depends(get_db),
    file_extractor: FileExtractor = Depends(get_file_extractor),
    document_service: DocumentService = Depends(get_document_service)
):
    if not file_extractor.validate_file_type(file.filename):
        allowed_types = ", ".join(file_extractor._allowed_extensions)
        raise HTTPException(
            status_code=400,
            detail=f"不支持的文件类型。允许的类型: {allowed_types}"
        )

    file_content = await file.read()
    
    if not file_extractor.validate_file_size(file_content):
        max_size_mb = file_extractor._max_file_size / (1024 * 1024)
        raise HTTPException(
            status_code=400,
            detail=f"文件大小超出限制。最大允许: {max_size_mb} MB"
        )

    task_id = str(uuid.uuid4())
    
    task = UploadTask(
        id=task_id,
        file_name=file.filename,
        collection_name=collection_name,
        status="pending"
    )
    db.add(task)
    await db.commit()

    background_tasks.add_task(
        process_upload_task_v2,
        task_id,
        file_content,
        file.filename,
        collection_name,
        db,
        document_service
    )

    return UploadResponse(
        task_id=task_id,
        status="pending",
        message=f"文件已上传，正在后台处理。任务ID: {task_id}"
    )


@router.get(
    "/task/{task_id}",
    response_model=TaskStatusResponse,
    responses={404: {"model": ErrorResponse}}
)
async def get_task_status(
    task_id: str,
    db: AsyncSession = Depends(get_db)
):
    result = await db.execute(
        select(UploadTask).where(UploadTask.id == task_id)
    )
    task = result.scalar_one_or_none()

    if not task:
        raise HTTPException(
            status_code=404,
            detail=f"任务不存在: {task_id}"
        )

    return TaskStatusResponse(
        task_id=task.id,
        status=task.status,
        file_name=task.file_name,
        collection_name=task.collection_name,
        total_chunks=task.total_chunks,
        processed_chunks=task.processed_chunks,
        error_message=task.error_message
    )
