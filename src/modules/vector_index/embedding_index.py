"""Embedding vector index for approximate nearest neighbor search."""
from __future__ import annotations

import heapq
import math
import pickle
import random
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager


class IndexType(Enum):
    FLAT = "flat"
    IVF = "ivf"
    HNSW = "hnsw"
    IVF_PQ = "ivf_pq"
    LSH = "lsh"


class DistanceMetric(Enum):
    EUCLIDEAN = "euclidean"
    COSINE = "cosine"
    INNER_PRODUCT = "inner_product"
    MANHATTAN = "manhattan"
    HAMMING = "hamming"


@dataclass
class VectorRecord:
    id: UUID
    vector: List[float]
    metadata: Dict[str, Any] = field(default_factory=dict)
    timestamp: float = field(default_factory=lambda: __import__("time").time())


@dataclass
class IVFCluster:
    centroid: List[float]
    vectors: List[VectorRecord] = field(default_factory=list)
    cluster_id: int = 0


@dataclass
class HNSWNode:
    id: UUID
    vector: List[float]
    metadata: Dict[str, Any]
    layers: List[List[int]] = field(default_factory=list)


class VectorIndex:
    def __init__(
        self,
        index_type: IndexType = IndexType.HNSW,
        dimension: int = 128,
        distance_metric: DistanceMetric = DistanceMetric.COSINE,
        **kwargs: Any,
    ) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._index_type = index_type
        self._dimension = dimension
        self._distance_metric = distance_metric
        self._records: Dict[UUID, VectorRecord] = {}
        self._id_to_idx: Dict[UUID, int] = {}
        self._idx_to_id: List[UUID] = []
        self._is_built = False
        self._kwargs = kwargs

        if index_type == IndexType.IVF or index_type == IndexType.IVF_PQ:
            self._nlist = kwargs.get("nlist", 100)
            self._nprobe = kwargs.get("nprobe", 10)
            self._clusters: List[IVFCluster] = []
            self._m = kwargs.get("m", 8)

        if index_type == IndexType.HNSW:
            self._m = kwargs.get("m", 16)
            self._ef_construction = kwargs.get("ef_construction", 200)
            self._ef_search = kwargs.get("ef_search", 50)
            self._hnsw_nodes: List[HNSWNode] = []
            self._hnsw_layers: List[List[int]] = []
            self._enter_point: Optional[int] = None
            self._max_layer = -1

        if index_type == IndexType.LSH:
            self._n_hashes = kwargs.get("n_hashes", 10)
            self._hash_size = kwargs.get("hash_size", 20)
            self._random_vectors: List[List[float]] = []
            self._hash_tables: List[Dict[str, List[int]]] = []

        self._logger.info(
            f"Initialized vector index",
            index_type=index_type.value,
            dimension=dimension,
            distance_metric=distance_metric.value,
        )

    @property
    def index_type(self) -> IndexType:
        return self._index_type

    @property
    def dimension(self) -> int:
        return self._dimension

    @property
    def distance_metric(self) -> DistanceMetric:
        return self._distance_metric

    @property
    def size(self) -> int:
        return len(self._records)

    @property
    def is_built(self) -> bool:
        return self._is_built

    def add_vector(
        self,
        vector: List[float],
        metadata: Optional[Dict[str, Any]] = None,
        record_id: Optional[UUID] = None,
    ) -> UUID:
        if len(vector) != self._dimension:
            raise ValidationError(
                message=f"Vector dimension mismatch: expected {self._dimension}, got {len(vector)}",
                suggestion="Ensure vectors have the correct dimension.",
            )

        record_id = record_id or uuid4()
        record = VectorRecord(
            id=record_id,
            vector=vector,
            metadata=metadata or {},
        )

        self._records[record_id] = record
        self._id_to_idx[record_id] = len(self._idx_to_id)
        self._idx_to_id.append(record_id)

        self._is_built = False

        return record_id

    def add_vectors(
        self,
        vectors: List[List[float]],
        metadata_list: Optional[List[Dict[str, Any]]] = None,
    ) -> List[UUID]:
        if metadata_list and len(metadata_list) != len(vectors):
            raise ValidationError(
                message=f"Metadata list length mismatch: expected {len(vectors)}, got {len(metadata_list)}",
                suggestion="Ensure metadata list has the same length as vectors.",
            )

        record_ids: List[UUID] = []
        for i, vector in enumerate(vectors):
            metadata = metadata_list[i] if metadata_list else None
            record_id = self.add_vector(vector, metadata)
            record_ids.append(record_id)

        return record_ids

    def get_vector(self, record_id: UUID) -> Optional[VectorRecord]:
        return self._records.get(record_id)

    def update_vector(
        self,
        record_id: UUID,
        vector: Optional[List[float]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> bool:
        record = self._records.get(record_id)
        if not record:
            return False

        if vector is not None:
            if len(vector) != self._dimension:
                raise ValidationError(
                    message=f"Vector dimension mismatch: expected {self._dimension}, got {len(vector)}",
                    suggestion="Ensure vectors have the correct dimension.",
                )
            record.vector = vector

        if metadata is not None:
            record.metadata.update(metadata)

        self._is_built = False
        return True

    def delete_vector(self, record_id: UUID) -> bool:
        if record_id not in self._records:
            return False

        del self._records[record_id]
        idx = self._id_to_idx.pop(record_id)

        if idx < len(self._idx_to_id) - 1:
            last_id = self._idx_to_id[-1]
            self._idx_to_id[idx] = last_id
            self._id_to_idx[last_id] = idx

        self._idx_to_id.pop()
        self._is_built = False

        return True

    def build(self) -> None:
        self._logger.info(f"Building {self._index_type.value} index with {len(self._records)} vectors")

        if len(self._records) == 0:
            raise ValidationError(
                message="No vectors to build index from",
                suggestion="Add vectors to the index before building.",
            )

        if self._index_type == IndexType.FLAT:
            self._build_flat()
        elif self._index_type == IndexType.IVF:
            self._build_ivf()
        elif self._index_type == IndexType.IVF_PQ:
            self._build_ivf_pq()
        elif self._index_type == IndexType.HNSW:
            self._build_hnsw()
        elif self._index_type == IndexType.LSH:
            self._build_lsh()

        self._is_built = True
        self._logger.info("Index built successfully")

    def _build_flat(self) -> None:
        pass

    def _build_ivf(self) -> None:
        vectors = [record.vector for record in self._records.values()]
        self._clusters = self._kmeans(vectors, self._nlist)

        for i, (record_id, record) in enumerate(self._records.items()):
            distances = [
                self._distance(record.vector, cluster.centroid)
                for cluster in self._clusters
            ]
            closest_cluster = distances.index(min(distances))
            self._clusters[closest_cluster].vectors.append(record)

    def _kmeans(
        self,
        vectors: List[List[float]],
        k: int,
        max_iterations: int = 100,
        tolerance: float = 1e-4,
    ) -> List[IVFCluster]:
        centroids = random.sample(vectors, min(k, len(vectors)))
        clusters: List[IVFCluster] = [
            IVFCluster(centroid=c, cluster_id=i) for i, c in enumerate(centroids)
        ]

        for _ in range(max_iterations):
            for cluster in clusters:
                cluster.vectors.clear()

            for vector in vectors:
                distances = [
                    self._distance(vector, cluster.centroid) for cluster in clusters
                ]
                closest = distances.index(min(distances))
                clusters[closest].vectors.append(VectorRecord(id=uuid4(), vector=vector))

            new_centroids = []
            for cluster in clusters:
                if cluster.vectors:
                    dim = len(cluster.vectors[0].vector)
                    centroid = [
                        sum(v.vector[d] for v in cluster.vectors) / len(cluster.vectors)
                        for d in range(dim)
                    ]
                    new_centroids.append(centroid)
                else:
                    new_centroids.append(cluster.centroid)

            max_shift = max(
                self._distance(old.centroid, new)
                for old, new in zip(clusters, new_centroids)
            )

            for cluster, new_centroid in zip(clusters, new_centroids):
                cluster.centroid = new_centroid
                cluster.vectors.clear()

            if max_shift < tolerance:
                break

        return clusters

    def _build_ivf_pq(self) -> None:
        self._build_ivf()

    def _build_hnsw(self) -> None:
        self._hnsw_nodes = []
        self._hnsw_layers = []
        self._enter_point = None
        self._max_layer = -1

        vectors = list(self._records.values())
        for i, record in enumerate(vectors):
            self._hnsw_nodes.append(HNSWNode(
                id=record.id,
                vector=record.vector,
                metadata=record.metadata,
            ))

        for i, node in enumerate(self._hnsw_nodes):
            self._insert_hnsw_node(i, node)

    def _insert_hnsw_node(self, node_idx: int, node: HNSWNode) -> None:
        m = self._m
        m_max = 2 * m

        level = self._get_random_level()

        node.layers = [[] for _ in range(level + 1)]

        if self._enter_point is None:
            self._enter_point = node_idx
            self._max_level = level
            for l in range(level + 1):
                self._hnsw_layers.append([node_idx])
            return

        curr = self._enter_point
        for l in range(self._max_level, level, -1):
            curr = self._search_layer_hnsw(node.vector, curr, 1, l)[0]

        for l in range(min(level, self._max_level), -1, -1):
            neighbors = self._search_layer_hnsw(node.vector, curr, self._ef_construction, l)
            selected = self._select_neighbors_heuristic(node.vector, neighbors, m, l)

            node.layers[l] = selected

            for neighbor_idx in selected:
                neighbor = self._hnsw_nodes[neighbor_idx]
                neighbor.layers[l].append(node_idx)
                if len(neighbor.layers[l]) > m_max:
                    neighbor.layers[l] = self._select_neighbors_heuristic(
                        neighbor.vector, neighbor.layers[l], m_max, l
                    )

            curr = neighbors[0] if neighbors else curr

        if level > self._max_level:
            for l in range(self._max_level + 1, level + 1):
                self._hnsw_layers.append([node_idx])
            self._enter_point = node_idx
            self._max_level = level

    def _get_random_level(self) -> int:
        level = 0
        m = self._m
        while random.random() < 1 / math.exp(1 / m) and level < 10:
            level += 1
        return level

    def _search_layer_hnsw(
        self,
        query: List[float],
        entry_point: int,
        ef: int,
        level: int,
    ) -> List[int]:
        visited = set()
        candidates = []
        results = []

        dist = self._distance(query, self._hnsw_nodes[entry_point].vector)
        heapq.heappush(candidates, (dist, entry_point))
        heapq.heappush(results, (-dist, entry_point))
        visited.add(entry_point)

        while candidates:
            dist, idx = heapq.heappop(candidates)
            furthest_dist = -results[0][0] if results else float("inf")

            if dist > furthest_dist:
                break

            node = self._hnsw_nodes[idx]
            if level < len(node.layers):
                for neighbor_idx in node.layers[level]:
                    if neighbor_idx not in visited:
                        visited.add(neighbor_idx)
                        neighbor_dist = self._distance(query, self._hnsw_nodes[neighbor_idx].vector)

                        if len(results) < ef or neighbor_dist < -results[0][0]:
                            heapq.heappush(candidates, (neighbor_dist, neighbor_idx))
                            heapq.heappush(results, (-neighbor_dist, neighbor_idx))

                            if len(results) > ef:
                                heapq.heappop(results)

        return [idx for _, idx in sorted([(-d, i) for d, i in results])]

    def _select_neighbors_heuristic(
        self,
        query: List[float],
        candidates: List[int],
        m: int,
        level: int,
    ) -> List[int]:
        if len(candidates) <= m:
            return candidates

        distances = [
            (self._distance(query, self._hnsw_nodes[idx].vector), idx)
            for idx in candidates
        ]
        distances.sort()

        return [idx for _, idx in distances[:m]]

    def _build_lsh(self) -> None:
        self._random_vectors = []
        self._hash_tables = []

        vectors = [record.vector for record in self._records.values()]

        for _ in range(self._n_hashes):
            random_vec = [
                random.normalvariate(0, 1) for _ in range(self._dimension)
            ]
            self._random_vectors.append(random_vec)

        for _ in range(self._hash_size):
            self._hash_tables.append({})

        for i, vector in enumerate(vectors):
            hash_str = self._compute_lsh_hash(vector)
            for j, h in enumerate(hash_str):
                if h not in self._hash_tables[j]:
                    self._hash_tables[j][h] = []
                self._hash_tables[j][h].append(i)

    def _compute_lsh_hash(self, vector: List[float]) -> List[str]:
        hashes = []
        for i, random_vec in enumerate(self._random_vectors):
            dot = sum(v * rv for v, rv in zip(vector, random_vec))
            hashes.append("1" if dot > 0 else "0")

        hash_strs = []
        for i in range(self._hash_size):
            start = (i * len(hashes)) // self._hash_size
            end = ((i + 1) * len(hashes)) // self._hash_size
            hash_strs.append("".join(hashes[start:end]))

        return hash_strs

    def search(
        self,
        query_vector: List[float],
        top_k: int = 10,
        **kwargs: Any,
    ) -> List[Tuple[UUID, float, Dict[str, Any]]]:
        if not self._is_built:
            self.build()

        if len(query_vector) != self._dimension:
            raise ValidationError(
                message=f"Query vector dimension mismatch: expected {self._dimension}, got {len(query_vector)}",
                suggestion="Ensure query vector has the correct dimension.",
            )

        if self._index_type == IndexType.FLAT:
            return self._search_flat(query_vector, top_k)
        elif self._index_type == IndexType.IVF:
            return self._search_ivf(query_vector, top_k, kwargs.get("nprobe", self._nprobe))
        elif self._index_type == IndexType.IVF_PQ:
            return self._search_ivf_pq(query_vector, top_k, kwargs.get("nprobe", self._nprobe))
        elif self._index_type == IndexType.HNSW:
            return self._search_hnsw(query_vector, top_k, kwargs.get("ef_search", self._ef_search))
        elif self._index_type == IndexType.LSH:
            return self._search_lsh(query_vector, top_k)

        return self._search_flat(query_vector, top_k)

    def _search_flat(
        self,
        query_vector: List[float],
        top_k: int,
    ) -> List[Tuple[UUID, float, Dict[str, Any]]]:
        results = []

        for record_id, record in self._records.items():
            dist = self._distance(query_vector, record.vector)
            results.append((record_id, dist, record.metadata))

        results.sort(key=lambda x: x[1])
        return results[:top_k]

    def _search_ivf(
        self,
        query_vector: List[float],
        top_k: int,
        nprobe: int,
    ) -> List[Tuple[UUID, float, Dict[str, Any]]]:
        cluster_distances = [
            (self._distance(query_vector, cluster.centroid), i)
            for i, cluster in enumerate(self._clusters)
        ]
        cluster_distances.sort()

        candidate_vectors: List[VectorRecord] = []
        for _, cluster_idx in cluster_distances[:nprobe]:
            candidate_vectors.extend(self._clusters[cluster_idx].vectors)

        results = []
        for record in candidate_vectors:
            dist = self._distance(query_vector, record.vector)
            results.append((record.id, dist, record.metadata))

        results.sort(key=lambda x: x[1])
        return results[:top_k]

    def _search_ivf_pq(
        self,
        query_vector: List[float],
        top_k: int,
        nprobe: int,
    ) -> List[Tuple[UUID, float, Dict[str, Any]]]:
        return self._search_ivf(query_vector, top_k, nprobe)

    def _search_hnsw(
        self,
        query_vector: List[float],
        top_k: int,
        ef_search: int,
    ) -> List[Tuple[UUID, float, Dict[str, Any]]]:
        if self._enter_point is None:
            return []

        curr = self._enter_point
        for l in range(self._max_level, 0, -1):
            curr = self._search_layer_hnsw(query_vector, curr, 1, l)[0]

        candidates = self._search_layer_hnsw(query_vector, curr, ef_search, 0)
        results = []

        for idx in candidates:
            node = self._hnsw_nodes[idx]
            dist = self._distance(query_vector, node.vector)
            results.append((node.id, dist, node.metadata))

        results.sort(key=lambda x: x[1])
        return results[:top_k]

    def _search_lsh(
        self,
        query_vector: List[float],
        top_k: int,
    ) -> List[Tuple[UUID, float, Dict[str, Any]]]:
        query_hash = self._compute_lsh_hash(query_vector)

        candidate_indices = set()
        for j, h in enumerate(query_hash):
            if h in self._hash_tables[j]:
                candidate_indices.update(self._hash_tables[j][h])

        results = []
        for idx in candidate_indices:
            record_id = self._idx_to_id[idx]
            record = self._records[record_id]
            dist = self._distance(query_vector, record.vector)
            results.append((record_id, dist, record.metadata))

        results.sort(key=lambda x: x[1])
        return results[:top_k]

    def _distance(self, a: List[float], b: List[float]) -> float:
        if self._distance_metric == DistanceMetric.EUCLIDEAN:
            return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))
        elif self._distance_metric == DistanceMetric.COSINE:
            dot = sum(x * y for x, y in zip(a, b))
            norm_a = math.sqrt(sum(x * x for x in a))
            norm_b = math.sqrt(sum(x * x for x in b))
            if norm_a == 0 or norm_b == 0:
                return 1.0
            return 1 - (dot / (norm_a * norm_b))
        elif self._distance_metric == DistanceMetric.INNER_PRODUCT:
            return -sum(x * y for x, y in zip(a, b))
        elif self._distance_metric == DistanceMetric.MANHATTAN:
            return sum(abs(x - y) for x, y in zip(a, b))
        elif self._distance_metric == DistanceMetric.HAMMING:
            return sum(x != y for x, y in zip(a, b))
        else:
            return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))

    def save(self, filepath: str) -> None:
        data = {
            "index_type": self._index_type.value,
            "dimension": self._dimension,
            "distance_metric": self._distance_metric.value,
            "records": self._records,
            "id_to_idx": self._id_to_idx,
            "idx_to_id": self._idx_to_id,
            "is_built": self._is_built,
            "kwargs": self._kwargs,
        }

        if self._index_type == IndexType.IVF or self._index_type == IndexType.IVF_PQ:
            data["nlist"] = self._nlist
            data["nprobe"] = self._nprobe
            data["clusters"] = self._clusters
            data["m"] = self._m

        if self._index_type == IndexType.HNSW:
            data["m"] = self._m
            data["ef_construction"] = self._ef_construction
            data["ef_search"] = self._ef_search
            data["hnsw_nodes"] = self._hnsw_nodes
            data["hnsw_layers"] = self._hnsw_layers
            data["enter_point"] = self._enter_point
            data["max_layer"] = self._max_layer

        if self._index_type == IndexType.LSH:
            data["n_hashes"] = self._n_hashes
            data["hash_size"] = self._hash_size
            data["random_vectors"] = self._random_vectors
            data["hash_tables"] = self._hash_tables

        with open(filepath, "wb") as f:
            pickle.dump(data, f)

        self._logger.info(f"Index saved to {filepath}")

    @classmethod
    def load(cls, filepath: str) -> "VectorIndex":
        with open(filepath, "rb") as f:
            data = pickle.load(f)

        index_type = IndexType(data["index_type"])
        dimension = data["dimension"]
        distance_metric = DistanceMetric(data["distance_metric"])
        kwargs = data.get("kwargs", {})

        index = cls(index_type, dimension, distance_metric, **kwargs)
        index._records = data["records"]
        index._id_to_idx = data["id_to_idx"]
        index._idx_to_id = data["idx_to_id"]
        index._is_built = data["is_built"]

        if index_type == IndexType.IVF or index_type == IndexType.IVF_PQ:
            index._nlist = data["nlist"]
            index._nprobe = data["nprobe"]
            index._clusters = data["clusters"]
            index._m = data["m"]

        if index_type == IndexType.HNSW:
            index._m = data["m"]
            index._ef_construction = data["ef_construction"]
            index._ef_search = data["ef_search"]
            index._hnsw_nodes = data["hnsw_nodes"]
            index._hnsw_layers = data["hnsw_layers"]
            index._enter_point = data["enter_point"]
            index._max_layer = data["max_layer"]

        if index_type == IndexType.LSH:
            index._n_hashes = data["n_hashes"]
            index._hash_size = data["hash_size"]
            index._random_vectors = data["random_vectors"]
            index._hash_tables = data["hash_tables"]

        LogManager().get_logger(__name__).info(f"Index loaded from {filepath}")
        return index

    def get_stats(self) -> Dict[str, Any]:
        stats = {
            "index_type": self._index_type.value,
            "dimension": self._dimension,
            "distance_metric": self._distance_metric.value,
            "size": len(self._records),
            "is_built": self._is_built,
        }

        if self._index_type == IndexType.IVF or self._index_type == IndexType.IVF_PQ:
            stats["nlist"] = self._nlist
            stats["nprobe"] = self._nprobe
            stats["cluster_sizes"] = [len(c.vectors) for c in self._clusters]

        if self._index_type == IndexType.HNSW:
            stats["m"] = self._m
            stats["ef_construction"] = self._ef_construction
            stats["ef_search"] = self._ef_search
            stats["max_layer"] = self._max_layer
            stats["nodes_count"] = len(self._hnsw_nodes)

        if self._index_type == IndexType.LSH:
            stats["n_hashes"] = self._n_hashes
            stats["hash_size"] = self._hash_size
            stats["hash_buckets"] = [len(t) for t in self._hash_tables]

        return stats

    def list_vectors(
        self,
        limit: Optional[int] = None,
        offset: int = 0,
    ) -> List[Dict[str, Any]]:
        records = list(self._records.values())
        records = records[offset:]
        if limit:
            records = records[:limit]

        return [
            {
                "id": str(r.id),
                "metadata": r.metadata,
                "timestamp": r.timestamp,
                "vector_dim": len(r.vector),
            }
            for r in records
        ]

    def clear(self) -> None:
        self._records.clear()
        self._id_to_idx.clear()
        self._idx_to_id.clear()
        self._is_built = False

        if hasattr(self, "_clusters"):
            self._clusters.clear()
        if hasattr(self, "_hnsw_nodes"):
            self._hnsw_nodes.clear()
            self._hnsw_layers.clear()
            self._enter_point = None
            self._max_layer = -1
        if hasattr(self, "_random_vectors"):
            self._random_vectors.clear()
            self._hash_tables.clear()

        self._logger.info("Index cleared")
