"""Approximate Nearest Neighbor search optimizer."""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager
from .embedding_index import VectorIndex, DistanceMetric, IndexType


@dataclass
class SearchResult:
    id: UUID
    score: float
    metadata: Dict[str, Any]
    rank: int
    timestamp: float = field(default_factory=lambda: __import__("time").time())

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": str(self.id),
            "score": float(self.score),
            "metadata": self.metadata,
            "rank": self.rank,
            "timestamp": self.timestamp,
        }


@dataclass
class SearchStats:
    query_count: int = 0
    total_time_ms: float = 0.0
    avg_time_ms: float = 0.0
    min_time_ms: float = float("inf")
    max_time_ms: float = 0.0
    cache_hits: int = 0
    cache_misses: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "query_count": self.query_count,
            "total_time_ms": self.total_time_ms,
            "avg_time_ms": self.avg_time_ms,
            "min_time_ms": self.min_time_ms if self.min_time_ms != float("inf") else 0,
            "max_time_ms": self.max_time_ms,
            "cache_hits": self.cache_hits,
            "cache_misses": self.cache_misses,
            "cache_hit_rate": self.cache_hits / self.query_count if self.query_count else 0,
        }


@dataclass
class RerankConfig:
    enabled: bool = False
    method: str = "cross_encoder"
    model_name: Optional[str] = None
    top_k: int = 100


@dataclass
class CacheConfig:
    enabled: bool = True
    max_size: int = 10000
    ttl_seconds: int = 3600
    similarity_threshold: float = 0.99


class ANNSearcher:
    def __init__(
        self,
        index: Optional[VectorIndex] = None,
        cache_config: Optional[CacheConfig] = None,
        rerank_config: Optional[RerankConfig] = None,
    ) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._index = index
        self._cache_config = cache_config or CacheConfig()
        self._rerank_config = rerank_config or RerankConfig()
        self._cache: Dict[str, Tuple[List[SearchResult], float]] = {}
        self._stats = SearchStats()
        self._query_history: List[Dict[str, Any]] = []

    @property
    def index(self) -> Optional[VectorIndex]:
        return self._index

    @index.setter
    def index(self, index: VectorIndex) -> None:
        self._index = index

    @property
    def stats(self) -> SearchStats:
        return self._stats

    def search(
        self,
        query_vector: List[float],
        top_k: int = 10,
        use_cache: Optional[bool] = None,
        enable_rerank: Optional[bool] = None,
        **kwargs: Any,
    ) -> List[SearchResult]:
        if not self._index:
            raise ValidationError(
                message="No vector index configured",
                suggestion="Set the index property before searching.",
            )

        start_time = time.time()

        use_cache = use_cache if use_cache is not None else self._cache_config.enabled
        enable_rerank = enable_rerank if enable_rerank is not None else self._rerank_config.enabled

        cached_results = None
        if use_cache:
            cached_results = self._check_cache(query_vector, top_k)
            if cached_results:
                self._stats.cache_hits += 1
                elapsed = (time.time() - start_time) * 1000
                self._update_stats(elapsed)
                self._logger.info(f"Cache hit for query, returned {len(cached_results)} results in {elapsed:.2f}ms")
                return cached_results

        self._stats.cache_misses += 1

        raw_results = self._index.search(query_vector, top_k * 2 if enable_rerank else top_k, **kwargs)

        search_results = [
            SearchResult(
                id=result_id,
                score=float(score),
                metadata=metadata,
                rank=i + 1,
            )
            for i, (result_id, score, metadata) in enumerate(raw_results)
        ]

        if enable_rerank:
            search_results = self._rerank(query_vector, search_results, top_k)

        search_results = search_results[:top_k]
        for i, result in enumerate(search_results):
            result.rank = i + 1

        if use_cache:
            self._cache_result(query_vector, top_k, search_results)

        elapsed = (time.time() - start_time) * 1000
        self._update_stats(elapsed)

        self._record_query(query_vector, top_k, search_results, elapsed, use_cache, enable_rerank)

        self._logger.info(
            f"Search completed: {len(search_results)} results in {elapsed:.2f}ms",
            top_k=top_k,
            index_type=self._index.index_type.value,
        )

        return search_results

    def batch_search(
        self,
        query_vectors: List[List[float]],
        top_k: int = 10,
        **kwargs: Any,
    ) -> List[List[SearchResult]]:
        if not self._index:
            raise ValidationError(
                message="No vector index configured",
                suggestion="Set the index property before searching.",
            )

        self._logger.info(f"Performing batch search for {len(query_vectors)} queries")

        results = []
        for i, query_vector in enumerate(query_vectors):
            query_results = self.search(query_vector, top_k, **kwargs)
            results.append(query_results)

        return results

    def _check_cache(
        self,
        query_vector: List[float],
        top_k: int,
    ) -> Optional[List[SearchResult]]:
        if not self._cache_config.enabled:
            return None

        cache_key = self._get_cache_key(query_vector, top_k)
        if cache_key in self._cache:
            results, timestamp = self._cache[cache_key]
            if time.time() - timestamp < self._cache_config.ttl_seconds:
                return results
            else:
                del self._cache[cache_key]

        for key, (cached_results, cached_timestamp) in list(self._cache.items()):
            cached_vector_str, cached_top_k = key.rsplit("_", 1)
            if int(cached_top_k) < top_k:
                continue

            cached_vector = self._vector_from_string(cached_vector_str)
            similarity = self._cosine_similarity(query_vector, cached_vector)

            if similarity >= self._cache_config.similarity_threshold:
                if time.time() - cached_timestamp < self._cache_config.ttl_seconds:
                    return cached_results[:top_k]

        return None

    def _get_cache_key(self, query_vector: List[float], top_k: int) -> str:
        vector_str = self._vector_to_string(query_vector)
        return f"{vector_str}_{top_k}"

    def _vector_to_string(self, vector: List[float]) -> str:
        return "_".join(f"{v:.6f}" for v in vector)

    def _vector_from_string(self, s: str) -> List[float]:
        return [float(x) for x in s.split("_")]

    def _cosine_similarity(self, a: List[float], b: List[float]) -> float:
        dot = sum(x * y for x, y in zip(a, b))
        norm_a = sum(x * x for x in a) ** 0.5
        norm_b = sum(x * x for x in b) ** 0.5
        if norm_a == 0 or norm_b == 0:
            return 0.0
        return dot / (norm_a * norm_b)

    def _cache_result(
        self,
        query_vector: List[float],
        top_k: int,
        results: List[SearchResult],
    ) -> None:
        if len(self._cache) >= self._cache_config.max_size:
            oldest_key = min(self._cache.keys(), key=lambda k: self._cache[k][1])
            del self._cache[oldest_key]

        cache_key = self._get_cache_key(query_vector, top_k)
        self._cache[cache_key] = (results, time.time())

    def _rerank(
        self,
        query_vector: List[float],
        results: List[SearchResult],
        top_k: int,
    ) -> List[SearchResult]:
        if self._rerank_config.method == "cosine_reorder":
            return self._rerank_cosine(query_vector, results)
        elif self._rerank_config.method == "metadata_boost":
            return self._rerank_metadata_boost(query_vector, results)
        else:
            return results

    def _rerank_cosine(
        self,
        query_vector: List[float],
        results: List[SearchResult],
    ) -> List[SearchResult]:
        if not self._index:
            return results

        reranked = []
        for result in results:
            record = self._index.get_vector(result.id)
            if record:
                distance = self._index._distance(query_vector, record.vector)
                reranked.append((distance, result))
            else:
                reranked.append((result.score, result))

        reranked.sort(key=lambda x: x[0])

        final_results = []
        for i, (distance, result) in enumerate(reranked):
            result.score = float(distance)
            final_results.append(result)

        return final_results

    def _rerank_metadata_boost(
        self,
        query_vector: List[float],
        results: List[SearchResult],
    ) -> List[SearchResult]:
        reranked = []
        for result in results:
            boost = 0.0
            importance = result.metadata.get("importance", 0.0)
            boost -= importance * 0.1

            quality = result.metadata.get("quality", 0.0)
            boost -= quality * 0.05

            adjusted_score = result.score + boost
            reranked.append((adjusted_score, result))

        reranked.sort(key=lambda x: x[0])

        final_results = []
        for i, (adjusted_score, result) in enumerate(reranked):
            result.score = adjusted_score
            final_results.append(result)

        return final_results

    def _update_stats(self, elapsed_ms: float) -> None:
        self._stats.query_count += 1
        self._stats.total_time_ms += elapsed_ms
        self._stats.avg_time_ms = self._stats.total_time_ms / self._stats.query_count
        self._stats.min_time_ms = min(self._stats.min_time_ms, elapsed_ms)
        self._stats.max_time_ms = max(self._stats.max_time_ms, elapsed_ms)

    def _record_query(
        self,
        query_vector: List[float],
        top_k: int,
        results: List[SearchResult],
        elapsed_ms: float,
        used_cache: bool,
        used_rerank: bool,
    ) -> None:
        self._query_history.append({
            "id": str(uuid4()),
            "timestamp": time.time(),
            "top_k": top_k,
            "num_results": len(results),
            "elapsed_ms": elapsed_ms,
            "used_cache": used_cache,
            "used_rerank": used_rerank,
            "scores": [r.score for r in results[:5]],
            "avg_score": sum(r.score for r in results) / len(results) if results else 0,
        })

        if len(self._query_history) > 1000:
            self._query_history = self._query_history[-1000:]

    def explain_search(
        self,
        query_vector: List[float],
        top_k: int = 10,
        **kwargs: Any,
    ) -> Dict[str, Any]:
        if not self._index:
            raise ValidationError(
                message="No vector index configured",
                suggestion="Set the index property before searching.",
            )

        start_time = time.time()

        raw_results = self._index.search(query_vector, top_k, **kwargs)

        search_results = [
            SearchResult(
                id=result_id,
                score=float(score),
                metadata=metadata,
                rank=i + 1,
            )
            for i, (result_id, score, metadata) in enumerate(raw_results)
        ]

        elapsed = (time.time() - start_time) * 1000

        explanation = {
            "index_type": self._index.index_type.value,
            "distance_metric": self._index.distance_metric.value,
            "dimension": self._index.dimension,
            "index_size": self._index.size,
            "top_k": top_k,
            "num_results": len(search_results),
            "search_time_ms": elapsed,
            "index_params": self._index.get_stats(),
            "results": [r.to_dict() for r in search_results],
            "score_distribution": {
                "min": min(r.score for r in search_results) if search_results else 0,
                "max": max(r.score for r in search_results) if search_results else 0,
                "avg": sum(r.score for r in search_results) / len(search_results) if search_results else 0,
            },
        }

        if self._index.index_type in (IndexType.IVF, IndexType.IVF_PQ):
            cluster_distances = [
                self._index._distance(query_vector, c.centroid)
                for c in self._index._clusters
            ]
            cluster_distances.sort()
            explanation["cluster_distances"] = cluster_distances[:10]

        if self._cache_config.enabled:
            explanation["cache"] = {
                "enabled": True,
                "size": len(self._cache),
                "max_size": self._cache_config.max_size,
                "ttl_seconds": self._cache_config.ttl_seconds,
            }

        return explanation

    def filter_by_metadata(
        self,
        results: List[SearchResult],
        filter_conditions: Dict[str, Any],
    ) -> List[SearchResult]:
        filtered = []
        for result in results:
            match = True
            for key, value in filter_conditions.items():
                if key not in result.metadata:
                    match = False
                    break

                metadata_value = result.metadata[key]

                if isinstance(value, tuple) and len(value) == 2:
                    op, target = value
                    if op == ">":
                        if not metadata_value > target:
                            match = False
                    elif op == ">=":
                        if not metadata_value >= target:
                            match = False
                    elif op == "<":
                        if not metadata_value < target:
                            match = False
                    elif op == "<=":
                        if not metadata_value <= target:
                            match = False
                    elif op == "!=":
                        if not metadata_value != target:
                            match = False
                    elif op == "in":
                        if metadata_value not in target:
                            match = False
                    elif op == "contains":
                        if target not in metadata_value:
                            match = False
                else:
                    if metadata_value != value:
                        match = False

                if not match:
                    break

            if match:
                filtered.append(result)

        for i, result in enumerate(filtered):
            result.rank = i + 1

        return filtered

    def hybrid_search(
        self,
        query_vector: List[float],
        text_query: Optional[str] = None,
        top_k: int = 10,
        vector_weight: float = 0.7,
        text_weight: float = 0.3,
        **kwargs: Any,
    ) -> List[SearchResult]:
        vector_results = self.search(query_vector, top_k * 2, **kwargs)

        if not text_query:
            return vector_results[:top_k]

        text_scores = {}
        for result in vector_results:
            text_score = self._compute_text_score(text_query, result.metadata)
            text_scores[result.id] = text_score

        max_vector_score = max(r.score for r in vector_results) if vector_results else 1
        max_text_score = max(text_scores.values()) if text_scores else 1

        normalized_results = []
        for result in vector_results:
            normalized_vector = result.score / max_vector_score if max_vector_score > 0 else 0
            normalized_text = text_scores.get(result.id, 0) / max_text_score if max_text_score > 0 else 0

            hybrid_score = (vector_weight * normalized_vector) + (text_weight * normalized_text)
            result.score = hybrid_score
            normalized_results.append(result)

        normalized_results.sort(key=lambda r: r.score, reverse=True)

        final_results = normalized_results[:top_k]
        for i, result in enumerate(final_results):
            result.rank = i + 1

        return final_results

    def _compute_text_score(self, text_query: str, metadata: Dict[str, Any]) -> float:
        query_terms = set(text_query.lower().split())
        score = 0.0

        for key, value in metadata.items():
            if isinstance(value, str):
                value_terms = set(value.lower().split())
                overlap = len(query_terms & value_terms)
                score += overlap / len(query_terms) if query_terms else 0
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, str):
                        item_terms = set(item.lower().split())
                        overlap = len(query_terms & item_terms)
                        score += overlap / len(query_terms) if query_terms else 0

        return score

    def get_query_history(
        self,
        limit: Optional[int] = None,
        **kwargs: Any,
    ) -> List[Dict[str, Any]]:
        history = sorted(self._query_history, key=lambda x: x["timestamp"], reverse=True)

        if "min_time_ms" in kwargs:
            history = [h for h in history if h["elapsed_ms"] >= kwargs["min_time_ms"]]

        if "max_time_ms" in kwargs:
            history = [h for h in history if h["elapsed_ms"] <= kwargs["max_time_ms"]]

        if limit:
            history = history[:limit]

        return history

    def clear_cache(self) -> int:
        count = len(self._cache)
        self._cache.clear()
        self._logger.info(f"Cleared {count} entries from search cache")
        return count

    def reset_stats(self) -> None:
        self._stats = SearchStats()
        self._logger.info("Search statistics reset")

    def get_performance_report(self) -> Dict[str, Any]:
        return {
            "stats": self._stats.to_dict(),
            "cache": {
                "size": len(self._cache),
                "max_size": self._cache_config.max_size,
                "hit_rate": self._stats.cache_hits / self._stats.query_count if self._stats.query_count else 0,
            },
            "queries": {
                "total": self._stats.query_count,
                "recent": len(self._query_history),
            },
            "index": self._index.get_stats() if self._index else None,
        }

    def optimize_search_params(
        self,
        query_vectors: List[List[float]],
        ground_truth: List[List[UUID]],
        top_k: int = 10,
    ) -> Dict[str, Any]:
        if not self._index:
            raise ValidationError(
                message="No vector index configured",
                suggestion="Set the index property before optimizing.",
            )

        original_ef_search = None
        original_nprobe = None

        if self._index.index_type == IndexType.HNSW:
            original_ef_search = self._index._ef_search

        if self._index.index_type in (IndexType.IVF, IndexType.IVF_PQ):
            original_nprobe = self._index._nprobe

        results = []

        param_ranges = self._get_param_ranges()
        for params in param_ranges:
            if self._index.index_type == IndexType.HNSW and "ef_search" in params:
                self._index._ef_search = params["ef_search"]

            if self._index.index_type in (IndexType.IVF, IndexType.IVF_PQ) and "nprobe" in params:
                self._index._nprobe = params["nprobe"]

            total_recall = 0.0
            total_time = 0.0

            for query_vector, true_ids in zip(query_vectors, ground_truth):
                start = time.time()
                search_results = self.search(query_vector, top_k, use_cache=False)
                elapsed = time.time() - start

                found_ids = {r.id for r in search_results}
                true_ids_set = set(true_ids)
                recall = len(found_ids & true_ids_set) / len(true_ids_set) if true_ids_set else 0

                total_recall += recall
                total_time += elapsed

            avg_recall = total_recall / len(query_vectors)
            avg_time_ms = (total_time / len(query_vectors)) * 1000

            results.append({
                "params": params,
                "avg_recall": avg_recall,
                "avg_time_ms": avg_time_ms,
                "f1_score": 2 * (avg_recall * (1 / (avg_time_ms + 1))) / (avg_recall + (1 / (avg_time_ms + 1))) if (avg_recall + avg_time_ms) > 0 else 0,
            })

        if original_ef_search is not None:
            self._index._ef_search = original_ef_search
        if original_nprobe is not None:
            self._index._nprobe = original_nprobe

        results.sort(key=lambda x: x["f1_score"], reverse=True)

        return {
            "best_params": results[0]["params"] if results else {},
            "all_results": results[:10],
            "optimization_target": "f1_score",
        }

    def _get_param_ranges(self) -> List[Dict[str, Any]]:
        if not self._index:
            return [{}]

        params = []

        if self._index.index_type == IndexType.HNSW:
            for ef in [10, 20, 30, 50, 100, 200, 500]:
                params.append({"ef_search": ef})

        elif self._index.index_type in (IndexType.IVF, IndexType.IVF_PQ):
            for nprobe in [1, 2, 5, 10, 20, 50, 100]:
                params.append({"nprobe": nprobe})

        else:
            params.append({})

        return params

    def save_search_results(
        self,
        results: List[SearchResult],
        filepath: str,
    ) -> None:
        import json

        data = [r.to_dict() for r in results]
        with open(filepath, "w") as f:
            json.dump(data, f, indent=2, default=str)

        self._logger.info(f"Saved {len(results)} search results to {filepath}")
