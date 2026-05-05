from typing import List, Optional, Dict, Any, AsyncIterator
from dataclasses import dataclass
from datetime import datetime
import json
import asyncio

from app.clients.base import (
    ILLMClient,
    ChatMessage,
    ChatCompletionChunk
)
from app.services.rag.retrieval_service import RetrievalService
from app.services.rag.prompt_builder import PromptBuilder
from app.core.config import settings


@dataclass
class ChatRequest:
    model: str
    messages: List[Dict[str, str]]
    collection_name: Optional[str] = None
    stream: bool = True
    temperature: float = 0.7
    max_tokens: Optional[int] = None
    use_rag: bool = True


@dataclass
class ChatResponseEvent:
    event_type: str
    data: Any


class ChatOrchestrator:
    def __init__(
        self,
        llm_client: ILLMClient,
        retrieval_service: RetrievalService,
        prompt_builder: PromptBuilder
    ):
        self._llm_client = llm_client
        self._retrieval_service = retrieval_service
        self._prompt_builder = prompt_builder

    def _convert_messages(
        self,
        messages: List[Dict[str, str]]
    ) -> List[ChatMessage]:
        return [
            ChatMessage(role=msg.get("role", "user"), content=msg.get("content", ""))
            for msg in messages
        ]

    def _get_latest_user_message(
        self,
        messages: List[Dict[str, str]]
    ) -> Optional[str]:
        for msg in reversed(messages):
            if msg.get("role") == "user":
                return msg.get("content")
        return None

    async def chat(
        self,
        request: ChatRequest
    ) -> AsyncIterator[ChatResponseEvent]:
        start_time = datetime.utcnow()

        ping_data = {
            "timestamp": start_time.isoformat()
        }
        yield ChatResponseEvent(event_type="ping", data=ping_data)
        await asyncio.sleep(0.01)

        context_chunks = []
        if request.use_rag and request.collection_name:
            user_query = self._get_latest_user_message(request.messages)
            if user_query:
                try:
                    retrieved = await self._retrieval_service.retrieve(
                        query=user_query,
                        collection_name=request.collection_name
                    )
                    context_chunks = retrieved.chunks
                    
                    if context_chunks:
                        context_data = [
                            {
                                "content": chunk.content,
                                "score": chunk.score,
                                "metadata": chunk.metadata
                            }
                            for chunk in context_chunks
                        ]
                        yield ChatResponseEvent(event_type="context", data=context_data)
                        await asyncio.sleep(0.01)
                        
                except Exception as e:
                    yield ChatResponseEvent(event_type="error", data=str(e))
                    return

        try:
            processed_messages = request.messages
            if context_chunks:
                context_objs = []
                for chunk in context_chunks:
                    context_objs.append({
                        "content": chunk.content,
                        "score": chunk.score,
                        "metadata": chunk.metadata
                    })
                
                enhanced_msgs = self._prompt_builder.enhance_messages(
                    request.messages,
                    context_objs
                )
                processed_messages = enhanced_msgs

            chat_messages = self._convert_messages(processed_messages)

            if request.stream:
                async for chunk in self._llm_client.chat_stream(
                    messages=chat_messages,
                    model=request.model,
                    temperature=request.temperature,
                    max_tokens=request.max_tokens
                ):
                    if chunk.content:
                        yield ChatResponseEvent(event_type="content", data=chunk.content)
                    
                    if chunk.finish_reason:
                        done_data = {
                            "finish_reason": chunk.finish_reason,
                            "duration_ms": (datetime.utcnow() - start_time).total_seconds() * 1000
                        }
                        yield ChatResponseEvent(event_type="done", data=done_data)
            else:
                response = await self._llm_client.chat(
                    messages=chat_messages,
                    model=request.model,
                    temperature=request.temperature,
                    max_tokens=request.max_tokens
                )
                yield ChatResponseEvent(event_type="content", data=response.content)
                yield ChatResponseEvent(event_type="done", data={
                    "finish_reason": response.finish_reason,
                    "duration_ms": (datetime.utcnow() - start_time).total_seconds() * 1000
                })

        except Exception as e:
            yield ChatResponseEvent(event_type="error", data=str(e))

    async def chat_non_stream(
        self,
        request: ChatRequest
    ) -> Dict[str, Any]:
        start_time = datetime.utcnow()

        context_chunks = []
        if request.use_rag and request.collection_name:
            user_query = self._get_latest_user_message(request.messages)
            if user_query:
                retrieved = await self._retrieval_service.retrieve(
                    query=user_query,
                    collection_name=request.collection_name
                )
                context_chunks = retrieved.chunks

        processed_messages = request.messages
        if context_chunks:
            context_objs = []
            for chunk in context_chunks:
                context_objs.append({
                    "content": chunk.content,
                    "score": chunk.score,
                    "metadata": chunk.metadata
                })
            
            enhanced_msgs = self._prompt_builder.enhance_messages(
                request.messages,
                context_objs
            )
            processed_messages = enhanced_msgs

        chat_messages = self._convert_messages(processed_messages)
        response = await self._llm_client.chat(
            messages=chat_messages,
            model=request.model,
            temperature=request.temperature,
            max_tokens=request.max_tokens
        )

        duration_ms = (datetime.utcnow() - start_time).total_seconds() * 1000

        return {
            "content": response.content,
            "model": request.model,
            "finish_reason": response.finish_reason,
            "usage_tokens": response.usage_tokens,
            "duration_ms": duration_ms,
            "context_chunks_count": len(context_chunks)
        }
