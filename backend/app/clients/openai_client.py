from typing import List, Optional, Dict, Any
import httpx
import asyncio

from app.core.config import settings
from app.clients.base import (
    IEmbeddingClient,
    EmbeddingResponse,
    ChatMessage,
    ChatResponse,
    ChatCompletionChunk,
    ILLMClient
)


class BaseAPIClient:
    def __init__(
        self,
        base_url: str,
        api_key: Optional[str] = None,
        timeout: int = 60,
        max_retries: int = 3
    ):
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._timeout = timeout
        self._max_retries = max_retries

    def _build_headers(self) -> Dict[str, str]:
        headers = {
            "Content-Type": "application/json",
        }
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        return headers

    async def _request_with_retry(
        self,
        method: str,
        endpoint: str,
        payload: Optional[Dict[str, Any]] = None,
        stream: bool = False
    ):
        url = f"{self._base_url}/{endpoint.lstrip('/')}"
        headers = self._build_headers()

        last_exception = None
        for attempt in range(self._max_retries):
            try:
                async with httpx.AsyncClient(
                    timeout=httpx.Timeout(timeout=self._timeout)
                ) as client:
                    if stream and method.upper() == "POST":
                        return client.stream(
                            "POST",
                            url,
                            json=payload,
                            headers=headers
                        )
                    else:
                        response = await client.request(
                            method,
                            url,
                            json=payload,
                            headers=headers
                        )
                        response.raise_for_status()
                        return response

            except httpx.HTTPStatusError as e:
                if e.response.status_code >= 500 and attempt < self._max_retries - 1:
                    last_exception = e
                    await asyncio.sleep(1 * (attempt + 1))
                    continue
                raise

            except Exception as e:
                if attempt < self._max_retries - 1:
                    last_exception = e
                    await asyncio.sleep(1 * (attempt + 1))
                    continue
                raise

        if last_exception:
            raise last_exception


class OpenAIEmbeddingClient(BaseAPIClient, IEmbeddingClient):
    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        timeout: int = 60,
        max_retries: int = 3
    ):
        super().__init__(
            base_url=base_url or settings.EMBEDDING_BASE_URL,
            api_key=api_key or settings.EMBEDDING_API_KEY,
            timeout=timeout,
            max_retries=max_retries
        )
        self._model = model or settings.EMBEDDING_MODEL

    async def embed(self, text: str) -> EmbeddingResponse:
        payload = {
            "input": text,
            "model": self._model
        }

        response = await self._request_with_retry(
            "POST",
            "/embeddings",
            payload=payload
        )

        data = response.json()
        embedding = data["data"][0]["embedding"]
        usage = data.get("usage", {})

        return EmbeddingResponse(
            embedding=embedding,
            model=self._model,
            usage_tokens=usage.get("total_tokens")
        )

    async def embed_batch(self, texts: List[str]) -> List[EmbeddingResponse]:
        if not texts:
            return []

        payload = {
            "input": texts,
            "model": self._model
        }

        response = await self._request_with_retry(
            "POST",
            "/embeddings",
            payload=payload
        )

        data = response.json()
        embeddings_data = data.get("data", [])
        usage = data.get("usage", {})

        results = []
        for item in embeddings_data:
            results.append(
                EmbeddingResponse(
                    embedding=item["embedding"],
                    model=self._model,
                    usage_tokens=usage.get("total_tokens")
                )
            )

        return results


class OpenAILLMClient(BaseAPIClient, ILLMClient):
    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        timeout: int = 120,
        max_retries: int = 3
    ):
        super().__init__(
            base_url=base_url or settings.LLM_BASE_URL,
            api_key=api_key or settings.LLM_API_KEY,
            timeout=timeout,
            max_retries=max_retries
        )

    def _convert_messages(
        self,
        messages: List[ChatMessage]
    ) -> List[Dict[str, str]]:
        return [
            {"role": msg.role, "content": msg.content}
            for msg in messages
        ]

    async def chat(
        self,
        messages: List[ChatMessage],
        model: str,
        temperature: float = 0.7,
        max_tokens: Optional[int] = None,
        **kwargs
    ) -> ChatResponse:
        payload = {
            "model": model,
            "messages": self._convert_messages(messages),
            "temperature": temperature,
            "stream": False
        }

        if max_tokens is not None:
            payload["max_tokens"] = max_tokens

        payload.update(kwargs)

        response = await self._request_with_retry(
            "POST",
            "/chat/completions",
            payload=payload
        )

        data = response.json()
        choices = data.get("choices", [])
        usage = data.get("usage", {})

        if not choices:
            return ChatResponse(
                content="",
                model=model,
                usage_tokens=usage.get("total_tokens")
            )

        choice = choices[0]
        message = choice.get("message", {})

        return ChatResponse(
            content=message.get("content", ""),
            model=model,
            finish_reason=choice.get("finish_reason"),
            usage_tokens=usage.get("total_tokens")
        )

    async def chat_stream(
        self,
        messages: List[ChatMessage],
        model: str,
        temperature: float = 0.7,
        max_tokens: Optional[int] = None,
        **kwargs
    ):
        import json

        payload = {
            "model": model,
            "messages": self._convert_messages(messages),
            "temperature": temperature,
            "stream": True
        }

        if max_tokens is not None:
            payload["max_tokens"] = max_tokens

        payload.update(kwargs)

        url = f"{self._base_url}/chat/completions"
        headers = self._build_headers()

        async with httpx.AsyncClient(
            timeout=httpx.Timeout(timeout=self._timeout * 2)
        ) as client:
            async with client.stream(
                "POST",
                url,
                json=payload,
                headers=headers
            ) as response:
                if response.status_code != 200:
                    error_text = await response.aread()
                    raise RuntimeError(
                        f"LLM API调用失败: {response.status_code} - {error_text.decode()}"
                    )

                async for line in response.aiter_lines():
                    if line.startswith("data: "):
                        data_str = line[6:]
                        if data_str == "[DONE]":
                            break

                        try:
                            data = json.loads(data_str)
                            choices = data.get("choices", [])

                            if choices:
                                choice = choices[0]
                                delta = choice.get("delta", {})
                                content = delta.get("content", "")
                                finish_reason = choice.get("finish_reason")

                                if content or finish_reason:
                                    yield ChatCompletionChunk(
                                        content=content,
                                        finish_reason=finish_reason
                                    )

                        except json.JSONDecodeError:
                            continue
