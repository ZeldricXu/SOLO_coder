"""
ANN检索优化器：查询重写、混合检索、结果重排序
"""
from typing import List, Dict, Any, Optional, Union, Callable, Tuple
import numpy as np
from dataclasses import dataclass
from enum import Enum

from .index_base import VectorIndexBase, SearchResult, MetricType
from .embedding import EmbeddingProcessor


class RerankMethod(str, Enum):
    CROSS_ENCODER = "cross_encoder"
    SIMILARITY = "similarity"
    RRF = "rrf"
    WEIGHTED = "weighted"
    BM25 = "bm25"


class QueryRewriteStrategy(str, Enum):
    EXPANSION = "expansion"
    GENERATION = "generation"
    TRANSFORMATION = "transformation"
    NONE = "none"


@dataclass
class OptimizedSearchResult:
    results: SearchResult
    original_query: str
    rewritten_queries: List[str]
    rerank_scores: List[float]
    search_time_ms: float
    rerank_time_ms: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "results": self.results.to_dict(),
            "original_query": self.original_query,
            "rewritten_queries": self.rewritten_queries,
            "rerank_scores": self.rerank_scores,
            "search_time_ms": self.search_time_ms,
            "rerank_time_ms": self.rerank_time_ms,
        }


class QueryRewriter:
    def __init__(
        self,
        strategy: QueryRewriteStrategy = QueryRewriteStrategy.EXPANSION,
        embedding_processor: Optional[EmbeddingProcessor] = None,
        **kwargs: Any,
    ):
        self.strategy = strategy
        self.embedding_processor = embedding_processor
        self._expansion_terms = kwargs.get("expansion_terms", {})
        self._llm_client = kwargs.get("llm_client")

    def rewrite(
        self,
        query: str,
        num_queries: int = 3,
        **kwargs: Any,
    ) -> List[str]:
        if self.strategy == QueryRewriteStrategy.NONE:
            return [query]
        elif self.strategy == QueryRewriteStrategy.EXPANSION:
            return self._expand_query(query, num_queries)
        elif self.strategy == QueryRewriteStrategy.GENERATION:
            return self._generate_queries(query, num_queries, **kwargs)
        elif self.strategy == QueryRewriteStrategy.TRANSFORMATION:
            return self._transform_query(query, num_queries)
        else:
            return [query]

    def _expand_query(self, query: str, num_queries: int) -> List[str]:
        queries = [query]
        terms = query.lower().split()
        for term in terms:
            if term in self._expansion_terms:
                for expansion in self._expansion_terms[term][: num_queries - 1]:
                    expanded = query.replace(term, expansion)
                    if expanded not in queries:
                        queries.append(expanded)
                    if len(queries) >= num_queries:
                        break
        while len(queries) < num_queries:
            queries.append(query)
        return queries[:num_queries]

    def _generate_queries(
        self,
        query: str,
        num_queries: int,
        **kwargs: Any,
    ) -> List[str]:
        if self._llm_client is None:
            return [query]
        system_prompt = (
            f"Generate {num_queries} different search queries based on the original query. "
            "Return only the queries, one per line."
        )
        try:
            response = self._llm_client.chat.completions.create(
                model=kwargs.get("model", "gpt-3.5-turbo"),
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": f"Original query: {query}"},
                ],
                temperature=kwargs.get("temperature", 0.7),
            )
            generated = response.choices[0].message.content.strip().split("\n")
            return [query] + [q.strip() for q in generated if q.strip()][: num_queries - 1]
        except Exception:
            return [query]

    def _transform_query(self, query: str, num_queries: int) -> List[str]:
        queries = [query]
        if num_queries > 1:
            queries.append(f"best {query}")
        if num_queries > 2:
            queries.append(f"top rated {query}")
        return queries[:num_queries]


class HybridSearcher:
    def __init__(
        self,
        dense_index: VectorIndexBase,
        sparse_index: Optional[Any] = None,
        dense_weight: float = 0.5,
        sparse_weight: float = 0.5,
    ):
        self.dense_index = dense_index
        self.sparse_index = sparse_index
        self.dense_weight = dense_weight
        self.sparse_weight = sparse_weight

    def search(
        self,
        dense_query: np.ndarray,
        sparse_query: Optional[str] = None,
        k: int = 10,
        filter_func: Optional[callable] = None,
        **kwargs: Any,
    ) -> SearchResult:
        dense_k = k * 2
        dense_result = self.dense_index.search(
            dense_query, k=dense_k, filter_func=filter_func, **kwargs
        )
        if self.sparse_index is None or sparse_query is None:
            return self._truncate_result(dense_result, k)
        sparse_result = self._sparse_search(sparse_query, k=dense_k, **kwargs)
        return self._merge_results(dense_result, sparse_result, k)

    def _sparse_search(
        self,
        query: str,
        k: int,
        **kwargs: Any,
    ) -> SearchResult:
        try:
            return self.sparse_index.search(query, k=k, **kwargs)
        except Exception:
            return SearchResult(ids=[], distances=[], vectors=None, metadata=[])

    def _merge_results(
        self,
        dense_result: SearchResult,
        sparse_result: SearchResult,
        k: int,
    ) -> SearchResult:
        dense_scores = {id_: self._normalize_score(dense_result.distances[i], i)
                        for i, id_ in enumerate(dense_result.ids)}
        sparse_scores = {id_: self._normalize_score(sparse_result.distances[i], i)
                         for i, id_ in enumerate(sparse_result.ids)}
        all_ids = set(dense_scores.keys()) | set(sparse_scores.keys())
        merged_scores = []
        for id_ in all_ids:
            dense_s = dense_scores.get(id_, 0.0)
            sparse_s = sparse_scores.get(id_, 0.0)
            combined = self.dense_weight * dense_s + self.sparse_weight * sparse_s
            merged_scores.append((id_, combined))
        merged_scores.sort(key=lambda x: x[1], reverse=True)
        top_ids = [x[0] for x in merged_scores[:k]]
        top_scores = [x[1] for x in merged_scores[:k]]
        vectors = []
        metadata = []
        id_to_meta = {}
        id_to_vec = {}
        for i, id_ in enumerate(dense_result.ids):
            id_to_meta[id_] = dense_result.metadata[i]
            if dense_result.vectors is not None:
                id_to_vec[id_] = dense_result.vectors[i]
        for i, id_ in enumerate(sparse_result.ids):
            if id_ not in id_to_meta:
                id_to_meta[id_] = sparse_result.metadata[i]
            if id_ not in id_to_vec and sparse_result.vectors is not None:
                id_to_vec[id_] = sparse_result.vectors[i]
        for id_ in top_ids:
            metadata.append(id_to_meta.get(id_, {}))
            if id_to_vec:
                vectors.append(id_to_vec.get(id_))
        result_vectors = np.array(vectors) if vectors else None
        return SearchResult(
            ids=top_ids,
            distances=top_scores,
            vectors=result_vectors,
            metadata=metadata,
        )

    @staticmethod
    def _normalize_score(score: float, rank: int) -> float:
        return max(0.0, min(1.0, score)) if score >= 0 else 1.0 / (1.0 + rank)

    @staticmethod
    def _truncate_result(result: SearchResult, k: int) -> SearchResult:
        return SearchResult(
            ids=result.ids[:k],
            distances=result.distances[:k],
            vectors=result.vectors[:k] if result.vectors is not None else None,
            metadata=result.metadata[:k],
        )


class Reranker:
    def __init__(
        self,
        method: RerankMethod = RerankMethod.SIMILARITY,
        embedding_processor: Optional[EmbeddingProcessor] = None,
        cross_encoder_model: Optional[str] = None,
        **kwargs: Any,
    ):
        self.method = method
        self.embedding_processor = embedding_processor
        self.cross_encoder_model = cross_encoder_model
        self._cross_encoder = None
        self._rrf_k = kwargs.get("rrf_k", 60)
        self._weights = kwargs.get("weights", [0.5, 0.5])
        if cross_encoder_model is not None:
            try:
                from sentence_transformers import CrossEncoder
                self._cross_encoder = CrossEncoder(cross_encoder_model)
            except ImportError:
                self._cross_encoder = None

    def rerank(
        self,
        query: str,
        results: SearchResult,
        texts: Optional[List[str]] = None,
        **kwargs: Any,
    ) -> Tuple[List[int], List[float], List[Dict[str, Any]], np.ndarray]:
        if len(results) == 0:
            return [], [], [], np.array([])
        if self.method == RerankMethod.SIMILARITY:
            return self._rerank_by_similarity(query, results, **kwargs)
        elif self.method == RerankMethod.CROSS_ENCODER:
            return self._rerank_by_cross_encoder(query, results, texts, **kwargs)
        elif self.method == RerankMethod.RRF:
            return self._rerank_by_rrf(results, **kwargs)
        elif self.method == RerankMethod.WEIGHTED:
            return self._rerank_by_weighted(results, **kwargs)
        elif self.method == RerankMethod.BM25:
            return self._rerank_by_bm25(query, results, texts, **kwargs)
        else:
            return results.ids, results.distances, results.metadata, results.vectors

    def _rerank_by_similarity(
        self,
        query: str,
        results: SearchResult,
        **kwargs: Any,
    ) -> Tuple[List[int], List[float], List[Dict[str, Any]], np.ndarray]:
        if self.embedding_processor is None or results.vectors is None:
            return results.ids, results.distances, results.metadata, results.vectors
        query_vec = self.embedding_processor.encode_single(query, **kwargs)
        scores = []
        for vec in results.vectors:
            sim = np.dot(query_vec, vec) / (np.linalg.norm(query_vec) * np.linalg.norm(vec) + 1e-10)
            scores.append(float(sim))
        sorted_indices = np.argsort(scores)[::-1]
        return (
            [results.ids[i] for i in sorted_indices],
            [scores[i] for i in sorted_indices],
            [results.metadata[i] for i in sorted_indices],
            results.vectors[sorted_indices],
        )

    def _rerank_by_cross_encoder(
        self,
        query: str,
        results: SearchResult,
        texts: Optional[List[str]] = None,
        **kwargs: Any,
    ) -> Tuple[List[int], List[float], List[Dict[str, Any]], np.ndarray]:
        if self._cross_encoder is None or texts is None:
            return results.ids, results.distances, results.metadata, results.vectors
        pairs = [[query, text] for text in texts]
        scores = self._cross_encoder.predict(pairs, **kwargs).tolist()
        sorted_indices = np.argsort(scores)[::-1]
        sorted_vectors = results.vectors[sorted_indices] if results.vectors is not None else np.array([])
        return (
            [results.ids[i] for i in sorted_indices],
            [scores[i] for i in sorted_indices],
            [results.metadata[i] for i in sorted_indices],
            sorted_vectors,
        )

    def _rerank_by_rrf(
        self,
        results: SearchResult,
        **kwargs: Any,
    ) -> Tuple[List[int], List[float], List[Dict[str, Any]], np.ndarray]:
        rrf_scores = {}
        for rank, id_ in enumerate(results.ids):
            rrf_scores[id_] = rrf_scores.get(id_, 0.0) + 1.0 / (self._rrf_k + rank)
        sorted_items = sorted(rrf_scores.items(), key=lambda x: x[1], reverse=True)
        ids = [x[0] for x in sorted_items]
        scores = [x[1] for x in sorted_items]
        id_to_idx = {id_: i for i, id_ in enumerate(results.ids)}
        metadata = [results.metadata[id_to_idx[id_]] for id_ in ids]
        vectors = np.array([results.vectors[id_to_idx[id_]] for id_ in ids]) if results.vectors is not None else np.array([])
        return ids, scores, metadata, vectors

    def _rerank_by_weighted(
        self,
        results: SearchResult,
        **kwargs: Any,
    ) -> Tuple[List[int], List[float], List[Dict[str, Any]], np.ndarray]:
        weighted_scores = []
        for i, dist in enumerate(results.distances):
            score = self._weights[0] * dist + self._weights[1] * (1.0 / (i + 1))
            weighted_scores.append((results.ids[i], score, results.metadata[i]))
        weighted_scores.sort(key=lambda x: x[1], reverse=True)
        ids = [x[0] for x in weighted_scores]
        scores = [x[1] for x in weighted_scores]
        metadata = [x[2] for x in weighted_scores]
        id_to_idx = {id_: i for i, id_ in enumerate(results.ids)}
        vectors = np.array([results.vectors[id_to_idx[id_]] for id_ in ids]) if results.vectors is not None else np.array([])
        return ids, scores, metadata, vectors

    def _rerank_by_bm25(
        self,
        query: str,
        results: SearchResult,
        texts: Optional[List[str]] = None,
        **kwargs: Any,
    ) -> Tuple[List[int], List[float], List[Dict[str, Any]], np.ndarray]:
        if texts is None:
            return results.ids, results.distances, results.metadata, results.vectors
        try:
            from rank_bm25 import BM25Okapi
            tokenized_corpus = [text.lower().split() for text in texts]
            bm25 = BM25Okapi(tokenized_corpus)
            tokenized_query = query.lower().split()
            scores = bm25.get_scores(tokenized_query).tolist()
            sorted_indices = np.argsort(scores)[::-1]
            sorted_vectors = results.vectors[sorted_indices] if results.vectors is not None else np.array([])
            return (
                [results.ids[i] for i in sorted_indices],
                [scores[i] for i in sorted_indices],
                [results.metadata[i] for i in sorted_indices],
                sorted_vectors,
            )
        except ImportError:
            return results.ids, results.distances, results.metadata, results.vectors


class SearchOptimizer:
    def __init__(
        self,
        index: VectorIndexBase,
        embedding_processor: Optional[EmbeddingProcessor] = None,
        query_rewriter: Optional[QueryRewriter] = None,
        hybrid_searcher: Optional[HybridSearcher] = None,
        reranker: Optional[Reranker] = None,
    ):
        self.index = index
        self.embedding_processor = embedding_processor
        self.query_rewriter = query_rewriter or QueryRewriter(strategy=QueryRewriteStrategy.NONE)
        self.hybrid_searcher = hybrid_searcher or HybridSearcher(dense_index=index)
        self.reranker = reranker or Reranker(method=RerankMethod.SIMILARITY, embedding_processor=embedding_processor)

    def search(
        self,
        query: Union[str, np.ndarray],
        k: int = 10,
        enable_rewrite: bool = False,
        enable_rerank: bool = False,
        enable_hybrid: bool = False,
        texts: Optional[List[str]] = None,
        filter_func: Optional[callable] = None,
        **kwargs: Any,
    ) -> OptimizedSearchResult:
        import time
        import timeit
        start_time = timeit.default_timer()
        original_query = query if isinstance(query, str) else ""
        rewritten_queries = [original_query]
        if enable_rewrite and isinstance(query, str):
            rewritten_queries = self.query_rewriter.rewrite(query, **kwargs)
        if isinstance(query, str) and self.embedding_processor is not None:
            query_vecs = self.embedding_processor.encode(rewritten_queries, **kwargs)
        elif isinstance(query, np.ndarray):
            query_vecs = query if query.ndim == 2 else query.reshape(1, -1)
        else:
            raise ValueError("Query must be a string or numpy array")
        all_results = []
        for qv in query_vecs:
            if enable_hybrid:
                result = self.hybrid_searcher.search(
                    qv,
                    sparse_query=original_query,
                    k=k * 2,
                    filter_func=filter_func,
                    **kwargs,
                )
            else:
                result = self.index.search(qv, k=k * 2, filter_func=filter_func, **kwargs)
            all_results.append(result)
        merged_result = self._merge_multi_query_results(all_results, k * 2)
        search_time = (timeit.default_timer() - start_time) * 1000
        rerank_time = 0.0
        rerank_scores = merged_result.distances
        if enable_rerank and len(merged_result) > 0:
            rerank_start = timeit.default_timer()
            rerank_ids, rerank_scores, rerank_metadata, rerank_vectors = self.reranker.rerank(
                original_query,
                merged_result,
                texts=texts,
                **kwargs,
            )
            merged_result = SearchResult(
                ids=rerank_ids[:k],
                distances=rerank_scores[:k],
                vectors=rerank_vectors[:k] if len(rerank_vectors) > 0 else None,
                metadata=rerank_metadata[:k],
            )
            rerank_time = (timeit.default_timer() - rerank_start) * 1000
        else:
            merged_result = SearchResult(
                ids=merged_result.ids[:k],
                distances=merged_result.distances[:k],
                vectors=merged_result.vectors[:k] if merged_result.vectors is not None else None,
                metadata=merged_result.metadata[:k],
            )
        return OptimizedSearchResult(
            results=merged_result,
            original_query=original_query,
            rewritten_queries=rewritten_queries,
            rerank_scores=rerank_scores[:k],
            search_time_ms=search_time,
            rerank_time_ms=rerank_time,
        )

    def _merge_multi_query_results(
        self,
        results: List[SearchResult],
        k: int,
    ) -> SearchResult:
        if len(results) == 1:
            return results[0]
        merged_scores: Dict[int, float] = {}
        merged_metadata: Dict[int, Dict[str, Any]] = {}
        merged_vectors: Dict[int, np.ndarray] = {}
        for result in results:
            for rank, (id_, dist) in enumerate(zip(result.ids, result.distances)):
                score = 1.0 / (rank + 60)
                merged_scores[id_] = merged_scores.get(id_, 0.0) + score
                if id_ not in merged_metadata:
                    merged_metadata[id_] = result.metadata[rank] if rank < len(result.metadata) else {}
                if id_ not in merged_vectors and result.vectors is not None and rank < len(result.vectors):
                    merged_vectors[id_] = result.vectors[rank]
        sorted_items = sorted(merged_scores.items(), key=lambda x: x[1], reverse=True)[:k]
        ids = [x[0] for x in sorted_items]
        scores = [x[1] for x in sorted_items]
        metadata = [merged_metadata.get(id_, {}) for id_ in ids]
        vectors = np.array([merged_vectors[id_] for id_ in ids if id_ in merged_vectors]) if merged_vectors else None
        return SearchResult(
            ids=ids,
            distances=scores,
            vectors=vectors,
            metadata=metadata,
        )
