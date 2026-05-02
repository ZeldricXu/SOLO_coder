from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from typing import List, Dict, Any, AsyncGenerator
import json
from langchain_openai import OpenAIEmbeddings, ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from langchain_core.documents import Document as LangchainDocument
from langchain_chroma import Chroma
import chromadb
from chromadb.config import Settings as ChromaSettings
import os
from app.core.config import settings
from app.core.dependencies import get_active_api_config
from app.models.models import APIConfig
from app.models.schemas import QueryRequest, QueryResponse, SourceNode


router = APIRouter(prefix="/query", tags=["Query"])


def get_chroma_client() -> chromadb.PersistentClient:
    os.makedirs(settings.CHROMA_PERSIST_DIR, exist_ok=True)
    return chromadb.PersistentClient(
        path=settings.CHROMA_PERSIST_DIR,
        settings=ChromaSettings(
            anonymized_telemetry=False,
            allow_reset=True
        )
    )


def create_services(api_config: APIConfig, collection_name: str = "default"):
    embeddings = OpenAIEmbeddings(
        model=api_config.embedding_model,
        api_key=api_config.api_key,
        base_url=api_config.base_url
    )
    
    llm = ChatOpenAI(
        model=api_config.model,
        api_key=api_config.api_key,
        base_url=api_config.base_url,
        temperature=0.7,
        streaming=True
    )
    
    client = get_chroma_client()
    
    vector_store = Chroma(
        client=client,
        collection_name=collection_name,
        embedding_function=embeddings,
        persist_directory=settings.CHROMA_PERSIST_DIR
    )
    
    system_prompt = """你是一个专业的知识助手。请根据以下提供的上下文信息，准确、全面地回答用户的问题。

重要规则：
1. 只使用上下文中提供的信息来回答问题
2. 如果上下文中没有相关信息，请明确说明"未在知识库中找到相关信息"
3. 回答要简洁、准确，避免编造内容
4. 如果上下文信息不足，可以说明"现有信息不足以完整回答该问题"

上下文信息：
{context}

用户问题：
{question}

请基于上述上下文回答问题："""
    
    prompt = ChatPromptTemplate.from_template(system_prompt)
    chain = prompt | llm | StrOutputParser()
    
    return vector_store, chain


def similarity_search(
    vector_store: Chroma,
    query: str,
    top_k: int = 5
) -> List[LangchainDocument]:
    results = vector_store.similarity_search_with_score(
        query,
        k=top_k
    )
    
    documents_with_scores = []
    for doc, score in results:
        doc.metadata["score"] = score
        documents_with_scores.append(doc)
    
    return documents_with_scores


def format_context(documents: List[LangchainDocument]) -> str:
    context_parts = []
    for i, doc in enumerate(documents):
        source = doc.metadata.get("file_name", f"文档_{i}")
        chunk_idx = doc.metadata.get("chunk_index", i)
        score = doc.metadata.get("score", 0)
        
        context_parts.append(
            f"【来源：{source} | 片段：{chunk_idx} | 相关度：{score:.3f}】\n{doc.page_content}\n"
        )
    
    return "\n---\n".join(context_parts)


def build_source_nodes(documents: List[LangchainDocument]) -> List[SourceNode]:
    sources = []
    for doc in documents:
        source = SourceNode(
            document_id=doc.metadata.get("document_id", ""),
            file_name=doc.metadata.get("file_name", "未知文档"),
            content=doc.page_content[:200] + "..." if len(doc.page_content) > 200 else doc.page_content,
            score=doc.metadata.get("score", 0),
            chunk_index=doc.metadata.get("chunk_index")
        )
        sources.append(source)
    return sources


@router.post("/")
async def query_knowledge_base(
    request: QueryRequest,
    api_config: APIConfig = Depends(get_active_api_config)
):
    try:
        vector_store, chain = create_services(
            api_config, 
            request.knowledge_base_id
        )
        
        retrieved_docs = similarity_search(
            vector_store,
            request.query,
            request.top_k
        )
        
        if not retrieved_docs:
            if request.stream:
                return StreamingResponse(
                    empty_response_generator(),
                    media_type="text/event-stream"
                )
            else:
                return QueryResponse(
                    answer="未在知识库中找到相关信息",
                    sources=[]
                )
        
        if request.stream:
            return StreamingResponse(
                stream_response_generator(
                    chain,
                    retrieved_docs,
                    request.query
                ),
                media_type="text/event-stream"
            )
        else:
            context = format_context(retrieved_docs)
            sources = build_source_nodes(retrieved_docs)
            
            answer = await chain.ainvoke({
                "context": context,
                "question": request.query
            })
            
            return QueryResponse(
                answer=answer,
                sources=sources
            )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"查询失败：{str(e)}"
        )


async def empty_response_generator() -> AsyncGenerator[str, None]:
    yield f"data: {json.dumps({'answer': '未在知识库中找到相关信息'}, ensure_ascii=False)}\n\n"
    yield f"data: {json.dumps({'sources': []}, ensure_ascii=False)}\n\n"
    yield "data: [DONE]\n\n"


async def stream_response_generator(
    chain,
    retrieved_docs: List[LangchainDocument],
    query: str
) -> AsyncGenerator[str, None]:
    context = format_context(retrieved_docs)
    sources = build_source_nodes(retrieved_docs)
    
    index = 0
    async for chunk in chain.astream({
        "context": context,
        "question": query
    }):
        yield f"data: {json.dumps({'token': chunk, 'index': index}, ensure_ascii=False)}\n\n"
        index += 1
    
    sources_dict = [s.model_dump() for s in sources]
    yield f"data: {json.dumps({'sources': sources_dict}, ensure_ascii=False)}\n\n"
    yield "data: [DONE]\n\n"


@router.post("/search")
async def semantic_search_endpoint(
    request: QueryRequest,
    api_config: APIConfig = Depends(get_active_api_config)
):
    try:
        vector_store, _ = create_services(
            api_config,
            request.knowledge_base_id
        )
        
        results = similarity_search(
            vector_store,
            request.query,
            request.top_k
        )
        
        sources = []
        for doc in results:
            source = SourceNode(
                document_id=doc.metadata.get("document_id", ""),
                file_name=doc.metadata.get("file_name", "未知文档"),
                content=doc.page_content[:300] + "..." if len(doc.page_content) > 300 else doc.page_content,
                score=doc.metadata.get("score", 0),
                chunk_index=doc.metadata.get("chunk_index")
            )
            sources.append(source)
        
        return {"results": sources}
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"搜索失败：{str(e)}"
        )
