from typing import TypeVar, Type
from fastapi import Depends, Request

from app.core.di import DIContainer, get_container, IDIContainer
from app.clients.base import IEmbeddingClient, ILLMClient
from app.clients.openai_client import OpenAIEmbeddingClient, OpenAILLMClient
from app.data.vector.base import IVectorRepository
from app.data.vector.qdrant_repository import QdrantVectorRepository
from app.services.document import (
    TextChunker,
    FileExtractor,
    DocumentService
)
from app.services.rag import (
    PromptBuilder,
    RetrievalService,
    ChatOrchestrator
)


T = TypeVar('T')


def register_services(container: DIContainer) -> None:
    container.register_singleton(
        IVectorRepository,
        QdrantVectorRepository
    )

    container.register_singleton(
        IEmbeddingClient,
        OpenAIEmbeddingClient
    )

    container.register_singleton(
        ILLMClient,
        OpenAILLMClient
    )

    container.register_singleton(
        TextChunker,
        TextChunker
    )

    container.register_singleton(
        FileExtractor,
        FileExtractor
    )

    container.register_singleton(
        PromptBuilder,
        PromptBuilder
    )

    container.register_singleton(
        RetrievalService,
        RetrievalService
    )

    container.register_singleton(
        DocumentService,
        DocumentService
    )

    container.register_singleton(
        ChatOrchestrator,
        ChatOrchestrator
    )


def get_di_container() -> IDIContainer:
    return get_container()


def get_service(service_type: Type[T]) -> T:
    container = get_di_container()
    return container.resolve(service_type)


def get_vector_repository() -> IVectorRepository:
    return get_service(IVectorRepository)


def get_embedding_client() -> IEmbeddingClient:
    return get_service(IEmbeddingClient)


def get_llm_client() -> ILLMClient:
    return get_service(ILLMClient)


def get_text_chunker() -> TextChunker:
    return get_service(TextChunker)


def get_file_extractor() -> FileExtractor:
    return get_service(FileExtractor)


def get_prompt_builder() -> PromptBuilder:
    return get_service(PromptBuilder)


def get_retrieval_service() -> RetrievalService:
    return get_service(RetrievalService)


def get_document_service() -> DocumentService:
    return get_service(DocumentService)


def get_chat_orchestrator() -> ChatOrchestrator:
    return get_service(ChatOrchestrator)
