"""
向量索引基类定义，提供统一的索引接口
"""
from abc import ABC, abstractmethod
from typing import List, Tuple, Optional, Dict, Any, Union
import numpy as np
from enum import Enum


class IndexType(str, Enum):
    HNSW = "hnsw"
    IVF = "ivf"
    ANNOY = "annoy"
    FLAT = "flat"


class MetricType(str, Enum):
    L2 = "l2"
    INNER_PRODUCT = "inner_product"
    COSINE = "cosine"


class SearchResult:
    def __init__(
        self,
        ids: List[int],
        distances: List[float],
        vectors: Optional[np.ndarray] = None,
        metadata: Optional[List[Dict[str, Any]]] = None,
    ):
        self.ids = ids
        self.distances = distances
        self.vectors = vectors
        self.metadata = metadata or [{} for _ in ids]

    def __len__(self) -> int:
        return len(self.ids)

    def __getitem__(self, idx: int) -> Tuple[int, float, Optional[np.ndarray], Dict[str, Any]]:
        return (
            self.ids[idx],
            self.distances[idx],
            self.vectors[idx] if self.vectors is not None else None,
            self.metadata[idx],
        )

    def to_dict(self) -> Dict[str, Any]:
        return {
            "ids": self.ids,
            "distances": self.distances,
            "vectors": self.vectors.tolist() if self.vectors is not None else None,
            "metadata": self.metadata,
        }


class VectorIndexBase(ABC):
    def __init__(
        self,
        dimension: int,
        metric: MetricType = MetricType.COSINE,
        index_params: Optional[Dict[str, Any]] = None,
    ):
        self.dimension = dimension
        self.metric = metric
        self.index_params = index_params or {}
        self._is_trained = False
        self._metadata: Dict[int, Dict[str, Any]] = {}
        self._id_map: Dict[int, int] = {}
        self._next_id = 0

    @abstractmethod
    def build(self, vectors: np.ndarray, ids: Optional[List[int]] = None) -> None:
        pass

    @abstractmethod
    def add(
        self,
        vectors: np.ndarray,
        ids: Optional[List[int]] = None,
        metadata: Optional[List[Dict[str, Any]]] = None,
    ) -> List[int]:
        pass

    @abstractmethod
    def search(
        self,
        query: np.ndarray,
        k: int = 10,
        filter_func: Optional[callable] = None,
        **kwargs: Any,
    ) -> SearchResult:
        pass

    @abstractmethod
    def delete(self, ids: List[int]) -> bool:
        pass

    @abstractmethod
    def update(
        self,
        ids: List[int],
        vectors: np.ndarray,
        metadata: Optional[List[Dict[str, Any]]] = None,
    ) -> bool:
        pass

    @abstractmethod
    def get_vector(self, id: int) -> Optional[np.ndarray]:
        pass

    @abstractmethod
    def save(self, path: str) -> None:
        pass

    @abstractmethod
    def load(self, path: str) -> None:
        pass

    @abstractmethod
    def size(self) -> int:
        pass

    @abstractmethod
    def clear(self) -> None:
        pass

    def is_trained(self) -> bool:
        return self._is_trained

    def get_metadata(self, id: int) -> Optional[Dict[str, Any]]:
        return self._metadata.get(id)

    def set_metadata(self, id: int, metadata: Dict[str, Any]) -> bool:
        if id in self._id_map:
            self._metadata[id] = metadata
            return True
        return False

    def list_ids(self) -> List[int]:
        return list(self._id_map.keys())

    def _normalize_vector(self, vectors: np.ndarray) -> np.ndarray:
        if self.metric == MetricType.COSINE:
            norms = np.linalg.norm(vectors, axis=1, keepdims=True)
            return vectors / np.maximum(norms, 1e-10)
        return vectors

    def _validate_vectors(self, vectors: np.ndarray) -> np.ndarray:
        if vectors.ndim == 1:
            vectors = vectors.reshape(1, -1)
        if vectors.shape[1] != self.dimension:
            raise ValueError(
                f"Vector dimension mismatch: expected {self.dimension}, got {vectors.shape[1]}"
            )
        return vectors.astype(np.float32)

    def _generate_ids(self, count: int, ids: Optional[List[int]] = None) -> List[int]:
        if ids is not None:
            if len(ids) != count:
                raise ValueError(f"IDs count mismatch: expected {count}, got {len(ids)}")
            for id in ids:
                if id in self._id_map:
                    raise ValueError(f"ID {id} already exists")
            return ids
        new_ids = list(range(self._next_id, self._next_id + count))
        self._next_id += count
        return new_ids

    def _register_ids(self, ids: List[int], internal_indices: List[int]) -> None:
        for id, internal_idx in zip(ids, internal_indices):
            self._id_map[id] = internal_idx
