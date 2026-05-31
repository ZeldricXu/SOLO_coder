from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from .models import (
    DocumentCreate,
    DocumentVersionResponse,
    DocumentDiffRequest,
    DocumentDiffResponse,
    ClauseDefinitionCreate,
    ClauseDefinitionResponse,
    DocumentType,
    DiffAlgorithm,
)
from .service import DocumentDiffService

router = APIRouter(prefix="/documents", tags=["文档智能比对"])


@router.post("/versions", response_model=Dict[str, Any], status_code=201)
async def create_document_version(
    doc_data: DocumentCreate,
    db: AsyncSession = Depends(get_db),
):
    service = DocumentDiffService(db)
    version = await service.create_document_version(doc_data)
    return {
        "code": 201,
        "data": version.model_dump(),
        "message": "文档版本创建成功",
    }


@router.get("/versions/{version_id}", response_model=Dict[str, Any])
async def get_document_version(
    version_id: str,
    tenant_id: Optional[str] = Query(None),
    include_content: bool = Query(True),
    db: AsyncSession = Depends(get_db),
):
    service = DocumentDiffService(db)
    version = await service.get_document_version(version_id, tenant_id, include_content)
    return {
        "code": 200,
        "data": version.model_dump(),
        "message": "查询成功",
    }


@router.get("/{document_id}/versions", response_model=Dict[str, Any])
async def list_document_versions(
    document_id: str,
    tenant_id: Optional[str] = Query(None),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = DocumentDiffService(db)
    versions = await service.list_document_versions(document_id, tenant_id, limit, offset)
    return {
        "code": 200,
        "data": [v.model_dump() for v in versions],
        "total": len(versions),
        "message": "查询成功",
    }


@router.post("/compare", response_model=Dict[str, Any])
async def compare_documents(
    diff_request: DocumentDiffRequest,
    db: AsyncSession = Depends(get_db),
):
    service = DocumentDiffService(db)
    result = await service.compare_documents(diff_request)
    return {
        "code": 200,
        "data": result.model_dump(),
        "message": "文档比对完成",
    }


@router.post("/clauses", response_model=Dict[str, Any], status_code=201)
async def create_clause_definition(
    clause_data: ClauseDefinitionCreate,
    db: AsyncSession = Depends(get_db),
):
    service = DocumentDiffService(db)
    clause = await service.create_clause_definition(clause_data)
    return {
        "code": 201,
        "data": clause.model_dump(),
        "message": "关键条款定义创建成功",
    }


@router.get("/clauses", response_model=Dict[str, Any])
async def list_clause_definitions(
    document_type: Optional[DocumentType] = Query(None),
    tenant_id: Optional[str] = Query(None),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = DocumentDiffService(db)
    clauses = await service.list_clause_definitions(document_type, tenant_id, limit, offset)
    return {
        "code": 200,
        "data": [c.model_dump() for c in clauses],
        "total": len(clauses),
        "message": "查询成功",
    }
