from fastapi import APIRouter, HTTPException, UploadFile, File, Query
from typing import List, Optional
import io

from .schemas import (
    DocumentParseRequest,
    DocumentParseResponse,
    ChunkingRequest,
    ChunkingResponse,
    EmbeddingRequest,
    EmbeddingResponse,
    DocumentPipelineRequest,
    DocumentPipelineResponse,
    DocumentInfo,
    DocumentFormat,
    ChunkingStrategy,
)
from .service import document_pipeline_service
from common.schemas import BaseResponse
from common.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/document-pipeline", tags=["文档解析管道"])


@router.post("/parse", response_model=BaseResponse[DocumentParseResponse])
async def parse_document(request: DocumentParseRequest):
    """解析文档"""
    try:
        result = await document_pipeline_service.parse_document(request)
        return BaseResponse(data=result, message="文档解析完成")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to parse document: {str(e)}")
        raise HTTPException(status_code=500, detail=f"解析文档失败: {str(e)}")


@router.post("/parse/upload", response_model=BaseResponse[DocumentParseResponse])
async def parse_uploaded_document(
    file: UploadFile = File(...),
    extract_images: bool = False,
    extract_tables: bool = True,
):
    """上传并解析文档"""
    try:
        content = await file.read()
        request = DocumentParseRequest(
            file_content=content,
            file_name=file.filename or "unknown",
            extract_images=extract_images,
            extract_tables=extract_tables,
        )
        result = await document_pipeline_service.parse_document(request)
        return BaseResponse(data=result, message="文档解析完成")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to parse uploaded document: {str(e)}")
        raise HTTPException(status_code=500, detail=f"解析文档失败: {str(e)}")


@router.post("/chunk", response_model=BaseResponse[ChunkingResponse])
async def chunk_text(request: ChunkingRequest):
    """文本分块"""
    try:
        result = await document_pipeline_service.chunk_text(request)
        return BaseResponse(data=result, message="文本分块完成")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to chunk text: {str(e)}")
        raise HTTPException(status_code=500, detail=f"文本分块失败: {str(e)}")


@router.post("/embed", response_model=BaseResponse[EmbeddingResponse])
async def create_embeddings(request: EmbeddingRequest):
    """创建向量嵌入"""
    try:
        result = await document_pipeline_service.create_embeddings(request)
        return BaseResponse(data=result, message="向量生成完成")
    except Exception as e:
        logger.error(f"Failed to create embeddings: {str(e)}")
        raise HTTPException(status_code=500, detail=f"生成向量失败: {str(e)}")


@router.post("/process", response_model=BaseResponse[DocumentPipelineResponse])
async def process_pipeline(request: DocumentPipelineRequest):
    """执行完整文档处理流水线"""
    try:
        result = await document_pipeline_service.process_pipeline(request)
        return BaseResponse(data=result, message="文档处理完成")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to process pipeline: {str(e)}")
        raise HTTPException(status_code=500, detail=f"文档处理失败: {str(e)}")


@router.post("/process/upload", response_model=BaseResponse[DocumentPipelineResponse])
async def process_uploaded_document(
    file: UploadFile = File(...),
    chunking_strategy: ChunkingStrategy = ChunkingStrategy.RECURSIVE,
    chunk_size: int = 512,
    chunk_overlap: int = 50,
    embedding_model: str = "text-embedding-ada-002",
    extract_images: bool = False,
    extract_tables: bool = True,
):
    """上传并执行完整文档处理流水线"""
    try:
        content = await file.read()
        request = DocumentPipelineRequest(
            file_content=content,
            file_name=file.filename or "unknown",
            chunking_strategy=chunking_strategy,
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            embedding_model=embedding_model,
            extract_images=extract_images,
            extract_tables=extract_tables,
        )
        result = await document_pipeline_service.process_pipeline(request)
        return BaseResponse(data=result, message="文档处理完成")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to process uploaded document: {str(e)}")
        raise HTTPException(status_code=500, detail=f"文档处理失败: {str(e)}")


@router.get("/documents/{doc_id}", response_model=BaseResponse[DocumentInfo])
async def get_document(doc_id: str):
    """获取文档信息"""
    try:
        result = document_pipeline_service.get_document_info(doc_id)
        return BaseResponse(data=result, message="获取成功")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to get document: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取文档信息失败: {str(e)}")


@router.get("/documents", response_model=BaseResponse[List[DocumentInfo]])
async def list_documents(
    limit: int = Query(default=100, ge=1, le=1000),
):
    """列出已处理的文档"""
    try:
        result = document_pipeline_service.list_documents(limit)
        return BaseResponse(data=result, message="获取成功")
    except Exception as e:
        logger.error(f"Failed to list documents: {str(e)}")
        raise HTTPException(status_code=500, detail=f"获取文档列表失败: {str(e)}")


@router.get("/formats", response_model=BaseResponse[List[str]])
async def list_supported_formats():
    """列出支持的文档格式"""
    formats = [f.value for f in DocumentFormat]
    return BaseResponse(data=formats, message="获取成功")


@router.get("/chunking-strategies", response_model=BaseResponse[List[str]])
async def list_chunking_strategies():
    """列出支持的分块策略"""
    strategies = [s.value for s in ChunkingStrategy]
    return BaseResponse(data=strategies, message="获取成功")
