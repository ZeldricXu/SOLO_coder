from typing import List, Dict, Any, Optional
import asyncio
import json
from loguru import logger
import httpx
import numpy as np
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

from recommendation_engine.models.schemas import ContentItem, ContentEmbedding
from config import settings


class EmbeddingServiceClient:
    def __init__(self):
        self._base_url = settings.embedding_service_url
        self._timeout = settings.embedding_service_timeout
        self._batch_size = settings.embedding_service_batch_size
        self._client: Optional[httpx.AsyncClient] = None

    async def initialize(self) -> None:
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=self._timeout,
                limits=httpx.Limits(max_connections=100),
            )
            logger.info("EmbeddingServiceClient initialized")

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None
            logger.info("EmbeddingServiceClient closed")

    def _get_client(self) -> httpx.AsyncClient:
        if self._client is None:
            raise RuntimeError("EmbeddingServiceClient not initialized")
        return self._client

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=10),
        retry=retry_if_exception_type((httpx.HTTPError, httpx.TimeoutException)),
        reraise=True,
    )
    async def _request_embeddings(
        self, texts: List[str], embedding_type: str = "text"
    ) -> List[List[float]]:
        client = self._get_client()
        response = await client.post(
            self._base_url,
            json={"texts": texts, "type": embedding_type},
            timeout=self._timeout,
        )
        response.raise_for_status()
        data = response.json()
        embeddings = data.get("embeddings", [])
        if not embeddings:
            raise ValueError("Empty embeddings response")
        return embeddings

    async def get_text_embeddings(
        self, texts: List[str]
    ) -> List[np.ndarray]:
        if not texts:
            return []

        all_embeddings = []
        for i in range(0, len(texts), self._batch_size):
            batch = texts[i : i + self._batch_size]
            try:
                batch_embeddings = await self._request_embeddings(batch, "text")
                for emb in batch_embeddings:
                    arr = np.array(emb, dtype=np.float32)
                    if len(arr) != settings.faiss_embedding_dim:
                        arr = np.zeros(settings.faiss_embedding_dim, dtype=np.float32)
                    norm = np.linalg.norm(arr)
                    if norm > 0:
                        arr = arr / norm
                    all_embeddings.append(arr)
            except Exception as e:
                logger.error(f"Failed to get embeddings for batch {i}: {e}")
                for _ in batch:
                    all_embeddings.append(
                        np.zeros(settings.faiss_embedding_dim, dtype=np.float32)
                    )

        return all_embeddings

    async def get_image_embeddings(
        self, image_urls: List[str]
    ) -> List[np.ndarray]:
        if not image_urls:
            return []

        client = self._get_client()
        all_embeddings = []
        for i in range(0, len(image_urls), self._batch_size):
            batch = image_urls[i : i + self._batch_size]
            try:
                response = await client.post(
                    self._base_url,
                    json={"image_urls": batch, "type": "image"},
                    timeout=self._timeout,
                )
                response.raise_for_status()
                data = response.json()
                batch_embeddings = data.get("embeddings", [])
                for emb in batch_embeddings:
                    arr = np.array(emb, dtype=np.float32)
                    if len(arr) != settings.faiss_embedding_dim:
                        arr = np.zeros(settings.faiss_embedding_dim, dtype=np.float32)
                    norm = np.linalg.norm(arr)
                    if norm > 0:
                        arr = arr / norm
                    all_embeddings.append(arr)
            except Exception as e:
                logger.error(f"Failed to get image embeddings for batch {i}: {e}")
                for _ in batch:
                    all_embeddings.append(
                        np.zeros(settings.faiss_embedding_dim, dtype=np.float32)
                    )

        return all_embeddings

    async def get_content_embeddings(
        self, content_items: List[ContentItem]
    ) -> List[ContentEmbedding]:
        if not content_items:
            return []

        texts_to_embed = []
        for item in content_items:
            text_parts = []
            if item.title:
                text_parts.append(item.title)
            if item.metadata and "description" in item.metadata:
                text_parts.append(str(item.metadata["description"]))
            if item.tags:
                text_parts.append(" ".join(item.tags))
            if item.categories:
                text_parts.append(" ".join(item.categories))

            texts_to_embed.append(" ".join(text_parts))

        embeddings = await self.get_text_embeddings(texts_to_embed)

        result = []
        for item, embedding in zip(content_items, embeddings):
            result.append(
                ContentEmbedding(
                    content_id=item.content_id,
                    embedding=embedding.tolist(),
                    embedding_type="text",
                    model_version="v1",
                )
            )

        return result

    async def health_check(self) -> bool:
        try:
            client = self._get_client()
            response = await client.get(
                self._base_url.replace("/embeddings", "/health"),
                timeout=2.0,
            )
            return response.status_code == 200
        except Exception:
            return False
