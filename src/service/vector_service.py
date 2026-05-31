import logging
from typing import Any, Dict, List, Optional

import numpy as np

from src.domain.vector.embedding_index import EmbeddingIndex, VectorDocument, IndexStats
from src.domain.vector.ann_search import ANNSearcher, SearchResult
from src.domain.vector.index_optimizer import VectorIndexOptimizer, OptimizationSuggestion, IndexBenchmark
from src.infrastructure.config.settings import VectorConfig

logger = logging.getLogger(__name__)


class VectorService:
    def __init__(self, config: Optional[VectorConfig] = None):
        self._config = config or VectorConfig()
        self._index = EmbeddingIndex(self._config)
        self._searcher = ANNSearcher(self._index, self._config)
        self._optimizer = VectorIndexOptimizer(self._config)

    def build_index(self, documents: List[Dict[str, Any]]) -> Dict[str, Any]:
        vec_docs = []
        for doc in documents:
            doc_id = doc.get("id", str(len(vec_docs)))
            vector = np.array(doc["vector"], dtype=np.float32)
            metadata = doc.get("metadata", {})
            vec_docs.append(VectorDocument(doc_id=doc_id, vector=vector, metadata=metadata))

        self._index.build(vec_docs)
        stats = self._index.get_stats()

        return {
            "total_vectors": stats.total_vectors,
            "dimension": stats.dimension,
            "index_type": stats.index_type,
            "memory_usage_mb": stats.memory_usage_mb,
            "is_trained": stats.is_trained,
        }

    def add_document(self, doc_id: str, vector: List[float], metadata: Optional[Dict[str, Any]] = None) -> None:
        doc = VectorDocument(
            doc_id=doc_id,
            vector=np.array(vector, dtype=np.float32),
            metadata=metadata or {},
        )
        self._index.add(doc)

    def remove_document(self, doc_id: str) -> bool:
        return self._index.remove(doc_id)

    def search(
        self,
        query_vector: List[float],
        top_k: int = 10,
        filters: Optional[Dict[str, Any]] = None,
        min_score: Optional[float] = None,
    ) -> List[Dict[str, Any]]:
        results = self._searcher.search(
            np.array(query_vector, dtype=np.float32),
            top_k, filters, min_score,
        )
        return [r.to_dict() for r in results]

    def batch_search(
        self,
        query_vectors: List[List[float]],
        top_k: int = 10,
    ) -> List[List[Dict[str, Any]]]:
        vectors = np.array(query_vectors, dtype=np.float32)
        batch_result = self._searcher.batch_search(vectors, top_k)
        return [[r.to_dict() for r in results] for results in batch_result.query_results]

    def hybrid_search(
        self,
        query_vector: List[float],
        keyword_results: List[str],
        top_k: int = 10,
        vector_weight: float = 0.7,
        keyword_weight: float = 0.3,
    ) -> List[Dict[str, Any]]:
        results = self._searcher.hybrid_search(
            np.array(query_vector, dtype=np.float32),
            keyword_results, top_k, vector_weight, keyword_weight,
        )
        return [r.to_dict() for r in results]

    def get_index_stats(self) -> Dict[str, Any]:
        stats = self._index.get_stats()
        return {
            "total_vectors": stats.total_vectors,
            "dimension": stats.dimension,
            "index_type": stats.index_type,
            "memory_usage_mb": stats.memory_usage_mb,
            "is_trained": stats.is_trained,
        }

    def save_index(self, path: str) -> None:
        self._index.save(path)

    def load_index(self, path: str) -> None:
        self._index.load(path)
        self._searcher = ANNSearcher(self._index, self._config)

    def suggest_optimization(
        self,
        total_vectors: Optional[int] = None,
        search_qps: float = 0.0,
        memory_limit_mb: Optional[float] = None,
        recall_target: float = 0.95,
    ) -> List[Dict[str, Any]]:
        stats = self._index.get_stats()
        n = total_vectors or stats.total_vectors
        suggestions = self._optimizer.suggest_optimization(
            stats.index_type, n, search_qps, memory_limit_mb, recall_target,
        )
        return [
            {
                "current_type": s.current_type,
                "suggested_type": s.suggested_type,
                "reason": s.reason,
                "expected_improvement": s.expected_improvement,
            }
            for s in suggestions
        ]

    def benchmark_index(
        self,
        vectors: Optional[List[List[float]]] = None,
        doc_ids: Optional[List[str]] = None,
    ) -> List[Dict[str, Any]]:
        if vectors is None or doc_ids is None:
            return []
        arr = np.array(vectors, dtype=np.float32)
        benchmarks = self._optimizer.benchmark_index_types(arr, doc_ids)
        return [
            {
                "index_type": b.index_type,
                "build_time_ms": b.build_time_ms,
                "search_time_ms": b.search_time_ms,
                "recall_at_10": b.recall_at_10,
                "memory_mb": b.memory_mb,
            }
            for b in benchmarks
        ]
