"""
HNSW近似最近邻索引实现，基于Faiss
"""
from typing import List, Optional, Dict, Any
import numpy as np
import faiss
import pickle
import os

from .index_base import VectorIndexBase, SearchResult, MetricType


class HNSWIndex(VectorIndexBase):
    def __init__(
        self,
        dimension: int,
        metric: MetricType = MetricType.COSINE,
        index_params: Optional[Dict[str, Any]] = None,
    ):
        super().__init__(dimension, metric, index_params)
        self._index: Optional[faiss.IndexHNSW] = None
        self._storage_vectors: Optional[np.ndarray] = None
        self._deleted_mask: Optional[np.ndarray] = None
        self._m = self.index_params.get("m", 16)
        self._ef_construction = self.index_params.get("ef_construction", 200)
        self._ef_search = self.index_params.get("ef_search", 50)
        self._init_index()

    def _init_index(self) -> None:
        if self.metric == MetricType.L2:
            self._index = faiss.IndexHNSWFlat(self.dimension, self._m, faiss.METRIC_L2)
        elif self.metric in [MetricType.INNER_PRODUCT, MetricType.COSINE]:
            self._index = faiss.IndexHNSWFlat(self.dimension, self._m, faiss.METRIC_INNER_PRODUCT)
        else:
            raise ValueError(f"Unsupported metric: {self.metric}")
        self._index.hnsw.efConstruction = self._ef_construction
        self._index.hnsw.efSearch = self._ef_search

    def set_ef_search(self, ef_search: int) -> None:
        if self._index is not None:
            self._index.hnsw.efSearch = ef_search
            self._ef_search = ef_search

    def build(self, vectors: np.ndarray, ids: Optional[List[int]] = None) -> None:
        vectors = self._validate_vectors(vectors)
        vectors = self._normalize_vector(vectors)
        count = vectors.shape[0]
        generated_ids = self._generate_ids(count, ids)
        self._init_index()
        self._index.add(vectors)
        self._storage_vectors = vectors.copy()
        self._deleted_mask = np.zeros(count, dtype=bool)
        self._register_ids(generated_ids, list(range(count)))
        self._is_trained = True

    def add(
        self,
        vectors: np.ndarray,
        ids: Optional[List[int]] = None,
        metadata: Optional[List[Dict[str, Any]]] = None,
    ) -> List[int]:
        vectors = self._validate_vectors(vectors)
        vectors = self._normalize_vector(vectors)
        count = vectors.shape[0]
        generated_ids = self._generate_ids(count, ids)
        if self._index is None:
            self._init_index()
        current_size = self._index.ntotal
        self._index.add(vectors)
        if self._storage_vectors is None:
            self._storage_vectors = vectors.copy()
            self._deleted_mask = np.zeros(count, dtype=bool)
        else:
            self._storage_vectors = np.vstack([self._storage_vectors, vectors])
            self._deleted_mask = np.append(self._deleted_mask, np.zeros(count, dtype=bool))
        internal_indices = list(range(current_size, current_size + count))
        self._register_ids(generated_ids, internal_indices)
        if metadata is not None:
            for id, meta in zip(generated_ids, metadata):
                self._metadata[id] = meta
        self._is_trained = True
        return generated_ids

    def search(
        self,
        query: np.ndarray,
        k: int = 10,
        filter_func: Optional[callable] = None,
        **kwargs: Any,
    ) -> SearchResult:
        if not self._is_trained or self._index is None:
            raise RuntimeError("Index not built or trained")
        query = self._validate_vectors(query)
        query = self._normalize_vector(query)
        ef_search = kwargs.get("ef_search", self._ef_search)
        self._index.hnsw.efSearch = ef_search
        search_k = k * 3 if filter_func is not None else k
        distances, indices = self._index.search(query, search_k)
        valid_ids = []
        valid_distances = []
        valid_vectors = []
        valid_metadata = []
        for dist, idx in zip(distances[0], indices[0]):
            if idx == -1:
                continue
            if self._deleted_mask is not None and self._deleted_mask[idx]:
                continue
            external_id = None
            for ext_id, int_idx in self._id_map.items():
                if int_idx == idx:
                    external_id = ext_id
                    break
            if external_id is None:
                continue
            if filter_func is not None:
                meta = self._metadata.get(external_id, {})
                if not filter_func(external_id, meta):
                    continue
            valid_ids.append(external_id)
            valid_distances.append(float(dist))
            if self._storage_vectors is not None:
                valid_vectors.append(self._storage_vectors[idx])
            valid_metadata.append(self._metadata.get(external_id, {}))
            if len(valid_ids) >= k:
                break
        if self.metric == MetricType.COSINE:
            valid_distances = [1.0 - d for d in valid_distances]
        result_vectors = np.array(valid_vectors) if valid_vectors else None
        return SearchResult(
            ids=valid_ids,
            distances=valid_distances,
            vectors=result_vectors,
            metadata=valid_metadata,
        )

    def delete(self, ids: List[int]) -> bool:
        if not self._is_trained or self._deleted_mask is None:
            return False
        for id in ids:
            if id not in self._id_map:
                continue
            internal_idx = self._id_map[id]
            if internal_idx < len(self._deleted_mask):
                self._deleted_mask[internal_idx] = True
            del self._id_map[id]
            if id in self._metadata:
                del self._metadata[id]
        return True

    def update(
        self,
        ids: List[int],
        vectors: np.ndarray,
        metadata: Optional[List[Dict[str, Any]]] = None,
    ) -> bool:
        vectors = self._validate_vectors(vectors)
        if len(ids) != vectors.shape[0]:
            raise ValueError("IDs and vectors count mismatch")
        if metadata is not None and len(metadata) != len(ids):
            raise ValueError("IDs and metadata count mismatch")
        self.delete(ids)
        self.add(vectors, ids, metadata)
        return True

    def get_vector(self, id: int) -> Optional[np.ndarray]:
        if id not in self._id_map or self._storage_vectors is None:
            return None
        internal_idx = self._id_map[id]
        if self._deleted_mask is not None and self._deleted_mask[internal_idx]:
            return None
        return self._storage_vectors[internal_idx].copy()

    def save(self, path: str) -> None:
        if self._index is None:
            raise RuntimeError("Index not initialized")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        faiss.write_index(self._index, f"{path}.faiss")
        state = {
            "dimension": self.dimension,
            "metric": self.metric,
            "index_params": self.index_params,
            "_is_trained": self._is_trained,
            "_metadata": self._metadata,
            "_id_map": self._id_map,
            "_next_id": self._next_id,
            "_storage_vectors": self._storage_vectors,
            "_deleted_mask": self._deleted_mask,
            "_m": self._m,
            "_ef_construction": self._ef_construction,
            "_ef_search": self._ef_search,
        }
        with open(f"{path}.pkl", "wb") as f:
            pickle.dump(state, f)

    def load(self, path: str) -> None:
        self._index = faiss.read_index(f"{path}.faiss")
        with open(f"{path}.pkl", "rb") as f:
            state = pickle.load(f)
        self.dimension = state["dimension"]
        self.metric = state["metric"]
        self.index_params = state["index_params"]
        self._is_trained = state["_is_trained"]
        self._metadata = state["_metadata"]
        self._id_map = state["_id_map"]
        self._next_id = state["_next_id"]
        self._storage_vectors = state["_storage_vectors"]
        self._deleted_mask = state["_deleted_mask"]
        self._m = state["_m"]
        self._ef_construction = state["_ef_construction"]
        self._ef_search = state["_ef_search"]
        self._index.hnsw.efConstruction = self._ef_construction
        self._index.hnsw.efSearch = self._ef_search

    def size(self) -> int:
        if self._deleted_mask is None:
            return 0
        return int(np.sum(~self._deleted_mask))

    def clear(self) -> None:
        self._init_index()
        self._storage_vectors = None
        self._deleted_mask = None
        self._is_trained = False
        self._metadata = {}
        self._id_map = {}
        self._next_id = 0
