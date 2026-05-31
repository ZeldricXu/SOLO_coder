import logging
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from src.domain.vector.embedding_index import EmbeddingIndex
from src.infrastructure.config.settings import VectorConfig

logger = logging.getLogger(__name__)


@dataclass
class SearchResult:
    doc_id: str
    score: float
    metadata: Dict[str, Any]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "doc_id": self.doc_id,
            "score": self.score,
            "metadata": self.metadata,
        }


@dataclass
class BatchSearchResult:
    query_results: List[List[SearchResult]]
    total_queries: int
    total_time_ms: float = 0.0


class ANNSearcher:
    def __init__(self, index: EmbeddingIndex, config: Optional[VectorConfig] = None):
        self._index = index
        self._config = config or VectorConfig()

    def search(
        self,
        query_vector: np.ndarray,
        top_k: int = 10,
        filters: Optional[Dict[str, Any]] = None,
        min_score: Optional[float] = None,
    ) -> List[SearchResult]:
        raw_results = self._index.search(query_vector, top_k * 2 if filters else top_k)

        results = []
        for doc_id, score, metadata in raw_results:
            if filters and not self._match_filters(metadata, filters):
                continue
            if min_score is not None and score > min_score:
                continue
            results.append(SearchResult(doc_id=doc_id, score=score, metadata=metadata))
            if len(results) >= top_k:
                break

        return results

    def batch_search(
        self,
        query_vectors: np.ndarray,
        top_k: int = 10,
        filters: Optional[List[Dict[str, Any]]] = None,
    ) -> BatchSearchResult:
        import time
        start = time.time()

        all_results = []
        for i, query in enumerate(query_vectors):
            query_filters = filters[i] if filters and i < len(filters) else None
            results = self.search(query, top_k, query_filters)
            all_results.append(results)

        elapsed = (time.time() - start) * 1000
        return BatchSearchResult(
            query_results=all_results,
            total_queries=len(query_vectors),
            total_time_ms=elapsed,
        )

    def hybrid_search(
        self,
        query_vector: np.ndarray,
        keyword_results: List[str],
        top_k: int = 10,
        vector_weight: float = 0.7,
        keyword_weight: float = 0.3,
    ) -> List[SearchResult]:
        vector_results = self.search(query_vector, top_k * 2)

        scored: Dict[str, float] = {}
        metas: Dict[str, Dict[str, Any]] = {}

        for i, result in enumerate(vector_results):
            score = vector_weight * (1.0 / (i + 1))
            scored[result.doc_id] = scored.get(result.doc_id, 0) + score
            metas[result.doc_id] = result.metadata

        for i, doc_id in enumerate(keyword_results):
            score = keyword_weight * (1.0 / (i + 1))
            scored[doc_id] = scored.get(doc_id, 0) + score
            if doc_id not in metas:
                metas[doc_id] = {}

        sorted_results = sorted(scored.items(), key=lambda x: x[1], reverse=True)[:top_k]
        return [
            SearchResult(doc_id=doc_id, score=score, metadata=metas[doc_id])
            for doc_id, score in sorted_results
        ]

    def range_search(
        self,
        query_vector: np.ndarray,
        max_distance: float,
        limit: int = 1000,
    ) -> List[SearchResult]:
        results = self.search(query_vector, limit)
        return [r for r in results if r.score <= max_distance]

    def _match_filters(self, metadata: Dict[str, Any], filters: Dict[str, Any]) -> bool:
        for key, expected in filters.items():
            actual = metadata.get(key)
            if isinstance(expected, list):
                if actual not in expected:
                    return False
            elif isinstance(expected, dict):
                for op, val in expected.items():
                    if op == "$gt" and not (actual is not None and actual > val):
                        return False
                    elif op == "$lt" and not (actual is not None and actual < val):
                        return False
                    elif op == "$gte" and not (actual is not None and actual >= val):
                        return False
                    elif op == "$lte" and not (actual is not None and actual <= val):
                        return False
                    elif op == "$ne" and actual == val:
                        return False
                    elif op == "$in" and actual not in val:
                        return False
                    elif op == "$contains" and (actual is None or val not in str(actual)):
                        return False
            else:
                if actual != expected:
                    return False
        return True
