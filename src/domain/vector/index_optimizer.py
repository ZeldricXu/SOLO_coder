import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from src.domain.vector.embedding_index import EmbeddingIndex, VectorDocument
from src.infrastructure.config.settings import VectorConfig

logger = logging.getLogger(__name__)


@dataclass
class IndexBenchmark:
    index_type: str
    build_time_ms: float = 0.0
    search_time_ms: float = 0.0
    recall_at_10: float = 0.0
    memory_mb: float = 0.0
    total_vectors: int = 0


@dataclass
class OptimizationSuggestion:
    current_type: str
    suggested_type: str
    reason: str
    expected_improvement: str


class VectorIndexOptimizer:
    INDEX_TYPES = ["FLAT", "IVF_FLAT", "IVF_PQ", "HNSW"]

    def __init__(self, config: Optional[VectorConfig] = None):
        self._config = config or VectorConfig()

    def benchmark_index_types(
        self,
        vectors: np.ndarray,
        doc_ids: List[str],
        query_vectors: Optional[np.ndarray] = None,
        top_k: int = 10,
        n_queries: int = 100,
    ) -> List[IndexBenchmark]:
        if query_vectors is None:
            n_queries = min(n_queries, len(vectors))
            query_vectors = vectors[:n_queries]

        ground_truth = self._compute_ground_truth(vectors, query_vectors, top_k)
        benchmarks = []

        for index_type in self.INDEX_TYPES:
            try:
                config = VectorConfig(
                    dimension=vectors.shape[1],
                    index_type=index_type,
                    nlist=self._config.nlist,
                    nprobe=self._config.nprobe,
                    metric=self._config.metric,
                )

                start = time.time()
                documents = [
                    VectorDocument(doc_id=doc_ids[i], vector=vectors[i])
                    for i in range(len(doc_ids))
                ]
                index = EmbeddingIndex(config)
                index.build(documents)
                build_time = (time.time() - start) * 1000

                start = time.time()
                for query in query_vectors:
                    index.search(query, top_k)
                search_time = (time.time() - start) * 1000 / len(query_vectors)

                recall = self._compute_recall(index, query_vectors, ground_truth, top_k)
                stats = index.get_stats()

                benchmarks.append(IndexBenchmark(
                    index_type=index_type,
                    build_time_ms=round(build_time, 2),
                    search_time_ms=round(search_time, 4),
                    recall_at_10=round(recall, 4),
                    memory_mb=stats.memory_usage_mb,
                    total_vectors=stats.total_vectors,
                ))

            except Exception as e:
                logger.warning(f"Failed to benchmark {index_type}: {e}")

        return benchmarks

    def _compute_ground_truth(
        self, vectors: np.ndarray, query_vectors: np.ndarray, top_k: int
    ) -> List[List[int]]:
        ground_truth = []
        for query in query_vectors:
            query = query.reshape(1, -1)
            distances = np.linalg.norm(vectors - query, axis=1)
            top_indices = np.argsort(distances)[:top_k].tolist()
            ground_truth.append(top_indices)
        return ground_truth

    def _compute_recall(
        self,
        index: EmbeddingIndex,
        query_vectors: np.ndarray,
        ground_truth: List[List[int]],
        top_k: int,
    ) -> float:
        total_recall = 0.0
        for i, query in enumerate(query_vectors):
            results = index.search(query, top_k)
            result_ids = set()
            for doc_id, _, _ in results:
                try:
                    idx = int(doc_id)
                    result_ids.add(idx)
                except ValueError:
                    pass
            gt_set = set(ground_truth[i])
            if gt_set:
                total_recall += len(result_ids & gt_set) / len(gt_set)
        return total_recall / len(query_vectors) if query_vectors else 0.0

    def suggest_optimization(
        self,
        current_type: str,
        total_vectors: int,
        search_qps: float,
        memory_limit_mb: Optional[float] = None,
        recall_target: float = 0.95,
    ) -> List[OptimizationSuggestion]:
        suggestions = []

        if total_vectors < 10000 and current_type != "FLAT":
            suggestions.append(OptimizationSuggestion(
                current_type=current_type,
                suggested_type="FLAT",
                reason="Small dataset: FLAT index provides exact results with negligible overhead",
                expected_improvement="100% recall, simpler index, no training required",
            ))

        if total_vectors > 100000 and current_type == "FLAT":
            suggestions.append(OptimizationSuggestion(
                current_type=current_type,
                suggested_type="IVF_FLAT",
                reason="Large dataset: FLAT index search is too slow",
                expected_improvement=f"~10x faster search, ~{recall_target*100:.0f}%+ recall with proper nlist",
            ))

        if total_vectors > 1000000 and current_type in ("FLAT", "IVF_FLAT"):
            suggestions.append(OptimizationSuggestion(
                current_type=current_type,
                suggested_type="IVF_PQ",
                reason="Very large dataset: IVF_PQ reduces memory usage significantly",
                expected_improvement=f"~4-8x memory reduction, ~{recall_target*100:.0f}% recall",
            ))

        if search_qps > 1000 and current_type != "HNSW":
            suggestions.append(OptimizationSuggestion(
                current_type=current_type,
                suggested_type="HNSW",
                reason="High QPS requirement: HNSW provides best search latency",
                expected_improvement="Lowest search latency, good recall",
            ))

        if memory_limit_mb and total_vectors > 0:
            dim = self._config.dimension
            flat_memory = total_vectors * dim * 4 / (1024 * 1024)
            if flat_memory > memory_limit_mb and current_type in ("FLAT", "IVF_FLAT"):
                suggestions.append(OptimizationSuggestion(
                    current_type=current_type,
                    suggested_type="IVF_PQ",
                    reason=f"Memory budget ({memory_limit_mb:.0f}MB) exceeded with current index",
                    expected_improvement=f"Memory usage reduced to ~{flat_memory/8:.0f}MB",
                ))

        return suggestions

    def optimize_nlist(self, total_vectors: int) -> int:
        return min(max(int(total_vectors ** 0.5), 1), 65536)

    def optimize_nprobe(self, nlist: int, recall_target: float = 0.95) -> int:
        ratio_map = {
            0.90: 0.01,
            0.95: 0.05,
            0.99: 0.15,
        }
        ratio = ratio_map.get(recall_target, 0.05)
        return max(1, min(int(nlist * ratio), nlist))

    def auto_tune(
        self,
        vectors: np.ndarray,
        doc_ids: List[str],
        target_recall: float = 0.95,
        max_search_time_ms: float = 1.0,
    ) -> Dict[str, Any]:
        best_config = None
        best_score = -1

        for index_type in ["IVF_FLAT", "IVF_PQ", "HNSW", "FLAT"]:
            try:
                nlist = self.optimize_nlist(len(vectors))
                nprobe = self.optimize_nprobe(nlist, target_recall)

                config = VectorConfig(
                    dimension=vectors.shape[1],
                    index_type=index_type,
                    nlist=nlist,
                    nprobe=nprobe,
                    metric=self._config.metric,
                )

                documents = [
                    VectorDocument(doc_id=doc_ids[i], vector=vectors[i])
                    for i in range(len(doc_ids))
                ]
                index = EmbeddingIndex(config)
                index.build(documents)

                n_test = min(50, len(vectors))
                query = vectors[:n_test]
                gt = self._compute_ground_truth(vectors, query, 10)
                recall = self._compute_recall(index, query, gt, 10)

                start = time.time()
                for q in query:
                    index.search(q, 10)
                avg_search = (time.time() - start) * 1000 / n_test

                if recall >= target_recall and avg_search <= max_search_time_ms:
                    score = recall / max(avg_search, 0.001)
                    if score > best_score:
                        best_score = score
                        best_config = {
                            "index_type": index_type,
                            "nlist": nlist,
                            "nprobe": nprobe,
                            "recall": recall,
                            "avg_search_ms": avg_search,
                        }

            except Exception as e:
                logger.warning(f"Auto-tune failed for {index_type}: {e}")

        return best_config or {
            "index_type": "FLAT",
            "nlist": 1,
            "nprobe": 1,
            "recall": 1.0,
            "avg_search_ms": 0.0,
        }
