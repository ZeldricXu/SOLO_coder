from typing import List, Dict, Tuple, Optional, Any
from collections import defaultdict
from loguru import logger
import numpy as np

from recommendation_engine.user_profile_service import UserProfileService
from recommendation_engine.content_embedding_index import ContentEmbeddingIndex
from recommendation_engine.collaborative_filter import CollaborativeFilter
from recommendation_engine.models.schemas import (
    UserProfile,
    RecallResultItem,
)
from config import settings


class RecallLayer:
    RECALL_SOURCES = {
        "vector_similarity": 1.0,
        "als_collaborative": 0.9,
        "tag_matching": 0.8,
        "popular": 0.6,
        "similar_items": 0.85,
    }

    def __init__(
        self,
        user_profile_service: UserProfileService,
        content_index: ContentEmbeddingIndex,
        cf_service: CollaborativeFilter,
    ):
        self._user_profile = user_profile_service
        self._content_index = content_index
        self._cf_service = cf_service
        self._recall_top_k = settings.pipeline_recall_top_k

    async def recall(
        self,
        user_id: str,
        top_k: Optional[int] = None,
        exclude_content_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> List[RecallResultItem]:
        top_k = top_k or self._recall_top_k
        exclude_content_ids = exclude_content_ids or []
        experiment_config = experiment_config or {}

        enabled_sources = experiment_config.get(
            "recall_sources", list(self.RECALL_SOURCES.keys())
        )
        source_weights = experiment_config.get(
            "recall_source_weights", self.RECALL_SOURCES
        )

        recall_tasks = []
        if "vector_similarity" in enabled_sources:
            recall_tasks.append(self._recall_by_vector(user_id, top_k, exclude_content_ids))
        if "als_collaborative" in enabled_sources:
            recall_tasks.append(self._recall_by_als(user_id, top_k, exclude_content_ids))
        if "tag_matching" in enabled_sources:
            recall_tasks.append(self._recall_by_tags(user_id, top_k, exclude_content_ids))
        if "popular" in enabled_sources:
            recall_tasks.append(self._recall_by_popular(top_k, exclude_content_ids))

        import asyncio
        results = await asyncio.gather(*recall_tasks, return_exceptions=True)

        merged: Dict[str, Dict[str, Any]] = {}
        source_names = [s for s in enabled_sources if s in ["vector_similarity", "als_collaborative", "tag_matching", "popular"]]

        for i, result in enumerate(results):
            if isinstance(result, Exception):
                logger.warning(f"Recall source {source_names[i]} failed: {result}")
                continue
            source = source_names[i]
            weight = source_weights.get(source, 1.0)
            for item in result:
                content_id = item[0]
                score = item[1]
                if content_id not in merged:
                    merged[content_id] = {
                        "content_id": content_id,
                        "scores": {},
                        "sources": [],
                        "best_score": 0.0,
                    }
                normalized_score = self._normalize_score(score, source)
                merged[content_id]["scores"][source] = normalized_score
                merged[content_id]["sources"].append(source)
                weighted_score = normalized_score * weight
                if weighted_score > merged[content_id]["best_score"]:
                    merged[content_id]["best_score"] = weighted_score

        recall_items = []
        for content_id, data in sorted(
            merged.items(), key=lambda x: x[1]["best_score"], reverse=True
        )[:top_k]:
            recall_items.append(
                RecallResultItem(
                    content_id=content_id,
                    score=data["best_score"],
                    recall_source=",".join(data["sources"]),
                )
            )

        for rank, item in enumerate(recall_items, 1):
            item.rank = rank

        logger.debug(
            f"Recall layer returned {len(recall_items)} items for user {user_id}"
        )
        return recall_items

    def _normalize_score(self, score: float, source: str) -> float:
        if source == "vector_similarity":
            return max(0.0, min(1.0, (score + 1.0) / 2.0))
        elif source == "als_collaborative":
            return max(0.0, min(1.0, 1.0 / (1.0 + np.exp(-score))))
        elif source == "tag_matching":
            return max(0.0, min(1.0, score))
        elif source == "popular":
            return max(0.0, min(1.0, score))
        else:
            return max(0.0, min(1.0, score))

    async def _recall_by_vector(
        self,
        user_id: str,
        top_k: int,
        exclude_content_ids: List[str],
    ) -> List[Tuple[str, float]]:
        user_vector = await self._user_profile.get_user_interest_vector(user_id)
        if user_vector is None or np.all(user_vector == 0):
            return []

        results = await self._content_index.search(
            user_vector, top_k=top_k, filter_content_ids=exclude_content_ids
        )
        return results

    async def _recall_by_als(
        self,
        user_id: str,
        top_k: int,
        exclude_content_ids: List[str],
    ) -> List[Tuple[str, float]]:
        results = await self._cf_service.recommend(
            user_id, top_k=top_k, exclude_items=exclude_content_ids
        )
        return results

    async def _recall_by_tags(
        self,
        user_id: str,
        top_k: int,
        exclude_content_ids: List[str],
    ) -> List[Tuple[str, float]]:
        profile = await self._user_profile.get_user_profile(user_id)
        if not profile:
            return []

        merged_tags = profile.merge_tags()[:10]
        if not merged_tags:
            return []

        exclude_set = set(exclude_content_ids)
        results: Dict[str, float] = defaultdict(float)

        for tag in merged_tags:
            tag_key = f"tag:contents:{tag.tag_id}"
            cached = await self._content_index._redis.zrevrangebyscore(
                tag_key, count=top_k, withscores=True
            )
            for item in cached:
                if isinstance(item, tuple):
                    content_id, score = str(item[0]), float(item[1])
                else:
                    content_id, score = str(item), 1.0
                if content_id in exclude_set:
                    continue
                results[content_id] += tag.weight * score

        return sorted(results.items(), key=lambda x: x[1], reverse=True)[:top_k]

    async def _recall_by_popular(
        self,
        top_k: int,
        exclude_content_ids: List[str],
    ) -> List[Tuple[str, float]]:
        exclude_set = set(exclude_content_ids)
        cached = await self._content_index._redis.zrevrangebyscore(
            "content:popularity", count=top_k + len(exclude_set), withscores=True
        )

        results = []
        for item in cached:
            if isinstance(item, tuple):
                content_id, score = str(item[0]), float(item[1])
            else:
                content_id, score = str(item), 1.0
            if content_id in exclude_set:
                continue
            results.append((content_id, score))
            if len(results) >= top_k:
                break

        return results

    async def recall_for_item(
        self,
        content_id: str,
        top_k: int = 100,
        exclude_content_ids: Optional[List[str]] = None,
    ) -> List[RecallResultItem]:
        exclude_content_ids = exclude_content_ids or []

        similar_results = await self._content_index.search_by_content(
            content_id, top_k=top_k, filter_content_ids=exclude_content_ids
        )

        als_similar = await self._cf_service.similar_items(
            content_id, top_k=top_k
        )

        merged: Dict[str, Dict[str, Any]] = {}

        for cid, score in similar_results:
            merged[cid] = {
                "content_id": cid,
                "score": self._normalize_score(score, "vector_similarity"),
                "source": "similar_items",
            }

        for cid, score in als_similar:
            normalized = self._normalize_score(score, "als_collaborative")
            if cid in merged:
                merged[cid]["score"] = max(merged[cid]["score"], normalized * 0.9)
                merged[cid]["source"] = "similar_items,als"
            else:
                merged[cid] = {
                    "content_id": cid,
                    "score": normalized * 0.9,
                    "source": "als",
                }

        items = []
        for cid, data in sorted(
            merged.items(), key=lambda x: x[1]["score"], reverse=True
        )[:top_k]:
            items.append(
                RecallResultItem(
                    content_id=cid,
                    score=data["score"],
                    recall_source=data["source"],
                )
            )

        for rank, item in enumerate(items, 1):
            item.rank = rank

        return items
