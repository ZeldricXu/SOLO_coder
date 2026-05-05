from typing import List, Optional
import httpx
import uuid
import asyncio
from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance,
    VectorParams,
    PointStruct,
    Filter,
    FieldCondition,
    MatchValue
)
from app.core.config import settings


class EmbeddingService:
    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None
    ):
        self.api_key = api_key or settings.EMBEDDING_API_KEY
        self.base_url = base_url or settings.EMBEDDING_BASE_URL
        self.model = model or settings.EMBEDDING_MODEL
        self.dimensions = settings.EMBEDDING_DIMENSIONS

    async def embed(
        self,
        text: str,
        retries: int = settings.MAX_RETRIES
    ) -> List[float]:
        url = f"{self.base_url}/embeddings"
        headers = {
            "Content-Type": "application/json",
        }
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        payload = {
            "input": text,
            "model": self.model
        }

        for attempt in range(retries):
            try:
                async with httpx.AsyncClient(timeout=settings.REQUEST_TIMEOUT) as client:
                    response = await client.post(url, json=payload, headers=headers)
                    response.raise_for_status()
                    data = response.json()
                    return data["data"][0]["embedding"]
            except Exception as e:
                if attempt == retries - 1:
                    raise RuntimeError(f"嵌入服务调用失败: {str(e)}")
                await asyncio.sleep(1 * (attempt + 1))

        raise RuntimeError("嵌入服务调用失败")

    async def embed_batch(
        self,
        texts: List[str],
        max_concurrent: int = 5
    ) -> List[List[float]]:
        semaphore = asyncio.Semaphore(max_concurrent)

        async def embed_with_semaphore(text: str) -> List[float]:
            async with semaphore:
                return await self.embed(text)

        tasks = [embed_with_semaphore(text) for text in texts]
        return await asyncio.gather(*tasks)


class VectorService:
    def __init__(
        self,
        host: Optional[str] = None,
        port: Optional[int] = None,
        collection_name: Optional[str] = None
    ):
        self.host = host or settings.QDRANT_HOST
        self.port = port or settings.QDRANT_PORT
        self.collection_name = collection_name or settings.QDRANT_COLLECTION
        self._client: Optional[QdrantClient] = None

    @property
    def client(self) -> QdrantClient:
        if self._client is None:
            self._client = QdrantClient(host=self.host, port=self.port)
        return self._client

    async def ensure_collection(self):
        collections = await asyncio.to_thread(
            self.client.get_collections
        )
        collection_names = [c.name for c in collections.collections]

        if self.collection_name not in collection_names:
            await asyncio.to_thread(
                self.client.create_collection,
                collection_name=self.collection_name,
                vectors_config=VectorParams(
                    size=settings.EMBEDDING_DIMENSIONS,
                    distance=Distance.COSINE
                )
            )

    async def upsert_points(
        self,
        texts: List[str],
        vectors: List[List[float]],
        collection_name: str,
        metadata_list: Optional[List[dict]] = None
    ) -> int:
        if metadata_list is None:
            metadata_list = [{} for _ in texts]

        points = []
        for text, vector, metadata in zip(texts, vectors, metadata_list):
            point_id = str(uuid.uuid4())
            point_metadata = {
                "content": text,
                "collection_name": collection_name,
                **metadata
            }
            points.append(
                PointStruct(
                    id=point_id,
                    vector=vector,
                    payload=point_metadata
                )
            )

        await asyncio.to_thread(
            self.client.upsert,
            collection_name=self.collection_name,
            points=points
        )

        return len(points)

    async def search(
        self,
        query_vector: List[float],
        collection_name: Optional[str] = None,
        top_k: int = None
    ) -> List[dict]:
        top_k = top_k or settings.TOP_K

        query_filter = None
        if collection_name:
            query_filter = Filter(
                must=[
                    FieldCondition(
                        key="collection_name",
                        match=MatchValue(value=collection_name)
                    )
                ]
            )

        results = await asyncio.to_thread(
            self.client.search,
            collection_name=self.collection_name,
            query_vector=query_vector,
            limit=top_k,
            query_filter=query_filter
        )

        formatted_results = []
        for result in results:
            formatted_results.append({
                "id": result.id,
                "score": result.score,
                "content": result.payload.get("content", ""),
                "metadata": {
                    k: v for k, v in result.payload.items()
                    if k not in ["content", "collection_name"]
                },
                "collection_name": result.payload.get("collection_name")
            })

        return formatted_results

    async def delete_collection(self, collection_name: str) -> bool:
        try:
            await asyncio.to_thread(
                self.client.delete,
                collection_name=self.collection_name,
                points_selector=Filter(
                    must=[
                        FieldCondition(
                            key="collection_name",
                            match=MatchValue(value=collection_name)
                        )
                    ]
                )
            )
            return True
        except Exception:
            return False
