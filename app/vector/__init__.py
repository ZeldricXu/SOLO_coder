"""
Vector Index Building Module.
Implements embedding vector indexing and approximate nearest neighbor search optimization.
"""

import numpy as np
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

from app.logging import get_logger


class DistanceMetric(str, Enum):
    EUCLIDEAN = "euclidean"
    COSINE = "cosine"
    DOT_PRODUCT = "dot_product"
    MANHATTAN = "manhattan"


class IndexType(str, Enum):
    FLAT = "flat"
    IVF = "ivf"
    HNSW = "hnsw"
    LSH = "lsh"


@dataclass
class VectorRecord:
    id: str
    vector: np.ndarray
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.utcnow)


@dataclass
class SearchResult:
    id: str
    distance: float
    similarity: float
    metadata: Dict[str, Any] = field(default_factory=dict)


class BaseVectorIndex(ABC):
    def __init__(
        self,
        dimension: int,
        metric: DistanceMetric = DistanceMetric.COSINE
    ):
        self._dimension = dimension
        self._metric = metric
        self._id_map: Dict[int, str] = {}
        self._metadata_map: Dict[str, Dict[str, Any]] = {}
        self._vectors: List[np.ndarray] = []
        self._next_id = 0
        self._built = False
        self._logger = get_logger("vector_index")
    
    @property
    def dimension(self) -> int:
        return self._dimension
    
    @property
    def metric(self) -> DistanceMetric:
        return self._metric
    
    @property
    def built(self) -> bool:
        return self._built
    
    @property
    def size(self) -> int:
        return len(self._vectors)
    
    def add(self, vector: np.ndarray, metadata: Optional[Dict[str, Any]] = None) -> str:
        if vector.shape[0] != self._dimension:
            raise ValueError(
                f"Vector dimension mismatch: expected {self._dimension}, got {vector.shape[0]}"
            )
        
        internal_id = self._next_id
        external_id = f"vec_{internal_id}"
        self._next_id += 1
        
        self._id_map[internal_id] = external_id
        self._metadata_map[external_id] = metadata or {}
        self._vectors.append(vector.astype(np.float32))
        
        self._built = False
        return external_id
    
    def add_batch(
        self,
        vectors: List[np.ndarray],
        metadatas: Optional[List[Dict[str, Any]]] = None
    ) -> List[str]:
        ids = []
        metas = metadatas or [{}] * len(vectors)
        for vec, meta in zip(vectors, metas):
            ids.append(self.add(vec, meta))
        return ids
    
    def get(self, external_id: str) -> Optional[VectorRecord]:
        for internal_id, eid in self._id_map.items():
            if eid == external_id:
                return VectorRecord(
                    id=external_id,
                    vector=self._vectors[internal_id],
                    metadata=self._metadata_map[external_id]
                )
        return None
    
    def delete(self, external_id: str) -> bool:
        for internal_id, eid in self._id_map.items():
            if eid == external_id:
                del self._id_map[internal_id]
                del self._metadata_map[external_id]
                self._vectors[internal_id] = np.zeros(self._dimension)
                self._built = False
                return True
        return False
    
    @abstractmethod
    def build(self):
        pass
    
    @abstractmethod
    def search(
        self,
        query: np.ndarray,
        k: int = 10,
        **kwargs
    ) -> List[SearchResult]:
        pass


class FlatIndex(BaseVectorIndex):
    def __init__(
        self,
        dimension: int,
        metric: DistanceMetric = DistanceMetric.COSINE
    ):
        super().__init__(dimension, metric)
        self._index_matrix: Optional[np.ndarray] = None
    
    def build(self):
        if not self._vectors:
            self._logger.warning("No vectors to build index from")
            return
        
        self._index_matrix = np.vstack(self._vectors).astype(np.float32)
        
        if self._metric in [DistanceMetric.COSINE, DistanceMetric.DOT_PRODUCT]:
            norms = np.linalg.norm(self._index_matrix, axis=1, keepdims=True)
            norms[norms == 0] = 1
            self._index_matrix = self._index_matrix / norms
        
        self._built = True
        self._logger.info("Flat index built", size=self.size)
    
    def _compute_distances(self, query: np.ndarray) -> np.ndarray:
        if self._index_matrix is None:
            return np.array([])
        
        query_vec = query.astype(np.float32)
        
        if self._metric == DistanceMetric.COSINE or self._metric == DistanceMetric.DOT_PRODUCT:
            query_norm = np.linalg.norm(query_vec)
            if query_norm > 0:
                query_vec = query_vec / query_norm
            scores = np.dot(self._index_matrix, query_vec)
            return 1 - scores
        
        elif self._metric == DistanceMetric.EUCLIDEAN:
            diff = self._index_matrix - query_vec
            return np.linalg.norm(diff, axis=1)
        
        elif self._metric == DistanceMetric.MANHATTAN:
            diff = np.abs(self._index_matrix - query_vec)
            return np.sum(diff, axis=1)
        
        return np.zeros(len(self._index_matrix))
    
    def search(
        self,
        query: np.ndarray,
        k: int = 10,
        **kwargs
    ) -> List[SearchResult]:
        if not self._built:
            self.build()
        
        if self._index_matrix is None or query.shape[0] != self._dimension:
            return []
        
        distances = self._compute_distances(query)
        
        if len(distances) == 0:
            return []
        
        k = min(k, len(distances))
        indices = np.argsort(distances)[:k]
        
        results = []
        for idx in indices:
            external_id = self._id_map.get(idx)
            if external_id is None:
                continue
            
            distance = distances[idx]
            
            if self._metric in [DistanceMetric.COSINE, DistanceMetric.DOT_PRODUCT]:
                similarity = 1 - distance
            else:
                similarity = 1.0 / (1.0 + distance)
            
            results.append(SearchResult(
                id=external_id,
                distance=float(distance),
                similarity=float(similarity),
                metadata=self._metadata_map.get(external_id, {})
            ))
        
        return results


class IVFIndex(BaseVectorIndex):
    def __init__(
        self,
        dimension: int,
        metric: DistanceMetric = DistanceMetric.COSINE,
        nlist: int = 100,
        nprobe: int = 10
    ):
        super().__init__(dimension, metric)
        self._nlist = nlist
        self._nprobe = nprobe
        self._centroids: Optional[np.ndarray] = None
        self._assignments: Dict[int, List[int]] = {}
        self._cluster_vectors: Dict[int, np.ndarray] = {}
    
    def _kmeans_pp_init(self, data: np.ndarray, k: int) -> np.ndarray:
        n_samples = data.shape[0]
        centroids = np.zeros((k, self._dimension), dtype=np.float32)
        
        idx = np.random.randint(n_samples)
        centroids[0] = data[idx]
        
        for i in range(1, k):
            distances = np.zeros(n_samples)
            for j in range(n_samples):
                min_dist = np.inf
                for c in range(i):
                    dist = np.linalg.norm(data[j] - centroids[c])
                    min_dist = min(min_dist, dist)
                distances[j] = min_dist
            
            probs = distances / distances.sum()
            idx = np.random.choice(n_samples, p=probs)
            centroids[i] = data[idx]
        
        return centroids
    
    def _kmeans(self, data: np.ndarray, k: int, max_iter: int = 100) -> Tuple[np.ndarray, List[int]]:
        centroids = self._kmeans_pp_init(data, k)
        assignments = [0] * data.shape[0]
        
        for _ in range(max_iter):
            for i, vec in enumerate(data):
                best_c = 0
                best_dist = np.inf
                for c, centroid in enumerate(centroids):
                    dist = np.linalg.norm(vec - centroid)
                    if dist < best_dist:
                        best_dist = dist
                        best_c = c
                assignments[i] = best_c
            
            new_centroids = np.zeros_like(centroids)
            counts = np.zeros(k)
            for i, cluster in enumerate(assignments):
                new_centroids[cluster] += data[i]
                counts[cluster] += 1
            
            for c in range(k):
                if counts[c] > 0:
                    new_centroids[c] /= counts[c]
            
            if np.allclose(centroids, new_centroids):
                break
            centroids = new_centroids
        
        return centroids, assignments
    
    def build(self):
        if not self._vectors:
            self._logger.warning("No vectors to build index from")
            return
        
        data = np.vstack(self._vectors).astype(np.float32)
        n_samples = data.shape[0]
        
        if n_samples < self._nlist:
            self._nlist = max(1, n_samples // 2)
        
        if self._metric in [DistanceMetric.COSINE, DistanceMetric.DOT_PRODUCT]:
            norms = np.linalg.norm(data, axis=1, keepdims=True)
            norms[norms == 0] = 1
            data = data / norms
        
        centroids, assignments = self._kmeans(data, self._nlist)
        
        self._centroids = centroids
        self._assignments = {}
        self._cluster_vectors = {}
        
        cluster_data: Dict[int, List[int]] = {}
        for i, cluster in enumerate(assignments):
            if cluster not in cluster_data:
                cluster_data[cluster] = []
            cluster_data[cluster].append(i)
        
        for cluster, indices in cluster_data.items():
            self._assignments[cluster] = indices
            self._cluster_vectors[cluster] = data[indices]
        
        self._built = True
        self._logger.info("IVF index built", size=self.size, nlist=self._nlist)
    
    def search(
        self,
        query: np.ndarray,
        k: int = 10,
        nprobe: Optional[int] = None,
        **kwargs
    ) -> List[SearchResult]:
        if not self._built:
            self.build()
        
        if self._centroids is None or query.shape[0] != self._dimension:
            return []
        
        actual_nprobe = nprobe or self._nprobe
        query_vec = query.astype(np.float32)
        
        if self._metric in [DistanceMetric.COSINE, DistanceMetric.DOT_PRODUCT]:
            query_norm = np.linalg.norm(query_vec)
            if query_norm > 0:
                query_vec = query_vec / query_norm
        
        centroid_dists = []
        for i, centroid in enumerate(self._centroids):
            dist = np.linalg.norm(query_vec - centroid)
            centroid_dists.append((dist, i))
        centroid_dists.sort()
        
        closest_clusters = [c for _, c in centroid_dists[:actual_nprobe]]
        
        candidates = []
        for cluster in closest_clusters:
            if cluster in self._assignments:
                cluster_vecs = self._cluster_vectors[cluster]
                cluster_indices = self._assignments[cluster]
                
                if self._metric in [DistanceMetric.COSINE, DistanceMetric.DOT_PRODUCT]:
                    scores = np.dot(cluster_vecs, query_vec)
                    for j, score in enumerate(scores):
                        candidates.append((1 - score, cluster_indices[j]))
                else:
                    diff = cluster_vecs - query_vec
                    dists = np.linalg.norm(diff, axis=1)
                    for j, dist in enumerate(dists):
                        candidates.append((dist, cluster_indices[j]))
        
        candidates.sort()
        k = min(k, len(candidates))
        
        results = []
        for distance, idx in candidates[:k]:
            external_id = self._id_map.get(idx)
            if external_id is None:
                continue
            
            if self._metric in [DistanceMetric.COSINE, DistanceMetric.DOT_PRODUCT]:
                similarity = 1 - distance
            else:
                similarity = 1.0 / (1.0 + distance)
            
            results.append(SearchResult(
                id=external_id,
                distance=float(distance),
                similarity=float(similarity),
                metadata=self._metadata_map.get(external_id, {})
            ))
        
        return results


class HNSWIndex(BaseVectorIndex):
    def __init__(
        self,
        dimension: int,
        metric: DistanceMetric = DistanceMetric.COSINE,
        m: int = 16,
        ef_construction: int = 200,
        ef_search: int = 50
    ):
        super().__init__(dimension, metric)
        self._m = m
        self._ef_construction = ef_construction
        self._ef_search = ef_search
        self._layers: List[Dict[int, Dict[int, float]]] = []
        self._entry_point: Optional[int] = None
        self._max_layer = 0
        self._node_count = 0
    
    def _random_level(self) -> int:
        level = 0
        while np.random.random() < 0.5 and level < 5:
            level += 1
        return level
    
    def _distance(self, a: np.ndarray, b: np.ndarray) -> float:
        if self._metric == DistanceMetric.COSINE:
            return 1 - np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-8)
        elif self._metric == DistanceMetric.EUCLIDEAN:
            return np.linalg.norm(a - b)
        elif self._metric == DistanceMetric.MANHATTAN:
            return np.sum(np.abs(a - b))
        else:
            return np.linalg.norm(a - b)
    
    def _search_layer(
        self,
        query: np.ndarray,
        entry_points: List[int],
        ef: int,
        layer: int
    ) -> List[int]:
        if layer >= len(self._layers):
            return []
        
        visited = set()
        candidates = []
        neighbors = []
        
        for ep in entry_points:
            if ep in visited:
                continue
            visited.add(ep)
            dist = self._distance(query, self._vectors[ep])
            candidates.append((dist, ep))
            neighbors.append((dist, ep))
        
        candidates.sort()
        neighbors.sort()
        
        while candidates:
            current_dist, current = candidates.pop(0)
            farthest_dist = neighbors[-1][0] if neighbors else float('inf')
            
            if current_dist > farthest_dist:
                break
            
            layer_edges = self._layers[layer].get(current, {})
            for neighbor in layer_edges:
                if neighbor in visited:
                    continue
                visited.add(neighbor)
                
                neighbor_dist = self._distance(query, self._vectors[neighbor])
                
                if len(neighbors) < ef or neighbor_dist < neighbors[-1][0]:
                    candidates.append((neighbor_dist, neighbor))
                    neighbors.append((neighbor_dist, neighbor))
                    neighbors.sort()
                    if len(neighbors) > ef:
                        neighbors = neighbors[:ef]
        
        return [n for _, n in neighbors]
    
    def _select_neighbors(
        self,
        query_idx: int,
        candidates: List[Tuple[float, int]],
        m: int
    ) -> List[int]:
        candidates.sort()
        return [idx for _, idx in candidates[:m]]
    
    def build(self):
        if not self._vectors:
            return
        
        self._layers = []
        self._entry_point = None
        self._node_count = 0
        
        for i, vec in enumerate(self._vectors):
            new_level = self._random_level()
            
            while len(self._layers) <= new_level:
                self._layers.append({})
            
            if self._entry_point is None:
                self._entry_point = i
                self._max_layer = new_level
                for l in range(new_level + 1):
                    self._layers[l][i] = {}
                continue
            
            entry_points = [self._entry_point]
            for l in range(self._max_layer, new_level, -1):
                entry_points = self._search_layer(
                    vec, entry_points, 1, l
                )
            
            for l in range(min(new_level, self._max_layer), -1, -1):
                neighbors = self._search_layer(
                    vec, entry_points, self._ef_construction, l
                )
                
                candidate_dists = []
                for n in neighbors:
                    dist = self._distance(vec, self._vectors[n])
                    candidate_dists.append((dist, n))
                
                selected = self._select_neighbors(i, candidate_dists, self._m)
                
                if i not in self._layers[l]:
                    self._layers[l][i] = {}
                for n in selected:
                    self._layers[l][i][n] = self._distance(vec, self._vectors[n])
                    if n not in self._layers[l]:
                        self._layers[l][n] = {}
                    self._layers[l][n][i] = self._layers[l][i][n]
                
                entry_points = selected
            
            if new_level > self._max_layer:
                self._entry_point = i
                self._max_layer = new_level
            
            self._node_count += 1
        
        self._built = True
        self._logger.info("HNSW index built", size=self.size, layers=self._max_layer + 1)
    
    def search(
        self,
        query: np.ndarray,
        k: int = 10,
        ef_search: Optional[int] = None,
        **kwargs
    ) -> List[SearchResult]:
        if not self._built:
            self.build()
        
        if self._entry_point is None or query.shape[0] != self._dimension:
            return []
        
        actual_ef = ef_search or self._ef_search
        entry_points = [self._entry_point]
        
        for l in range(self._max_layer, 0, -1):
            entry_points = self._search_layer(
                query, entry_points, 1, l
            )
        
        nearest = self._search_layer(
            query, entry_points, max(actual_ef, k), 0
        )
        
        results = []
        for idx in nearest:
            external_id = self._id_map.get(idx)
            if external_id is None:
                continue
            
            distance = self._distance(query, self._vectors[idx])
            
            if self._metric == DistanceMetric.COSINE:
                similarity = 1 - distance
            else:
                similarity = 1.0 / (1.0 + distance)
            
            results.append(SearchResult(
                id=external_id,
                distance=float(distance),
                similarity=float(similarity),
                metadata=self._metadata_map.get(external_id, {})
            ))
        
        results.sort(key=lambda x: x.distance)
        return results[:k]


class VectorIndexFactory:
    @staticmethod
    def create(
        index_type: IndexType,
        dimension: int,
        metric: DistanceMetric = DistanceMetric.COSINE,
        **kwargs
    ) -> BaseVectorIndex:
        if index_type == IndexType.FLAT:
            return FlatIndex(dimension, metric)
        elif index_type == IndexType.IVF:
            return IVFIndex(
                dimension,
                metric,
                nlist=kwargs.get("nlist", 100),
                nprobe=kwargs.get("nprobe", 10)
            )
        elif index_type == IndexType.HNSW:
            return HNSWIndex(
                dimension,
                metric,
                m=kwargs.get("m", 16),
                ef_construction=kwargs.get("ef_construction", 200),
                ef_search=kwargs.get("ef_search", 50)
            )
        else:
            raise ValueError(f"Unsupported index type: {index_type}")


class VectorSearchService:
    def __init__(self):
        self._indices: Dict[str, BaseVectorIndex] = {}
        self._logger = get_logger("vector_service")
    
    def create_index(
        self,
        name: str,
        index_type: IndexType,
        dimension: int,
        metric: DistanceMetric = DistanceMetric.COSINE,
        **kwargs
    ) -> BaseVectorIndex:
        index = VectorIndexFactory.create(
            index_type, dimension, metric, **kwargs
        )
        self._indices[name] = index
        self._logger.info(
            "Created vector index",
            name=name,
            type=index_type.value,
            dimension=dimension
        )
        return index
    
    def get_index(self, name: str) -> Optional[BaseVectorIndex]:
        return self._indices.get(name)
    
    def delete_index(self, name: str) -> bool:
        if name in self._indices:
            del self._indices[name]
            self._logger.info("Deleted vector index", name=name)
            return True
        return False
    
    def list_indices(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": name,
                "type": index.__class__.__name__,
                "dimension": index.dimension,
                "metric": index.metric.value,
                "size": index.size,
                "built": index.built
            }
            for name, index in self._indices.items()
        ]
