from typing import List, Optional, Dict, Any
import asyncio
import uuid

from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance,
    VectorParams,
    PointStruct,
    Filter,
    FieldCondition,
    MatchValue,
    ScoredPoint
)

from app.core.config import settings
from app.data.vector.base import (
    IVectorRepository,
    DocumentChunk,
    SearchResult
)


class QdrantVectorRepository(IVectorRepository):
    def __init__(
        self,
        host: Optional[str] = None,
        port: Optional[int] = None,
        default_collection: Optional[str] = None,
        vector_dimensions: Optional[int] = None
    ):
        self._host = host or settings.QDRANT_HOST
        self._port = port or settings.QDRANT_PORT
        self._default_collection = default_collection or settings.QDRANT_COLLECTION
        self._vector_dimensions = vector_dimensions or settings.EMBEDDING_DIMENSIONS
        self._client: Optional[QdrantClient] = None

    @property
    def client(self) -> QdrantClient:
        if self._client is None:
            self._client = QdrantClient(
                host=self._host,
                port=self._port
            )
        return self._client

    async def ensure_collection(self, collection_name: str) -> None:
        collections = await asyncio.to_thread(
            self.client.get_collections
        )
        collection_names = [c.name for c in collections.collections]

        if collection_name not in collection_names:
            await asyncio.to_thread(
                self.client.create_collection,
                collection_name=collection_name,
                vectors_config=VectorParams(
                    size=self._vector_dimensions,
                    distance=Distance.COSINE
                )
            )

    async def upsert(self, chunks: List[DocumentChunk], collection_name: str) -> int:
        if not chunks:
            return 0

        await self.ensure_collection(self._default_collection)

        points = []
        for chunk in chunks:
            if not chunk.vector:
                continue

            point = PointStruct(
                id=chunk.chunk_id or str(uuid.uuid4()),
                vector=chunk.vector,
                payload=chunk.to_payload()
            )
            points.append(point)

        if not points:
            return 0

        await asyncio.to_thread(
            self.client.upsert,
            collection_name=self._default_collection,
            points=points
        )

        return len(points)

    async def search(
        self,
        query_vector: List[float],
        collection_name: Optional[str] = None,
        top_k: int = 5
    ) -> List[SearchResult]:
        await self.ensure_collection(self._default_collection)

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

        results: List[ScoredPoint] = await asyncio.to_thread(
            self.client.search,
            collection_name=self._default_collection,
            query_vector=query_vector,
            limit=top_k,
            query_filter=query_filter
        )

        search_results = []
        for result in results:
            payload = result.payload or {}
            search_results.append(
                SearchResult(
                    chunk_id=result.id if isinstance(result.id, str) else str(result.id),
                    score=result.score,
                    content=payload.get("content", ""),
                    collection_name=payload.get("collection_name", ""),
                    metadata={
                        k: v for k, v in payload.items()
                        if k not in ["content", "collection_name", "chunk_id"]
                    }
                )
            )

        return search_results

    async def delete_by_collection(self, collection_name: str) -> bool:
        try:
            await asyncio.to_thread(
                self.client.delete,
                collection_name=self._default_collection,
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

    async def count(self, collection_name: Optional[str] = None) -> int:
        try:
            count_result = await asyncio.to_thread(
                self.client.count,
                collection_name=self._default_collection
            )
            return count_result.count
        except Exception:
            return 0
