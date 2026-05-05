from typing import List, Optional, Dict, Any
from dataclasses import dataclass

from app.clients.base import IEmbeddingClient
from app.data.vector.base import IVectorRepository, SearchResult
from app.core.config import settings


@dataclass
class RetrievedContext:
    query: str
    chunks: List[SearchResult]
    total_found: int


class RetrievalService:
    def __init__(
        self,
        embedding_client: IEmbeddingClient,
        vector_repository: IVectorRepository,
        top_k: Optional[int] = None
    ):
        self._embedding_client = embedding_client
        self._vector_repository = vector_repository
        self._top_k = top_k or settings.TOP_K

    async def retrieve(
        self,
        query: str,
        collection_name: Optional[str] = None,
        top_k: Optional[int] = None
    ) -> RetrievedContext:
        actual_top_k = top_k or self._top_k
        
        embedding_response = await self._embedding_client.embed(query)
        query_vector = embedding_response.embedding

        results = await self._vector_repository.search(
            query_vector=query_vector,
            collection_name=collection_name,
            top_k=actual_top_k
        )

        return RetrievedContext(
            query=query,
            chunks=results,
            total_found=len(results)
        )

    async def retrieve_with_scores(
        self,
        query: str,
        collection_name: Optional[str] = None,
        top_k: Optional[int] = None,
        min_score: float = 0.0
    ) -> List[Dict[str, Any]]:
        retrieved = await self.retrieve(query, collection_name, top_k)
        
        filtered_chunks = [
            {
                "content": chunk.content,
                "score": chunk.score,
                "metadata": chunk.metadata,
                "collection_name": chunk.collection_name
            }
            for chunk in retrieved.chunks
            if chunk.score >= min_score
        ]
        
        return filtered_chunks

    async def batch_retrieve(
        self,
        queries: List[str],
        collection_name: Optional[str] = None,
        top_k: Optional[int] = None
    ) -> List[RetrievedContext]:
        results = []
        for query in queries:
            retrieved = await self.retrieve(query, collection_name, top_k)
            results.append(retrieved)
        return results
