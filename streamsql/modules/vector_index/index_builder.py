from __future__ import annotations

import pickle
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional

import numpy as np

from streamsql.core.models import generate_id
from streamsql.modules.vector_index.embedding import EmbeddingService


class IndexType(str, Enum):
    FLAT = "flat"
    HNSW = "hnsw"
    IVF = "ivf"
    IVF_PQ = "ivf_pq"


class DistanceMetric(str, Enum):
    EUCLIDEAN = "euclidean"
    COSINE = "cosine"
    INNER_PRODUCT = "inner_product"


@dataclass
class VectorIndex:
    index_id: str = field(default_factory=lambda: generate_id("vidx"))
    index_type: IndexType = IndexType.FLAT
    dimension: int = 0
    metric: DistanceMetric = DistanceMetric.COSINE
    vectors: np.ndarray = field(default_factory=lambda: np.array([]))
    ids: list[str] = field(default_factory=list)
    metadata: list[dict[str, Any]] = field(default_factory=list)
    built: bool = False
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    hnsw_graph: Optional[dict[str, Any]] = None
    ivf_centroids: Optional[np.ndarray] = None
    ivf_clusters: Optional[list[list[int]]] = None

    def __len__(self) -> int:
        return len(self.ids)

    def save(self, path: str) -> None:
        with open(path, "wb") as f:
            pickle.dump(self, f)

    @classmethod
    def load(cls, path: str) -> "VectorIndex":
        with open(path, "rb") as f:
            return pickle.load(f)


class VectorIndexBuilder:
    def __init__(
        self,
        dimension: int = 1536,
        index_type: IndexType = IndexType.HNSW,
        metric: DistanceMetric = DistanceMetric.COSINE,
        embedding_service: Optional[EmbeddingService] = None,
    ):
        self.dimension = dimension
        self.index_type = index_type
        self.metric = metric
        self.embedding_service = embedding_service

    def create_index(
        self,
        vectors: np.ndarray,
        ids: Optional[list[str]] = None,
        metadata: Optional[list[dict[str, Any]]] = None,
    ) -> VectorIndex:
        if len(vectors.shape) != 2:
            raise ValueError("Vectors must be 2D array")

        if vectors.shape[1] != self.dimension:
            raise ValueError(
                f"Vector dimension mismatch: expected {self.dimension}, got {vectors.shape[1]}"
            )

        n = vectors.shape[0]
        if ids is None:
            ids = [str(i) for i in range(n)]
        if metadata is None:
            metadata = [{} for _ in range(n)]

        if len(ids) != n or len(metadata) != n:
            raise ValueError("Length mismatch between vectors, ids, and metadata")

        index = VectorIndex(
            index_type=self.index_type,
            dimension=self.dimension,
            metric=self.metric,
            vectors=vectors.copy(),
            ids=ids,
            metadata=metadata,
            built=False,
        )

        if self.index_type == IndexType.HNSW:
            self._build_hnsw(index)
        elif self.index_type == IndexType.IVF or self.index_type == IndexType.IVF_PQ:
            self._build_ivf(index)

        index.built = True
        index.updated_at = time.time()

        return index

    def add_vectors(
        self,
        index: VectorIndex,
        vectors: np.ndarray,
        ids: Optional[list[str]] = None,
        metadata: Optional[list[dict[str, Any]]] = None,
    ) -> VectorIndex:
        if vectors.shape[1] != index.dimension:
            raise ValueError(
                f"Vector dimension mismatch: expected {index.dimension}, got {vectors.shape[1]}"
            )

        n = vectors.shape[0]
        if ids is None:
            start_id = len(index.ids)
            ids = [str(start_id + i) for i in range(n)]
        if metadata is None:
            metadata = [{} for _ in range(n)]

        if index.vectors.size == 0:
            index.vectors = vectors
        else:
            index.vectors = np.vstack([index.vectors, vectors])

        index.ids.extend(ids)
        index.metadata.extend(metadata)
        index.built = False

        if self.index_type == IndexType.HNSW and index.hnsw_graph:
            self._build_hnsw(index)
        elif self.index_type in [IndexType.IVF, IndexType.IVF_PQ] and index.ivf_centroids is not None:
            self._build_ivf(index)

        index.built = True
        index.updated_at = time.time()

        return index

    def _build_hnsw(self, index: VectorIndex, M: int = 16, ef_construction: int = 100) -> None:
        n = len(index.ids)
        if n == 0:
            return

        graph: dict[int, list[int]] = {i: [] for i in range(n)}

        for i in range(n):
            candidates = list(range(max(0, i - ef_construction), i))
            distances = [
                self._distance(index.vectors[i], index.vectors[j])
                for j in candidates
            ]

            sorted_indices = sorted(range(len(candidates)), key=lambda k: distances[k])
            neighbors = [candidates[idx] for idx in sorted_indices[:M]]

            graph[i] = neighbors
            for neighbor in neighbors:
                if len(graph[neighbor]) < M:
                    graph[neighbor].append(i)

        index.hnsw_graph = {
            "M": M,
            "ef_construction": ef_construction,
            "edges": {str(k): v for k, v in graph.items()},
            "entry_point": 0 if n > 0 else -1,
        }

    def _build_ivf(self, index: VectorIndex, nlist: int = 100) -> None:
        n = len(index.ids)
        if n < nlist:
            nlist = max(1, n // 2)

        if n == 0:
            return

        rng = np.random.RandomState(42)
        centroids_indices = rng.choice(n, size=nlist, replace=False)
        centroids = index.vectors[centroids_indices].copy()

        for _ in range(10):
            clusters: list[list[int]] = [[] for _ in range(nlist)]
            for i in range(n):
                distances = [
                    self._distance(index.vectors[i], centroids[j])
                    for j in range(nlist)
                ]
                closest = np.argmin(distances)
                clusters[closest].append(i)

            for j in range(nlist):
                if clusters[j]:
                    cluster_vectors = index.vectors[clusters[j]]
                    centroids[j] = cluster_vectors.mean(axis=0)

        index.ivf_centroids = centroids
        index.ivf_clusters = clusters

    def _distance(self, v1: np.ndarray, v2: np.ndarray) -> float:
        if self.metric == DistanceMetric.EUCLIDEAN:
            return float(np.linalg.norm(v1 - v2))
        elif self.metric == DistanceMetric.COSINE:
            return 1 - float(np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2)))
        elif self.metric == DistanceMetric.INNER_PRODUCT:
            return -float(np.dot(v1, v2))
        return float(np.linalg.norm(v1 - v2))

    def build_from_documents(
        self,
        documents: list[dict[str, Any]],
        text_fields: Optional[list[str]] = None,
    ) -> VectorIndex:
        if self.embedding_service is None:
            raise ValueError("Embedding service required for building from documents")

        vectors, ids = self.embedding_service.encode_documents(documents, text_fields)
        return self.create_index(vectors, ids, documents)

    def merge_indexes(self, indexes: list[VectorIndex]) -> VectorIndex:
        if not indexes:
            raise ValueError("No indexes to merge")

        first = indexes[0]
        all_vectors = np.vstack([idx.vectors for idx in indexes])
        all_ids = [id_ for idx in indexes for id_ in idx.ids]
        all_metadata = [meta for idx in indexes for meta in idx.metadata]

        return self.create_index(all_vectors, all_ids, all_metadata)
