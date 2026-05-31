"""
Annoy树索引实现
"""
from typing import List, Optional, Dict, Any
import numpy as np
import pickle
import os
from annoy import AnnoyIndex

from .index_base import VectorIndexBase, SearchResult, MetricType


class AnnoyVectorIndex(VectorIndexBase):
    def __init__(
        self,
        dimension: int,
        metric: MetricType = MetricType.COSINE,
        index_params: Optional[Dict[str, Any]] = None,
    ):
        super().__init__(dimension, metric, index_params)
        self._index: Optional[AnnoyIndex] = None
        self._storage_vectors: Optional[np.ndarray] = None
        self._deleted_mask: Optional[np.ndarray] = None
        self._n_trees = self.index_params.get("n_trees", 50)
        self._search_k = self.index_params.get("search_k", -1)
        self._annoy_metric = self._map_metric()

    def _map_metric(self) -> str:
        if self.metric == MetricType.L2:
            return "euclidean"
        elif self.metric in [MetricType.INNER_PRODUCT, MetricType.COSINE]:
            return "angular"
        else:
            raise ValueError(f"Unsupported metric: {self.metric}")

    def _init_index(self) -> None:
        self._index = AnnoyIndex(self.dimension, self._annoy_metric)
        self._index.on_disk_build = False

    def build(self, vectors: np.ndarray, ids: Optional[List[int]] = None) -> None:
        vectors = self._validate_vectors(vectors)
        vectors = self._normalize_vector(vectors)
        count = vectors.shape[0]
        generated_ids = self._generate_ids(count, ids)
        self._init_index()
        for i, vec in enumerate(vectors):
            self._index.add_item(i, vec.tolist())
        self._index.build(self._n_trees)
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
            current_size = 0
        else:
            current_size = self._index.get_n_items()
            existing_vectors = []
            for i in range(current_size):
                if self._deleted_mask is None or not self._deleted_mask[i]:
                    existing_vectors.append(self._index.get_item_vector(i))
            self._init_index()
            for i, vec in enumerate(existing_vectors):
                self._index.add_item(i, vec)
            for i, vec in enumerate(vectors):
                self._index.add_item(current_size + i, vec.tolist())
            self._index.build(self._n_trees)
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
        search_k = kwargs.get("search_k", self._search_k)
        query_vec = query[0].tolist()
        search_n = k * 3 if filter_func is not None else k
        indices, distances = self._index.get_nns_by_vector(
            query_vec, search_n, search_k=search_k, include_distances=True
        )
        valid_ids = []
        valid_distances = []
        valid_vectors = []
        valid_metadata = []
        for idx, dist in zip(indices, distances):
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
            if self.metric == MetricType.COSINE:
                similarity = 1.0 - (dist ** 2) / 2.0
                valid_distances.append(similarity)
            elif self.metric == MetricType.INNER_PRODUCT:
                valid_distances.append(-dist)
            else:
                valid_distances.append(float(dist))
            if self._storage_vectors is not None:
                valid_vectors.append(self._storage_vectors[idx])
            valid_metadata.append(self._metadata.get(external_id, {}))
            if len(valid_ids) >= k:
                break
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
        if id not in self._id_map or self._index is None:
            return None
        internal_idx = self._id_map[id]
        if self._deleted_mask is not None and self._deleted_mask[internal_idx]:
            return None
        return np.array(self._index.get_item_vector(internal_idx), dtype=np.float32)

    def save(self, path: str) -> None:
        if self._index is None:
            raise RuntimeError("Index not initialized")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self._index.save(f"{path}.annoy")
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
            "_n_trees": self._n_trees,
            "_search_k": self._search_k,
            "_annoy_metric": self._annoy_metric,
        }
        with open(f"{path}.pkl", "wb") as f:
            pickle.dump(state, f)

    def load(self, path: str) -> None:
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
        self._n_trees = state["_n_trees"]
        self._search_k = state["_search_k"]
        self._annoy_metric = state["_annoy_metric"]
        self._index = AnnoyIndex(self.dimension, self._annoy_metric)
        self._index.load(f"{path}.annoy")

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
