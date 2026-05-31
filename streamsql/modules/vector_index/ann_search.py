from __future__ import annotations

import heapq
import time
from dataclasses import dataclass, field
from typing import Any

import numpy as np

from streamsql.modules.vector_index.index_builder import DistanceMetric, IndexType, VectorIndex


@dataclass
class SearchResult:
    id: str
    score: float
    distance: float
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "score": self.score,
            "distance": self.distance,
            "metadata": self.metadata,
        }


class ANNSearch:
    def __init__(self, index: VectorIndex, ef_search: int = 50):
        self.index = index
        self.ef_search = ef_search
        self._last_search_time: float = 0.0
        self._total_searches: int = 0

    def search(
        self,
        query_vector: np.ndarray,
        k: int = 10,
        ef_search: int | None = None,
    ) -> list[SearchResult]:
        start_time = time.time()
        ef = ef_search or self.ef_search

        if len(self.index) == 0:
            return []

        if self.index.index_type == IndexType.FLAT or not self.index.built:
            results = self._flat_search(query_vector, k)
        elif self.index.index_type == IndexType.HNSW and self.index.hnsw_graph:
            results = self._hnsw_search(query_vector, k, ef)
        elif self.index.index_type in [IndexType.IVF, IndexType.IVF_PQ] and self.index.ivf_centroids is not None:
            results = self._ivf_search(query_vector, k, ef)
        else:
            results = self._flat_search(query_vector, k)

        self._last_search_time = time.time() - start_time
        self._total_searches += 1

        return results

    def search_batch(
        self,
        query_vectors: np.ndarray,
        k: int = 10,
        ef_search: int | None = None,
    ) -> list[list[SearchResult]]:
        return [self.search(qv, k, ef_search) for qv in query_vectors]

    def search_by_id(
        self,
        vector_id: str,
        k: int = 10,
        ef_search: int | None = None,
    ) -> list[SearchResult]:
        if vector_id not in self.index.ids:
            raise ValueError(f"Vector ID {vector_id} not found in index")

        idx = self.index.ids.index(vector_id)
        query_vector = self.index.vectors[idx]

        results = self.search(query_vector, k + 1, ef_search)
        return [r for r in results if r.id != vector_id][:k]

    def _flat_search(self, query_vector: np.ndarray, k: int) -> list[SearchResult]:
        vectors = self.index.vectors
        n = len(vectors)

        distances = np.zeros(n)
        for i in range(n):
            distances[i] = self._distance(query_vector, vectors[i])

        top_k_indices = np.argsort(distances)[:k]

        results: list[SearchResult] = []
        for idx in top_k_indices:
            dist = float(distances[idx])
            score = self._distance_to_score(dist)
            results.append(
                SearchResult(
                    id=self.index.ids[idx],
                    score=score,
                    distance=dist,
                    metadata=self.index.metadata[idx],
                )
            )

        return results

    def _hnsw_search(
        self, query_vector: np.ndarray, k: int, ef: int
    ) -> list[SearchResult]:
        graph = self.index.hnsw_graph or {}
        edges = graph.get("edges", {})
        entry_point = graph.get("entry_point", 0)

        if entry_point < 0 or len(edges) == 0:
            return self._flat_search(query_vector, k)

        visited: set[int] = set()
        candidates: list[tuple[float, int]] = []

        entry_dist = self._distance(query_vector, self.index.vectors[entry_point])
        heapq.heappush(candidates, (entry_dist, entry_point))
        visited.add(entry_point)

        while candidates:
            current_dist, current_idx = heapq.heappop(candidates)

            neighbors = edges.get(str(current_idx), [])
            for neighbor_idx in neighbors:
                if neighbor_idx in visited:
                    continue

                visited.add(neighbor_idx)
                neighbor_dist = self._distance(
                    query_vector, self.index.vectors[neighbor_idx]
                )
                if len(candidates) < ef:
                    heapq.heappush(candidates, (neighbor_dist, neighbor_idx))
                elif neighbor_dist < candidates[0][0]:
                    heapq.heapreplace(candidates, (neighbor_dist, neighbor_idx))

            if len(candidates) > ef * 2:
                break

        all_candidates = list(candidates)
        all_candidates.sort(key=lambda x: x[0])

        results: list[SearchResult] = []
        for dist, idx in all_candidates[:k]:
            score = self._distance_to_score(dist)
            results.append(
                SearchResult(
                    id=self.index.ids[idx],
                    score=score,
                    distance=dist,
                    metadata=self.index.metadata[idx],
                )
            )

        return results

    def _ivf_search(
        self, query_vector: np.ndarray, k: int, nprobe: int
    ) -> list[SearchResult]:
        centroids = self.index.ivf_centroids
        clusters = self.index.ivf_clusters

        if centroids is None or clusters is None:
            return self._flat_search(query_vector, k)

        nlist = len(centroids)
        nprobe = min(nprobe, nlist)

        centroid_distances = [
            self._distance(query_vector, centroids[i]) for i in range(nlist)
        ]
        closest_centroids = sorted(range(nlist), key=lambda i: centroid_distances[i])[:nprobe]

        candidate_indices: list[int] = []
        for centroid_idx in closest_centroids:
            candidate_indices.extend(clusters[centroid_idx])

        if not candidate_indices:
            return []

        candidate_vectors = self.index.vectors[candidate_indices]
        distances = np.array([
            self._distance(query_vector, v) for v in candidate_vectors
        ])

        top_k_local = np.argsort(distances)[:k]

        results: list[SearchResult] = []
        for local_idx in top_k_local:
            global_idx = candidate_indices[local_idx]
            dist = float(distances[local_idx])
            score = self._distance_to_score(dist)
            results.append(
                SearchResult(
                    id=self.index.ids[global_idx],
                    score=score,
                    distance=dist,
                    metadata=self.index.metadata[global_idx],
                )
            )

        return results

    def _distance(self, v1: np.ndarray, v2: np.ndarray) -> float:
        metric = self.index.metric
        if metric == DistanceMetric.EUCLIDEAN:
            return float(np.linalg.norm(v1 - v2))
        elif metric == DistanceMetric.COSINE:
            norm1 = np.linalg.norm(v1)
            norm2 = np.linalg.norm(v2)
            if norm1 == 0 or norm2 == 0:
                return 1.0
            return 1 - float(np.dot(v1, v2) / (norm1 * norm2))
        elif metric == DistanceMetric.INNER_PRODUCT:
            return -float(np.dot(v1, v2))
        return float(np.linalg.norm(v1 - v2))

    def _distance_to_score(self, distance: float) -> float:
        if self.index.metric == DistanceMetric.COSINE:
            return max(0.0, min(1.0, 1 - distance))
        elif self.index.metric == DistanceMetric.INNER_PRODUCT:
            return -distance
        else:
            return 1.0 / (1.0 + distance)

    def filter_search(
        self,
        query_vector: np.ndarray,
        filter_func,
        k: int = 10,
        ef_search: int | None = None,
    ) -> list[SearchResult]:
        ef = ef_search or self.ef_search
        expanded_k = k * 10

        results = self.search(query_vector, expanded_k, ef)

        filtered = [r for r in results if filter_func(r.metadata)]

        return filtered[:k]

    def range_search(
        self,
        query_vector: np.ndarray,
        radius: float,
        max_results: int = 100,
    ) -> list[SearchResult]:
        results = self.search(query_vector, max_results)
        return [r for r in results if r.distance <= radius]

    def explain(self, query_vector: np.ndarray, k: int = 5) -> dict[str, Any]:
        start_time = time.time()
        results = self.search(query_vector, k)
        search_time = time.time() - start_time

        return {
            "index_type": self.index.index_type.value,
            "dimension": self.index.dimension,
            "total_vectors": len(self.index),
            "search_time_ms": search_time * 1000,
            "ef_search": self.ef_search,
            "results_count": len(results),
            "top_results": [r.to_dict() for r in results],
        }

    @property
    def stats(self) -> dict[str, Any]:
        return {
            "index_id": self.index.index_id,
            "index_type": self.index.index_type.value,
            "dimension": self.index.dimension,
            "metric": self.index.metric.value,
            "total_vectors": len(self.index),
            "built": self.index.built,
            "created_at": self.index.created_at,
            "updated_at": self.index.updated_at,
            "total_searches": self._total_searches,
            "last_search_time_s": self._last_search_time,
        }
