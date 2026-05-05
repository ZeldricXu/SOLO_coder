from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Optional, Dict, Any
from uuid import UUID, uuid4


@dataclass
class DocumentChunk:
    chunk_id: str = field(default_factory=lambda: str(uuid4()))
    collection_name: str = ""
    content: str = ""
    vector: List[float] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_payload(self) -> Dict[str, Any]:
        return {
            "chunk_id": self.chunk_id,
            "collection_name": self.collection_name,
            "content": self.content,
            "metadata": self.metadata
        }

    @classmethod
    def from_payload(
        cls,
        payload: Dict[str, Any],
        vector: Optional[List[float]] = None
    ) -> "DocumentChunk":
        return cls(
            chunk_id=payload.get("chunk_id", ""),
            collection_name=payload.get("collection_name", ""),
            content=payload.get("content", ""),
            vector=vector or [],
            metadata=payload.get("metadata", {})
        )


@dataclass
class SearchResult:
    chunk_id: str
    score: float
    content: str
    collection_name: str
    metadata: Dict[str, Any] = field(default_factory=dict)


class IVectorRepository(ABC):
    @abstractmethod
    async def ensure_collection(self, collection_name: str) -> None:
        pass

    @abstractmethod
    async def upsert(self, chunks: List[DocumentChunk], collection_name: str) -> int:
        pass

    @abstractmethod
    async def search(
        self,
        query_vector: List[float],
        collection_name: Optional[str] = None,
        top_k: int = 5
    ) -> List[SearchResult]:
        pass

    @abstractmethod
    async def delete_by_collection(self, collection_name: str) -> bool:
        pass

    @abstractmethod
    async def count(self, collection_name: Optional[str] = None) -> int:
        pass
