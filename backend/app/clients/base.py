from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Optional, Dict, Any, AsyncIterator


@dataclass
class EmbeddingResponse:
    embedding: List[float]
    model: str
    usage_tokens: Optional[int] = None


@dataclass
class ChatMessage:
    role: str
    content: str


@dataclass
class ChatResponse:
    content: str
    model: str
    finish_reason: Optional[str] = None
    usage_tokens: Optional[int] = None


@dataclass
class ChatCompletionChunk:
    content: str
    finish_reason: Optional[str] = None


class IEmbeddingClient(ABC):
    @abstractmethod
    async def embed(self, text: str) -> EmbeddingResponse:
        pass

    @abstractmethod
    async def embed_batch(self, texts: List[str]) -> List[EmbeddingResponse]:
        pass


class ILLMClient(ABC):
    @abstractmethod
    async def chat(
        self,
        messages: List[ChatMessage],
        model: str,
        temperature: float = 0.7,
        max_tokens: Optional[int] = None,
        **kwargs
    ) -> ChatResponse:
        pass

    @abstractmethod
    async def chat_stream(
        self,
        messages: List[ChatMessage],
        model: str,
        temperature: float = 0.7,
        max_tokens: Optional[int] = None,
        **kwargs
    ) -> AsyncIterator[ChatCompletionChunk]:
        pass
