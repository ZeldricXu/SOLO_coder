from typing import Optional, List, Dict, Any
import time
from datetime import datetime
from loguru import logger

from recommendation_engine.infrastructure import RedisClient, PostgresClient
from recommendation_engine.user_profile_service import UserProfileService, get_user_profile_service
from recommendation_engine.content_embedding_index import ContentEmbeddingIndex, get_content_embedding_index
from recommendation_engine.collaborative_filter import CollaborativeFilter, get_collaborative_filter
from recommendation_engine.models.schemas import (
    RecommendRequest,
    RecommendResponse,
    RerankResultItem,
)
from .recall_layer import RecallLayer
from .rank_layer import RankLayer
from .rerank_layer import RerankLayer
from config import settings


class RealtimeRankPipeline:
    _instance: Optional["RealtimeRankPipeline"] = None

    def __new__(cls) -> "RealtimeRankPipeline":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(
        self,
        redis_client: RedisClient,
        postgres_client: PostgresClient,
        user_profile_service: Optional[UserProfileService] = None,
        content_index: Optional[ContentEmbeddingIndex] = None,
        cf_service: Optional[CollaborativeFilter] = None,
    ) -> None:
        self._redis = redis_client
        self._postgres = postgres_client

        self._user_profile = user_profile_service or await get_user_profile_service(
            redis_client, postgres_client
        )
        self._content_index = content_index or await get_content_embedding_index(
            redis_client, postgres_client
        )
        self._cf_service = cf_service or await get_collaborative_filter(redis_client)

        self._recall_layer = RecallLayer(
            self._user_profile, self._content_index, self._cf_service
        )
        self._rank_layer = RankLayer(
            self._user_profile, self._content_index, self._cf_service
        )
        self._rerank_layer = RerankLayer(self._content_index)

        logger.info("RealtimeRankPipeline initialized")

    async def close(self) -> None:
        logger.info("RealtimeRankPipeline closed")

    async def recommend(
        self,
        request: RecommendRequest,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> RecommendResponse:
        start_time = time.time()
        experiment_config = experiment_config or {}

        try:
            recall_items = await self._recall_layer.recall(
                user_id=request.user_id,
                top_k=settings.pipeline_recall_top_k,
                exclude_content_ids=request.exclude_content_ids,
                experiment_config=experiment_config.get("recall_config", {}),
            )

            ranked_items = await self._rank_layer.rank(
                user_id=request.user_id,
                recall_items=recall_items,
                top_k=settings.pipeline_rank_top_k,
                experiment_config=experiment_config.get("rank_config", {}),
            )

            reranked_items = await self._rerank_layer.rerank(
                user_id=request.user_id,
                ranked_items=ranked_items,
                top_k=request.top_n,
                experiment_config=experiment_config.get("rerank_config", {}),
            )

            results = self._apply_position_bias_correction(reranked_items)

            processing_time_ms = (time.time() - start_time) * 1000

            return RecommendResponse(
                request_id=request.request_id,
                user_id=request.user_id,
                scene=request.scene,
                results=results,
                experiment_info=experiment_config.get("experiment_info"),
                processing_time_ms=processing_time_ms,
            )

        except Exception as e:
            logger.error(
                f"Recommendation pipeline failed for user {request.user_id}: {e}",
                exc_info=True,
            )
            processing_time_ms = (time.time() - start_time) * 1000
            return RecommendResponse(
                request_id=request.request_id,
                user_id=request.user_id,
                scene=request.scene,
                results=[],
                experiment_info=experiment_config.get("experiment_info"),
                processing_time_ms=processing_time_ms,
            )

    async def recommend_for_item(
        self,
        content_id: str,
        user_id: Optional[str] = None,
        top_n: int = 20,
        exclude_content_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> RecommendResponse:
        start_time = time.time()
        request_id = f"item_rec_{content_id}_{int(time.time())}"
        experiment_config = experiment_config or {}

        try:
            recall_items = await self._recall_layer.recall_for_item(
                content_id=content_id,
                top_k=settings.pipeline_recall_top_k,
                exclude_content_ids=exclude_content_ids,
            )

            if user_id:
                ranked_items = await self._rank_layer.rank(
                    user_id=user_id,
                    recall_items=recall_items,
                    top_k=settings.pipeline_rank_top_k,
                    experiment_config=experiment_config.get("rank_config", {}),
                )

                reranked_items = await self._rerank_layer.rerank(
                    user_id=user_id,
                    ranked_items=ranked_items,
                    top_k=top_n,
                    experiment_config=experiment_config.get("rerank_config", {}),
                )
            else:
                reranked_items = [
                    RerankResultItem(
                        content_id=item.content_id,
                        final_score=item.score,
                        diversity_penalty=0.0,
                        rule_adjustment=0.0,
                        rank=idx + 1,
                    )
                    for idx, item in enumerate(recall_items[:top_n])
                ]

            results = self._apply_position_bias_correction(reranked_items)

            processing_time_ms = (time.time() - start_time) * 1000

            return RecommendResponse(
                request_id=request_id,
                user_id=user_id or "anonymous",
                scene="item_similar",
                results=results,
                experiment_info=experiment_config.get("experiment_info"),
                processing_time_ms=processing_time_ms,
            )

        except Exception as e:
            logger.error(
                f"Item recommendation pipeline failed for content {content_id}: {e}",
                exc_info=True,
            )
            processing_time_ms = (time.time() - start_time) * 1000
            return RecommendResponse(
                request_id=request_id,
                user_id=user_id or "anonymous",
                scene="item_similar",
                results=[],
                experiment_info=experiment_config.get("experiment_info"),
                processing_time_ms=processing_time_ms,
            )

    def _apply_position_bias_correction(
        self,
        items: List[RerankResultItem],
    ) -> List[RerankResultItem]:
        for idx, item in enumerate(items):
            position = idx + 1
            position_bias = 1.0 / (position ** 0.15)
            item.final_score = item.final_score * position_bias
        items.sort(key=lambda x: x.final_score, reverse=True)
        for idx, item in enumerate(items, 1):
            item.rank = idx
        return items

    async def reload_models(self) -> Dict[str, bool]:
        results = {}
        results["lgbm_model"] = self._rank_layer.reload_model()
        logger.info(f"Model reload results: {results}")
        return results

    async def get_pipeline_stats(self) -> Dict[str, Any]:
        return {
            "recall_top_k": settings.pipeline_recall_top_k,
            "rank_top_k": settings.pipeline_rank_top_k,
            "rerank_top_k": settings.pipeline_rerank_top_k,
            "mmr_lambda": settings.pipeline_mmr_lambda,
            "rank_model": self._rank_layer.get_model_info(),
            "faiss_stats": await self._content_index.get_index_stats(),
            "als_stats": self._cf_service.get_model_stats(),
        }

    async def explain_recommendation(
        self,
        user_id: str,
        content_id: str,
    ) -> Dict[str, Any]:
        explanation = {}

        profile = await self._user_profile.get_user_profile(user_id)
        if profile:
            explanation["user_tags"] = [
                {"tag": t.tag_name, "weight": round(t.weight, 4)}
                for t in profile.merge_tags()[:10]
            ]
            explanation["user_stats"] = profile.realtime_behavior_stats

        als_score = await self._cf_service.predict_score(user_id, content_id)
        explanation["als_score"] = round(als_score, 4)

        content_info = await self._content_index.get_content_info(content_id)
        if content_info:
            explanation["content"] = {
                "title": content_info.get("title"),
                "categories": content_info.get("categories"),
                "tags": content_info.get("tags"),
                "popularity": content_info.get("popularity_score"),
            }

        user_vector = await self._user_profile.get_user_interest_vector(user_id)
        content_embedding = await self._content_index.get_content_embedding(content_id)
        if user_vector is not None and content_embedding is not None:
            norm_user = __import__("numpy").linalg.norm(user_vector)
            norm_content = __import__("numpy").linalg.norm(content_embedding)
            if norm_user > 0 and norm_content > 0:
                cosine = float(
                    __import__("numpy").dot(user_vector, content_embedding)
                    / (norm_user * norm_content)
                )
                explanation["vector_similarity"] = round(cosine, 4)

        return explanation

    async def health_check(self) -> bool:
        try:
            redis_ok = await self._redis.ping()
            pg_ok = await self._postgres.health_check()
            faiss_ok = await self._content_index.health_check()
            cf_ok = await self._cf_service.health_check()
            return redis_ok and pg_ok and faiss_ok and cf_ok
        except Exception:
            return False


_rank_pipeline: Optional[RealtimeRankPipeline] = None


async def get_rank_pipeline(
    redis_client: Optional[RedisClient] = None,
    postgres_client: Optional[PostgresClient] = None,
) -> RealtimeRankPipeline:
    global _rank_pipeline
    if _rank_pipeline is None:
        if redis_client is None or postgres_client is None:
            raise RuntimeError(
                "Redis and Postgres clients are required for initialization"
            )
        _rank_pipeline = RealtimeRankPipeline()
        await _rank_pipeline.initialize(redis_client, postgres_client)
    return _rank_pipeline


def close_rank_pipeline() -> None:
    global _rank_pipeline
    _rank_pipeline = None
