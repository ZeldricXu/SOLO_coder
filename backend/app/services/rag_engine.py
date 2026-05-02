from typing import List, Optional, AsyncGenerator, Dict, Any
from langchain_core.documents import Document as LangchainDocument
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from app.core.config import settings
from app.services.vector_store import VectorStoreService
from app.models.schemas import SourceNode


class RAGEngine:
    def __init__(self, vector_store_service: VectorStoreService):
        self.vector_store = vector_store_service

        self.llm = ChatOpenAI(
            model=settings.LLM_MODEL,
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_API_BASE,
            temperature=0.7,
            streaming=True
        )
        
        self.system_prompt = """你是一个专业的知识助手。请根据以下提供的上下文信息，准确、全面地回答用户的问题。

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
        
        self.prompt = ChatPromptTemplate.from_template(self.system_prompt)
        self.chain = self.prompt | self.llm | StrOutputParser()
    
    def _format_context(self, documents: List[LangchainDocument]) -> str:
        context_parts = []
        for i, doc in enumerate(documents):
            source = doc.metadata.get("file_name", f"文档_{i}")
            chunk_idx = doc.metadata.get("chunk_index", i)
            score = doc.metadata.get("score", 0)
            
            context_parts.append(
                f"【来源：{source} | 片段：{chunk_idx} | 相关度：{score:.3f}】\n{doc.page_content}\n"
            )
        
        return "\n---\n".join(context_parts)
    
    def _build_source_nodes(self, documents: List[LangchainDocument]) -> List[SourceNode]:
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
    
    async def process_query(
        self,
        query: str,
        collection_name: str = "default",
        top_k: int = 5
    ) -> Dict[str, Any]:
        retrieved_docs = self.vector_store.similarity_search(
            query=query,
            collection_name=collection_name,
            top_k=top_k
        )
        
        if not retrieved_docs:
            return {
                "answer": "未在知识库中找到相关信息",
                "sources": []
            }
        
        context = self._format_context(retrieved_docs)
        sources = self._build_source_nodes(retrieved_docs)
        
        answer = await self.chain.ainvoke({
            "context": context,
            "question": query
        })
        
        return {
            "answer": answer,
            "sources": sources
        }
    
    async def process_query_stream(
        self,
        query: str,
        collection_name: str = "default",
        top_k: int = 5
    ) -> AsyncGenerator[Dict[str, Any], None]:
        retrieved_docs = self.vector_store.similarity_search(
            query=query,
            collection_name=collection_name,
            top_k=top_k
        )
        
        if not retrieved_docs:
            yield {"type": "answer", "content": "未在知识库中找到相关信息"}
            yield {"type": "sources", "content": []}
            return
        
        context = self._format_context(retrieved_docs)
        sources = self._build_source_nodes(retrieved_docs)
        
        index = 0
        async for chunk in self.chain.astream({
            "context": context,
            "question": query
        }):
            yield {
                "type": "token",
                "content": chunk,
                "index": index
            }
            index += 1
        
        yield {
            "type": "sources",
            "content": [s.model_dump() for s in sources]
        }
