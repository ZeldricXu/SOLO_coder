import os
import uuid
import tempfile
import traceback
import chromadb
from chromadb.config import Settings as ChromaSettings
from langchain_openai import OpenAIEmbeddings
from langchain_chroma import Chroma
from fastapi import APIRouter, UploadFile, File, Depends, HTTPException, Query
from typing import List, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, func
from openai import APIError, APIConnectionError, AuthenticationError, RateLimitError
from app.core.database import get_db
from app.core.config import settings
from app.core.dependencies import get_active_api_config
from app.models.models import Document, APIConfig
from app.models.schemas import DocumentResponse, UploadResponse, CollectionInfo
from app.services.document_parser import DocumentParser

import logging
logger = logging.getLogger(__name__)


router = APIRouter(prefix="/documents", tags=["Documents"])

document_parser = DocumentParser()

ALLOWED_EXTENSIONS = {"pdf", "md", "txt", "markdown"}


def get_file_extension(filename: str) -> str:
    return filename.rsplit(".", 1)[-1].lower() if "." in filename else ""


def get_chroma_client() -> chromadb.PersistentClient:
    os.makedirs(settings.CHROMA_PERSIST_DIR, exist_ok=True)
    return chromadb.PersistentClient(
        path=settings.CHROMA_PERSIST_DIR,
        settings=ChromaSettings(
            anonymized_telemetry=False,
            allow_reset=True
        )
    )


def create_vector_store(
    api_config: APIConfig,
    collection_name: str = "default"
) -> Chroma:
    embeddings = OpenAIEmbeddings(
        model=api_config.embedding_model,
        api_key=api_config.api_key,
        base_url=api_config.base_url
    )
    
    client = get_chroma_client()
    
    return Chroma(
        client=client,
        collection_name=collection_name,
        embedding_function=embeddings,
        persist_directory=settings.CHROMA_PERSIST_DIR
    )


@router.post("/upload", response_model=UploadResponse)
async def upload_document(
    file: UploadFile = File(...),
    collection_name: str = Query(default="default", description="知识库名称"),
    db: AsyncSession = Depends(get_db),
    api_config: APIConfig = Depends(get_active_api_config)
):
    file_extension = get_file_extension(file.filename)
    
    if file_extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail=f"不支持的文件格式。支持的格式：{', '.join(ALLOWED_EXTENSIONS)}"
        )
    
    file_content = await file.read()
    file_size = len(file_content)
    
    temp_file_path = None
    try:
        with tempfile.NamedTemporaryFile(
            delete=False,
            suffix=f".{file_extension}",
            mode="wb"
        ) as temp_file:
            temp_file.write(file_content)
            temp_file_path = temp_file.name
        
        chunks = document_parser.process_file(temp_file_path, file_extension)
        
        document_id = str(uuid.uuid4())
        
        for chunk in chunks:
            chunk.metadata["file_name"] = file.filename
            chunk.metadata["file_type"] = file_extension
            chunk.metadata["document_id"] = document_id
        
        vector_store = create_vector_store(api_config, collection_name)
        vector_store.add_documents(
            documents=chunks,
            ids=[f"{document_id}_{i}" for i in range(len(chunks))]
        )
        
        document = Document(
            id=document_id,
            file_name=file.filename,
            file_type=file_extension,
            file_size=file_size,
            chunk_count=len(chunks),
            collection_name=collection_name
        )
        
        db.add(document)
        await db.commit()
        await db.refresh(document)
        
        return UploadResponse(
            document_id=document_id,
            file_name=file.filename,
            file_type=file_extension,
            chunk_count=len(chunks),
            status="success"
        )
        
    except AuthenticationError as e:
        await db.rollback()
        logger.error(f"API认证失败: {str(e)}")
        raise HTTPException(
            status_code=401,
            detail=f"API认证失败：请检查您的 API Key 是否正确。错误：{str(e)}"
        )
    except RateLimitError as e:
        await db.rollback()
        logger.error(f"API速率限制: {str(e)}")
        raise HTTPException(
            status_code=429,
            detail=f"API请求过于频繁：{str(e)}"
        )
    except APIConnectionError as e:
        await db.rollback()
        logger.error(f"API连接失败: {str(e)}")
        raise HTTPException(
            status_code=502,
            detail=f"无法连接到 API 服务：请检查网络连接或 Base URL 是否正确。错误：{str(e)}"
        )
    except APIError as e:
        await db.rollback()
        logger.error(f"API调用错误: {str(e)}")
        raise HTTPException(
            status_code=500,
            detail=f"API调用失败：{str(e)}"
        )
    except Exception as e:
        await db.rollback()
        error_trace = traceback.format_exc()
        logger.error(f"文件处理失败: {str(e)}\n{error_trace}")
        raise HTTPException(
            status_code=500,
            detail=f"文件处理失败：{str(e)}"
        )
    finally:
        if temp_file_path and os.path.exists(temp_file_path):
            try:
                os.unlink(temp_file_path)
            except Exception as e:
                logger.warning(f"无法删除临时文件: {temp_file_path}, 错误: {str(e)}")


@router.get("/", response_model=List[DocumentResponse])
async def list_documents(
    collection_name: Optional[str] = Query(default=None, description="知识库名称"),
    db: AsyncSession = Depends(get_db)
):
    query = select(Document).order_by(Document.upload_time.desc())
    
    if collection_name:
        query = query.where(Document.collection_name == collection_name)
    
    result = await db.execute(query)
    documents = result.scalars().all()
    
    return [DocumentResponse.model_validate(doc) for doc in documents]


@router.get("/{document_id}", response_model=DocumentResponse)
async def get_document(
    document_id: str,
    db: AsyncSession = Depends(get_db)
):
    result = await db.execute(
        select(Document).where(Document.id == document_id)
    )
    document = result.scalar_one_or_none()
    
    if not document:
        raise HTTPException(status_code=404, detail="文档不存在")
    
    return DocumentResponse.model_validate(document)


@router.delete("/{document_id}")
async def delete_document(
    document_id: str,
    db: AsyncSession = Depends(get_db),
    api_config: APIConfig = Depends(get_active_api_config)
):
    result = await db.execute(
        select(Document).where(Document.id == document_id)
    )
    document = result.scalar_one_or_none()
    
    if not document:
        raise HTTPException(status_code=404, detail="文档不存在")
    
    try:
        client = get_chroma_client()
        try:
            collection = client.get_collection(document.collection_name)
            results = collection.get(
                where={"document_id": document_id}
            )
            if results["ids"]:
                collection.delete(ids=results["ids"])
        except Exception:
            pass
        
        await db.delete(document)
        await db.commit()
        
        return {"message": "文档删除成功", "document_id": document_id}
    except Exception as e:
        await db.rollback()
        raise HTTPException(
            status_code=500,
            detail=f"删除文档失败：{str(e)}"
        )


@router.get("/collections/info", response_model=List[CollectionInfo])
async def get_collections_info(db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(
            Document.collection_name,
            func.count(Document.id).label("document_count"),
            func.sum(Document.chunk_count).label("chunk_count")
        ).group_by(Document.collection_name)
    )
    
    collections = []
    for row in result.all():
        collections.append(
            CollectionInfo(
                name=row.collection_name,
                document_count=row.document_count,
                chunk_count=row.chunk_count or 0
            )
        )
    
    try:
        client = get_chroma_client()
        vector_collections = client.list_collections()
        for vc in vector_collections:
            exists = any(c.name == vc.name for c in collections)
            if not exists:
                collection = client.get_collection(vc.name)
                count = collection.count()
                collections.append(
                    CollectionInfo(
                        name=vc.name,
                        document_count=0,
                        chunk_count=count
                    )
                )
    except Exception:
        pass
    
    return collections


@router.post("/collections/create")
async def create_collection(
    name: str = Query(..., description="知识库名称"),
    db: AsyncSession = Depends(get_db)
):
    if not name or not name.strip():
        raise HTTPException(status_code=400, detail="知识库名称不能为空")
    
    name = name.strip()
    
    if not name.replace("-", "").replace("_", "").isalnum():
        raise HTTPException(
            status_code=400, 
            detail="知识库名称只能包含字母、数字、下划线和连字符"
        )
    
    try:
        client = get_chroma_client()
        client.get_or_create_collection(name)
        
        return {"message": "知识库创建成功", "name": name}
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"创建知识库失败：{str(e)}"
        )


@router.post("/collections/rename")
async def rename_collection(
    old_name: str = Query(..., description="原知识库名称"),
    new_name: str = Query(..., description="新知识库名称"),
    db: AsyncSession = Depends(get_db)
):
    if not new_name or not new_name.strip():
        raise HTTPException(status_code=400, detail="知识库名称不能为空")
    
    new_name = new_name.strip()
    
    if not new_name.replace("-", "").replace("_", "").isalnum():
        raise HTTPException(
            status_code=400, 
            detail="知识库名称只能包含字母、数字、下划线和连字符"
        )
    
    try:
        from sqlalchemy import update
        
        await db.execute(
            update(Document).where(Document.collection_name == old_name).values(collection_name=new_name)
        )
        await db.commit()
        
        return {"message": "知识库重命名成功", "old_name": old_name, "new_name": new_name}
    except Exception as e:
        await db.rollback()
        raise HTTPException(
            status_code=500,
            detail=f"重命名知识库失败：{str(e)}"
        )


@router.delete("/collections/{name}")
async def delete_collection(
    name: str,
    db: AsyncSession = Depends(get_db)
):
    if name == "default":
        raise HTTPException(status_code=400, detail="不能删除默认知识库")
    
    try:
        from sqlalchemy import delete
        
        await db.execute(
            delete(Document).where(Document.collection_name == name)
        )
        
        try:
            client = get_chroma_client()
            client.delete_collection(name)
        except Exception:
            pass
        
        await db.commit()
        
        return {"message": "知识库删除成功", "name": name}
    except Exception as e:
        await db.rollback()
        raise HTTPException(
            status_code=500,
            detail=f"删除知识库失败：{str(e)}"
        )
