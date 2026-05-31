from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, Header, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db

from .models import (
    DocumentChunkCreate,
    DocumentPipelineCreate,
    DocumentStatus,
    DocumentTaskCreate,
)
from .service import DocumentPipelineService


router = APIRouter(prefix="/document-pipelines", tags=["文档解析管道"])


@router.post("", response_model=Dict[str, Any], status_code=201)
async def create_pipeline(
    pipeline_data: DocumentPipelineCreate,
    db: AsyncSession = Depends(get_db),
):
    service = DocumentPipelineService(db)
    pipeline = await service.create_pipeline(pipeline_data)
    return {
        "code": 201,
        "data": pipeline.model_dump(),
        "message": "文档管道创建成功",
    }


@router.get("/{pipeline_id}", response_model=Dict[str, Any])
async def get_pipeline(
    pipeline_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = DocumentPipelineService(db)
    pipeline = await service.get_pipeline(pipeline_id, tenant_id)
    return {
        "code": 200,
        "data": pipeline.model_dump(),
        "message": "查询成功",
    }


@router.post("/tasks", response_model=Dict[str, Any], status_code=201)
async def submit_document_task(
    task_data: DocumentTaskCreate,
    db: AsyncSession = Depends(get_db),
):
    service = DocumentPipelineService(db)
    task = await service.submit_document_task(task_data)
    return {
        "code": 201,
        "data": task.model_dump(),
        "message": "文档任务提交成功",
    }


@router.get("/tasks/{task_id}", response_model=Dict[str, Any])
async def get_task(
    task_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = DocumentPipelineService(db)
    task = await service.get_task(task_id, tenant_id)
    return {
        "code": 200,
        "data": task.model_dump(),
        "message": "查询成功",
    }


@router.patch("/tasks/{task_id}/progress", response_model=Dict[str, Any])
async def update_task_progress(
    task_id: str,
    status: DocumentStatus,
    processed_chunks: int,
    total_chunks: int,
    error_message: Optional[str] = None,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = DocumentPipelineService(db)
    task = await service.update_task_progress(
        task_id, status, processed_chunks, total_chunks, error_message, tenant_id
    )
    return {
        "code": 200,
        "data": task.model_dump(),
        "message": "任务进度更新成功",
    }


@router.post("/chunks", response_model=Dict[str, Any], status_code=201)
async def create_chunk(
    chunk_data: DocumentChunkCreate,
    db: AsyncSession = Depends(get_db),
):
    service = DocumentPipelineService(db)
    chunk = await service.create_chunk(chunk_data)
    return {
        "code": 201,
        "data": chunk.model_dump(),
        "message": "文档分块创建成功",
    }


@router.get("/{pipeline_id}/stats", response_model=Dict[str, Any])
async def get_pipeline_stats(
    pipeline_id: str,
    user_agent: str = Header("desktop", alias="User-Agent"),
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = DocumentPipelineService(db)
    stats = await service.get_pipeline_stats(pipeline_id, user_agent, tenant_id)
    return {
        "code": 200,
        "data": stats.model_dump(),
        "message": "查询成功",
    }
