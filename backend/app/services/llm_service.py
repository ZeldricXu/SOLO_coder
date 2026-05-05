from typing import List, Optional, AsyncIterator, Dict, Any
import json
import httpx
import asyncio
from datetime import datetime
from app.core.config import settings
from app.models.models import Message


class LLMService:
    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None
    ):
        self.api_key = api_key or settings.LLM_API_KEY
        self.base_url = base_url or settings.LLM_BASE_URL

    def _build_headers(self) -> Dict[str, str]:
        headers = {
            "Content-Type": "application/json",
        }
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    def _build_prompt(
        self,
        user_query: str,
        context_chunks: List[Dict[str, Any]]
    ) -> str:
        if not context_chunks:
            return user_query

        context_parts = []
        for idx, chunk in enumerate(context_chunks):
            content = chunk.get("content", "")
            score = chunk.get("score", 0)
            context_parts.append(f"[相关文档 {idx + 1} (相似度: {score:.3f})]\n{content}")

        context_str = "\n\n".join(context_parts)

        system_prompt = """你是一个专业的AI助手，请基于以下提供的上下文信息回答用户问题。

回答要求：
1. 严格基于提供的上下文信息回答，不要编造信息
2. 如果上下文中没有相关信息，请明确告知用户
3. 回答要简洁、准确、有条理
4. 可以适当引用上下文内容，但不要直接复制整个段落

以下是上下文信息：
"""

        full_prompt = f"{system_prompt}\n{context_str}\n\n用户问题：{user_query}"
        return full_prompt

    async def chat(
        self,
        messages: List[Message],
        model: str,
        context_chunks: Optional[List[Dict[str, Any]]] = None,
        stream: bool = False
    ) -> AsyncIterator[str]:
        url = f"{self.base_url}/chat/completions"
        headers = self._build_headers()

        processed_messages = []
        
        if context_chunks and messages:
            latest_user_msg = messages[-1]
            if latest_user_msg.role == "user":
                enhanced_content = self._build_prompt(
                    latest_user_msg.content,
                    context_chunks
                )
                processed_messages = messages[:-1] + [
                    Message(role="user", content=enhanced_content)
                ]
            else:
                processed_messages = messages
        else:
            processed_messages = messages

        messages_dict = [
            {"role": msg.role, "content": msg.content}
            for msg in processed_messages
        ]

        payload = {
            "model": model,
            "messages": messages_dict,
            "stream": stream
        }

        if stream:
            async for chunk in self._stream_chat(url, headers, payload):
                yield chunk
        else:
            response = await self._non_stream_chat(url, headers, payload)
            yield response

    async def _stream_chat(
        self,
        url: str,
        headers: Dict[str, str],
        payload: Dict[str, Any]
    ) -> AsyncIterator[str]:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(timeout=settings.REQUEST_TIMEOUT * 2)
        ) as client:
            async with client.stream(
                "POST",
                url,
                json=payload,
                headers=headers
            ) as response:
                if response.status_code != 200:
                    error_text = await response.aread()
                    raise RuntimeError(f"API调用失败: {response.status_code} - {error_text.decode()}")

                async for line in response.aiter_lines():
                    if line.startswith("data: "):
                        data_str = line[6:]
                        if data_str == "[DONE]":
                            break
                        try:
                            data = json.loads(data_str)
                            choices = data.get("choices", [])
                            if choices:
                                delta = choices[0].get("delta", {})
                                content = delta.get("content", "")
                                if content:
                                    yield content
                        except json.JSONDecodeError:
                            continue

    async def _non_stream_chat(
        self,
        url: str,
        headers: Dict[str, str],
        payload: Dict[str, Any]
    ) -> str:
        async with httpx.AsyncClient(timeout=settings.REQUEST_TIMEOUT) as client:
            response = await client.post(url, json=payload, headers=headers)
            
            if response.status_code != 200:
                raise RuntimeError(f"API调用失败: {response.status_code} - {response.text}")

            data = response.json()
            choices = data.get("choices", [])
            if choices:
                return choices[0].get("message", {}).get("content", "")
            return ""
