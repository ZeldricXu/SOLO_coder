from __future__ import annotations

from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.vector_index.embedding import EmbeddingService
from streamsql.modules.vector_index.index_builder import VectorIndexBuilder
from streamsql.modules.vector_index.ann_search import ANNSearch


class VectorService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()
        self.embedding_service = EmbeddingService()
        self.index_builder = VectorIndexBuilder(index_type="hnsw")
        self.searcher = None

    def build_index(
        self,
        texts: list[str],
        index_type: str = "hnsw",
        embedding_model: str = "mock",
        distance_metric: str = "cosine",
    ) -> dict[str, Any]:
        context = ProcessingContext(trace_id="build_index")

        self.embedding_service.model_type = embedding_model
        embeddings = self.embedding_service.encode_batch(texts)

        self.index_builder.index_type = index_type
        self.index_builder.distance_metric = distance_metric
        index = self.index_builder.build(embeddings)

        self.searcher = ANNSearcher(index_type=index_type, distance_metric=distance_metric)
        self.searcher.load_index(index)

        return {
            "index_id": index.index_id,
            "index_type": index_type,
            "distance_metric": distance_metric,
            "num_vectors": len(embeddings),
            "dimension": index.dimension,
            "build_time_ms": context.get_elapsed_ms(),
            "index_size_bytes": index.data_size,
        }

    def search(
        self,
        query_text: str,
        top_k: int = 10,
        texts: Optional[list[str]] = None,
    ) -> dict[str, Any]:
        query_embedding = self.embedding_service.encode(query_text)

        if texts:
            embeddings = self.embedding_service.encode_batch(texts)
            index = self.index_builder.build(embeddings)
            self.searcher.load_index(index)

        results = self.searcher.search(query_embedding, top_k=top_k)

        return {
            "query": query_text,
            "top_k": top_k,
            "results": [r.to_dict() for r in results],
        }

    def add_vectors(
        self,
        texts: list[str],
        existing_index_id: Optional[str] = None,
    ) -> dict[str, Any]:
        embeddings = self.embedding_service.encode_batch(texts)
        self.index_builder.add_vectors(embeddings)

        return {
            "added_count": len(texts),
            "total_count": len(self.index_builder.embeddings),
        }

    def get_embedding(self, text: str, model_type: str = "mock") -> dict[str, Any]:
        self.embedding_service.model_type = model_type
        embedding = self.embedding_service.encode(text)
        return {
            "text": text,
            "model": model_type,
            "dimension": len(embedding),
            "embedding": embedding[:10],
        }

    def get_available_models(self) -> list[str]:
        return ["mock", "sentence-transformers", "openai"]

    def get_available_index_types(self) -> list[str]:
        return ["flat", "hnsw", "ivf"]
