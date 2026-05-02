from typing import Optional, Dict, Any
from langchain_openai import OpenAIEmbeddings, ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from app.core.config import settings
import chromadb
from chromadb.config import Settings as ChromaSettings
from langchain_chroma import Chroma
import os


class ServiceFactory:
    def __init__(self):
        self._clients: Dict[str, chromadb.PersistentClient] = {}
        self._vector_stores: Dict[str, Chroma] = {}
        self._llm_chains: Dict[str, Any] = {}
        self._current_config_id: Optional[str] = None
    
    def _get_chroma_client(self) -> chromadb.PersistentClient:
        if "default" not in self._clients:
            os.makedirs(settings.CHROMA_PERSIST_DIR, exist_ok=True)
            self._clients["default"] = chromadb.PersistentClient(
                path=settings.CHROMA_PERSIST_DIR,
                settings=ChromaSettings(
                    anonymized_telemetry=False,
                    allow_reset=True
                )
            )
        return self._clients["default"]
    
    def create_embeddings(
        self,
        api_key: str,
        base_url: str,
        model: str
    ) -> OpenAIEmbeddings:
        return OpenAIEmbeddings(
            model=model,
            api_key=api_key,
            base_url=base_url
        )
    
    def create_llm(
        self,
        api_key: str,
        base_url: str,
        model: str
    ) -> ChatOpenAI:
        return ChatOpenAI(
            model=model,
            api_key=api_key,
            base_url=base_url,
            temperature=0.7,
            streaming=True
        )
    
    def create_vector_store(
        self,
        embeddings: OpenAIEmbeddings,
        collection_name: str = "default"
    ) -> Chroma:
        cache_key = f"{collection_name}"
        if cache_key in self._vector_stores:
            return self._vector_stores[cache_key]
        
        client = self._get_chroma_client()
        
        vector_store = Chroma(
            client=client,
            collection_name=collection_name,
            embedding_function=embeddings,
            persist_directory=settings.CHROMA_PERSIST_DIR
        )
        
        self._vector_stores[cache_key] = vector_store
        return vector_store
    
    def create_rag_chain(self, llm: ChatOpenAI):
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
        return prompt | llm | StrOutputParser()


service_factory = ServiceFactory()
