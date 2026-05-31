"""Vector index module for embedding index construction and ANN search optimization."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import Settings
from .embedding_index import VectorIndex, IndexType, DistanceMetric
from .ann_search import ANNSearcher, SearchResult, CacheConfig, RerankConfig


class VectorIndexModule:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or get_default_settings()
        self._indices: Dict[str, VectorIndex] = {}
        self._searchers: Dict[str, ANNSearcher] = {}
        self._logger = LogManager().get_logger(__name__)
        self._build_history: List[Dict[str, Any]] = []

    def get_index(self, index_name: str) -> Optional[VectorIndex]:
        return self._indices.get(index_name)

    def get_searcher(self, index_name: str) -> Optional[ANNSearcher]:
        return self._searchers.get(index_name)

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "index.create":
                create_result = self._handle_create_index(payload)
                result.results = [create_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Index created successfully"

            elif event_type == "index.add":
                add_result = self._handle_add_vectors(payload)
                result.results = [add_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Vectors added successfully"

            elif event_type == "index.build":
                build_result = self._handle_build_index(payload)
                result.results = [build_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Index built successfully"

            elif event_type == "index.search":
                search_result = self._handle_search(payload)
                result.results = [search_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Search completed successfully"

            elif event_type == "index.search.batch":
                batch_result = self._handle_batch_search(payload)
                result.results = [batch_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Batch search completed successfully"

            elif event_type == "index.search.hybrid":
                hybrid_result = self._handle_hybrid_search(payload)
                result.results = [hybrid_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Hybrid search completed successfully"

            elif event_type == "index.search.explain":
                explain_result = self._handle_explain_search(payload)
                result.results = [explain_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Search explanation generated"

            elif event_type == "index.optimize":
                optimize_result = self._handle_optimize_params(payload)
                result.results = [optimize_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Parameter optimization completed"

            elif event_type == "index.get":
                get_result = self._handle_get_vector(payload)
                result.results = [get_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Vector retrieved successfully"

            elif event_type == "index.update":
                update_result = self._handle_update_vector(payload)
                result.results = [update_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Vector updated successfully"

            elif event_type == "index.delete":
                delete_result = self._handle_delete_vector(payload)
                result.results = [delete_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Vector deleted successfully"

            elif event_type == "index.list":
                list_result = self._handle_list_vectors(payload)
                result.results = [list_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Vectors listed successfully"

            elif event_type == "index.stats":
                stats_result = self._handle_get_stats(payload)
                result.results = [stats_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Index stats retrieved"

            elif event_type == "index.save":
                save_result = self._handle_save_index(payload)
                result.results = [save_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Index saved successfully"

            elif event_type == "index.load":
                load_result = self._handle_load_index(payload)
                result.results = [load_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Index loaded successfully"

            elif event_type == "index.clear":
                clear_result = self._handle_clear_index(payload)
                result.results = [clear_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Index cleared successfully"

            elif event_type == "index.cache.clear":
                cache_result = self._handle_clear_cache(payload)
                result.results = [cache_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Search cache cleared"

            elif event_type == "index.stats.reset":
                reset_result = self._handle_reset_stats(payload)
                result.results = [reset_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Search stats reset"

            elif event_type == "index.list_indices":
                list_idx_result = self._handle_list_indices(payload)
                result.results = [list_idx_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Indices listed successfully"

            elif event_type == "index.filter":
                filter_result = self._handle_filter_results(payload)
                result.results = [filter_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Filtering completed successfully"

            elif event_type == "index.performance":
                perf_result = self._handle_get_performance(payload)
                result.results = [perf_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Performance report retrieved"

            elif event_type == "index.history":
                history_result = self._handle_get_history(payload)
                result.results = [history_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Query history retrieved"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Vector index event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Vector index event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    def _handle_create_index(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        index_type = IndexType(payload.get("index_type", "hnsw"))
        dimension = payload.get("dimension", 128)
        distance_metric = DistanceMetric(payload.get("distance_metric", "cosine"))
        index_params = payload.get("index_params", {})

        if index_name in self._indices:
            raise ValidationError(
                message=f"Index already exists: {index_name}",
                suggestion="Use a different index name or delete the existing index first.",
            )

        index = VectorIndex(
            index_type=index_type,
            dimension=dimension,
            distance_metric=distance_metric,
            **index_params,
        )

        cache_config_data = payload.get("cache_config")
        cache_config = CacheConfig(**cache_config_data) if cache_config_data else CacheConfig()

        rerank_config_data = payload.get("rerank_config")
        rerank_config = RerankConfig(**rerank_config_data) if rerank_config_data else RerankConfig()

        searcher = ANNSearcher(
            index=index,
            cache_config=cache_config,
            rerank_config=rerank_config,
        )

        self._indices[index_name] = index
        self._searchers[index_name] = searcher

        return {
            "index_name": index_name,
            "index_type": index_type.value,
            "dimension": dimension,
            "distance_metric": distance_metric.value,
            "index_params": index_params,
        }

    def _handle_add_vectors(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        vectors = payload.get("vectors")
        metadata_list = payload.get("metadata")
        ids = payload.get("ids")

        if not index_name or not vectors:
            raise ValidationError(
                message="Index name and vectors are required",
                suggestion="Provide 'index_name' and 'vectors' in the payload.",
            )

        index = self._get_or_create_index(index_name)

        uuids = None
        if ids:
            uuids = [UUID(id_str) if isinstance(id_str, str) else id_str for id_str in ids]

        if uuids:
            record_ids = []
            for i, vector in enumerate(vectors):
                metadata = metadata_list[i] if metadata_list else None
                rid = index.add_vector(vector, metadata, uuids[i])
                record_ids.append(rid)
        else:
            record_ids = index.add_vectors(vectors, metadata_list)

        return {
            "index_name": index_name,
            "added_count": len(record_ids),
            "record_ids": [str(rid) for rid in record_ids],
            "current_size": index.size,
        }

    def _handle_build_index(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        index = self._get_index(index_name)
        start_time = datetime.utcnow()

        index.build()

        build_time = (datetime.utcnow() - start_time).total_seconds() * 1000

        build_record = {
            "index_name": index_name,
            "timestamp": start_time.isoformat(),
            "build_time_ms": build_time,
            "vector_count": index.size,
            "index_type": index.index_type.value,
        }
        self._build_history.append(build_record)

        return {
            "index_name": index_name,
            "built": True,
            "build_time_ms": build_time,
            "vector_count": index.size,
            "stats": index.get_stats(),
        }

    def _handle_search(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        query_vector = payload.get("query_vector")
        top_k = payload.get("top_k", 10)
        use_cache = payload.get("use_cache")
        enable_rerank = payload.get("enable_rerank")
        search_params = payload.get("search_params", {})

        if not index_name or not query_vector:
            raise ValidationError(
                message="Index name and query vector are required",
                suggestion="Provide 'index_name' and 'query_vector' in the payload.",
            )

        searcher = self._get_searcher(index_name)

        results = searcher.search(
            query_vector=query_vector,
            top_k=top_k,
            use_cache=use_cache,
            enable_rerank=enable_rerank,
            **search_params,
        )

        return {
            "index_name": index_name,
            "query_dim": len(query_vector),
            "top_k": top_k,
            "result_count": len(results),
            "results": [r.to_dict() for r in results],
        }

    def _handle_batch_search(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        query_vectors = payload.get("query_vectors")
        top_k = payload.get("top_k", 10)
        search_params = payload.get("search_params", {})

        if not index_name or not query_vectors:
            raise ValidationError(
                message="Index name and query vectors are required",
                suggestion="Provide 'index_name' and 'query_vectors' in the payload.",
            )

        searcher = self._get_searcher(index_name)

        results = searcher.batch_search(
            query_vectors=query_vectors,
            top_k=top_k,
            **search_params,
        )

        return {
            "index_name": index_name,
            "query_count": len(query_vectors),
            "top_k": top_k,
            "results": [
                [r.to_dict() for r in query_results]
                for query_results in results
            ],
        }

    def _handle_hybrid_search(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        query_vector = payload.get("query_vector")
        text_query = payload.get("text_query")
        top_k = payload.get("top_k", 10)
        vector_weight = payload.get("vector_weight", 0.7)
        text_weight = payload.get("text_weight", 0.3)
        search_params = payload.get("search_params", {})

        if not index_name or not query_vector:
            raise ValidationError(
                message="Index name and query vector are required",
                suggestion="Provide 'index_name' and 'query_vector' in the payload.",
            )

        searcher = self._get_searcher(index_name)

        results = searcher.hybrid_search(
            query_vector=query_vector,
            text_query=text_query,
            top_k=top_k,
            vector_weight=vector_weight,
            text_weight=text_weight,
            **search_params,
        )

        return {
            "index_name": index_name,
            "top_k": top_k,
            "vector_weight": vector_weight,
            "text_weight": text_weight,
            "result_count": len(results),
            "results": [r.to_dict() for r in results],
        }

    def _handle_explain_search(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        query_vector = payload.get("query_vector")
        top_k = payload.get("top_k", 10)
        search_params = payload.get("search_params", {})

        if not index_name or not query_vector:
            raise ValidationError(
                message="Index name and query vector are required",
                suggestion="Provide 'index_name' and 'query_vector' in the payload.",
            )

        searcher = self._get_searcher(index_name)

        explanation = searcher.explain_search(
            query_vector=query_vector,
            top_k=top_k,
            **search_params,
        )

        return {
            "index_name": index_name,
            "explanation": explanation,
        }

    def _handle_optimize_params(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        query_vectors = payload.get("query_vectors")
        ground_truth = payload.get("ground_truth")
        top_k = payload.get("top_k", 10)

        if not index_name or not query_vectors or not ground_truth:
            raise ValidationError(
                message="Index name, query vectors, and ground truth are required",
                suggestion="Provide 'index_name', 'query_vectors', and 'ground_truth' in the payload.",
            )

        searcher = self._get_searcher(index_name)

        gt_uuids = [
            [UUID(id_str) if isinstance(id_str, str) else id_str for id_str in ids]
            for ids in ground_truth
        ]

        optimization = searcher.optimize_search_params(
            query_vectors=query_vectors,
            ground_truth=gt_uuids,
            top_k=top_k,
        )

        return {
            "index_name": index_name,
            "optimization": optimization,
        }

    def _handle_get_vector(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        record_id = payload.get("record_id")

        if not index_name or not record_id:
            raise ValidationError(
                message="Index name and record ID are required",
                suggestion="Provide 'index_name' and 'record_id' in the payload.",
            )

        index = self._get_index(index_name)
        rid = UUID(record_id) if isinstance(record_id, str) else record_id
        record = index.get_vector(rid)

        if not record:
            raise ValidationError(
                message=f"Vector not found: {record_id}",
                suggestion="Check that the record ID is correct.",
            )

        return {
            "index_name": index_name,
            "record_id": str(record.id),
            "vector": record.vector,
            "metadata": record.metadata,
            "timestamp": record.timestamp,
        }

    def _handle_update_vector(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        record_id = payload.get("record_id")
        vector = payload.get("vector")
        metadata = payload.get("metadata")

        if not index_name or not record_id:
            raise ValidationError(
                message="Index name and record ID are required",
                suggestion="Provide 'index_name' and 'record_id' in the payload.",
            )

        index = self._get_index(index_name)
        rid = UUID(record_id) if isinstance(record_id, str) else record_id

        updated = index.update_vector(rid, vector, metadata)

        return {
            "index_name": index_name,
            "record_id": str(rid),
            "updated": updated,
        }

    def _handle_delete_vector(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        record_id = payload.get("record_id")

        if not index_name or not record_id:
            raise ValidationError(
                message="Index name and record ID are required",
                suggestion="Provide 'index_name' and 'record_id' in the payload.",
            )

        index = self._get_index(index_name)
        rid = UUID(record_id) if isinstance(record_id, str) else record_id

        deleted = index.delete_vector(rid)

        return {
            "index_name": index_name,
            "record_id": str(rid),
            "deleted": deleted,
            "current_size": index.size,
        }

    def _handle_list_vectors(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        limit = payload.get("limit")
        offset = payload.get("offset", 0)

        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        index = self._get_index(index_name)
        records = index.list_vectors(limit=limit, offset=offset)

        return {
            "index_name": index_name,
            "total": index.size,
            "returned": len(records),
            "offset": offset,
            "records": records,
        }

    def _handle_get_stats(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        include_search_stats = payload.get("include_search_stats", True)

        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        index = self._get_index(index_name)
        stats = index.get_stats()

        if include_search_stats:
            searcher = self._searchers.get(index_name)
            if searcher:
                stats["search_stats"] = searcher.stats.to_dict()

        return {
            "index_name": index_name,
            "stats": stats,
        }

    def _handle_save_index(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        filepath = payload.get("filepath")

        if not index_name or not filepath:
            raise ValidationError(
                message="Index name and filepath are required",
                suggestion="Provide 'index_name' and 'filepath' in the payload.",
            )

        index = self._get_index(index_name)
        index.save(filepath)

        return {
            "index_name": index_name,
            "filepath": filepath,
            "saved": True,
        }

    def _handle_load_index(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        filepath = payload.get("filepath")
        cache_config_data = payload.get("cache_config")
        rerank_config_data = payload.get("rerank_config")

        if not index_name or not filepath:
            raise ValidationError(
                message="Index name and filepath are required",
                suggestion="Provide 'index_name' and 'filepath' in the payload.",
            )

        index = VectorIndex.load(filepath)

        cache_config = CacheConfig(**cache_config_data) if cache_config_data else CacheConfig()
        rerank_config = RerankConfig(**rerank_config_data) if rerank_config_data else RerankConfig()

        searcher = ANNSearcher(
            index=index,
            cache_config=cache_config,
            rerank_config=rerank_config,
        )

        self._indices[index_name] = index
        self._searchers[index_name] = searcher

        return {
            "index_name": index_name,
            "filepath": filepath,
            "loaded": True,
            "vector_count": index.size,
            "index_type": index.index_type.value,
        }

    def _handle_clear_index(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        index = self._get_index(index_name)
        index.clear()

        return {
            "index_name": index_name,
            "cleared": True,
        }

    def _handle_clear_cache(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        searcher = self._get_searcher(index_name)
        cleared_count = searcher.clear_cache()

        return {
            "index_name": index_name,
            "cleared_count": cleared_count,
        }

    def _handle_reset_stats(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        searcher = self._get_searcher(index_name)
        searcher.reset_stats()

        return {
            "index_name": index_name,
            "reset": True,
        }

    def _handle_list_indices(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        indices_info = []
        for name, index in self._indices.items():
            stats = index.get_stats()
            searcher = self._searchers.get(name)
            search_stats = searcher.stats.to_dict() if searcher else None

            indices_info.append({
                "name": name,
                "index_type": stats["index_type"],
                "dimension": stats["dimension"],
                "size": stats["size"],
                "is_built": stats["is_built"],
                "search_stats": search_stats,
            })

        return {
            "total_indices": len(self._indices),
            "indices": indices_info,
        }

    def _handle_filter_results(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        results_data = payload.get("results", [])
        filter_conditions = payload.get("filter_conditions", {})

        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        searcher = self._get_searcher(index_name)

        results = [
            SearchResult(
                id=UUID(r["id"]),
                score=r["score"],
                metadata=r["metadata"],
                rank=r["rank"],
            )
            for r in results_data
        ]

        filtered = searcher.filter_by_metadata(results, filter_conditions)

        return {
            "index_name": index_name,
            "original_count": len(results),
            "filtered_count": len(filtered),
            "results": [r.to_dict() for r in filtered],
        }

    def _handle_get_performance(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        searcher = self._get_searcher(index_name)
        report = searcher.get_performance_report()

        return {
            "index_name": index_name,
            "performance": report,
        }

    def _handle_get_history(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        index_name = payload.get("index_name")
        limit = payload.get("limit")
        min_time_ms = payload.get("min_time_ms")
        max_time_ms = payload.get("max_time_ms")

        if not index_name:
            raise ValidationError(
                message="Index name is required",
                suggestion="Provide 'index_name' in the payload.",
            )

        searcher = self._get_searcher(index_name)
        history = searcher.get_query_history(
            limit=limit,
            min_time_ms=min_time_ms,
            max_time_ms=max_time_ms,
        )

        return {
            "index_name": index_name,
            "total_history": len(history),
            "history": history,
        }

    def _get_index(self, index_name: str) -> VectorIndex:
        if index_name not in self._indices:
            raise ValidationError(
                message=f"Index not found: {index_name}",
                suggestion="Create the index first or check the index name.",
            )
        return self._indices[index_name]

    def _get_or_create_index(self, index_name: str) -> VectorIndex:
        if index_name not in self._indices:
            index = VectorIndex()
            self._indices[index_name] = index
            self._searchers[index_name] = ANNSearcher(index=index)
        return self._indices[index_name]

    def _get_searcher(self, index_name: str) -> ANNSearcher:
        if index_name not in self._searchers:
            self._get_index(index_name)
            index = self._indices[index_name]
            self._searchers[index_name] = ANNSearcher(index=index)
        return self._searchers[index_name]

    def create_index(self, index_name: str, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.create",
            payload={"index_name": index_name, **kwargs},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def add_vectors(self, index_name: str, vectors: List[List[float]], **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.add",
            payload={"index_name": index_name, "vectors": vectors, **kwargs},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def build_index(self, index_name: str) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.build",
            payload={"index_name": index_name},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def search(self, index_name: str, query_vector: List[float], **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.search",
            payload={"index_name": index_name, "query_vector": query_vector, **kwargs},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def batch_search(self, index_name: str, query_vectors: List[List[float]], **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.search.batch",
            payload={"index_name": index_name, "query_vectors": query_vectors, **kwargs},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def hybrid_search(
        self,
        index_name: str,
        query_vector: List[float],
        text_query: Optional[str] = None,
        **kwargs: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.search.hybrid",
            payload={
                "index_name": index_name,
                "query_vector": query_vector,
                "text_query": text_query,
                **kwargs,
            },
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def save_index(self, index_name: str, filepath: str) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.save",
            payload={"index_name": index_name, "filepath": filepath},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def load_index(self, index_name: str, filepath: str, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.load",
            payload={"index_name": index_name, "filepath": filepath, **kwargs},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def get_index_stats(self, index_name: str, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.stats",
            payload={"index_name": index_name, **kwargs},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def list_indices(self) -> Dict[str, Any]:
        event = EventMessage(
            event_type="index.list_indices",
            payload={},
            source="vector_index",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}
