from typing import List, Dict, Optional, Any
from collections import defaultdict
from loguru import logger
import numpy as np

from recommendation_engine.content_embedding_index import ContentEmbeddingIndex
from recommendation_engine.infrastructure.redis_client import RedisClient
from recommendation_engine.models.schemas import (
    RankResultItem,
    RerankResultItem,
)
from recommendation_engine.realtime_rank_pipeline.business_rule_injector import BusinessRuleInjector
from config import settings


class RerankLayer:
    def __init__(
        self,
        content_index: ContentEmbeddingIndex,
        redis_client: Optional[RedisClient] = None,
    ):
        self._content_index = content_index
        self._mmr_lambda = settings.pipeline_mmr_lambda
        self._rerank_top_k = settings.pipeline_rerank_top_k
        if redis_client is not None:
            self._rule_injector = BusinessRuleInjector(
                redis_client=redis_client,
                content_index=content_index,
            )

    async def rerank(
        self,
        user_id: str,
        ranked_items: List[RankResultItem],
        top_k: Optional[int] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> List[RerankResultItem]:
        top_k = top_k or self._rerank_top_k
        experiment_config = experiment_config or {}

        if not ranked_items:
            return []

        mmr_lambda = experiment_config.get("mmr_lambda", self._mmr_lambda)
        enable_diversity = experiment_config.get("enable_diversity", True)
        business_rules = experiment_config.get("business_rules", [])

        rule_adjustments = await self._apply_business_rules(
            ranked_items, business_rules
        )

        if enable_diversity:
            reranked = await self._mmr_rerank(
                ranked_items, rule_adjustments, top_k, mmr_lambda
            )
        else:
            reranked = await self._simple_rerank(
                ranked_items, rule_adjustments, top_k
            )

        if hasattr(self, '_rule_injector') and self._rule_injector is not None:
            reranked = await self._rule_injector.apply_rules(reranked)

        for rank, item in enumerate(reranked, 1):
            item.rank = rank

        logger.debug(
            f"Rerank layer returned {len(reranked)} items for user {user_id}"
        )
        return reranked

    async def _mmr_rerank(
        self,
        ranked_items: List[RankResultItem],
        rule_adjustments: Dict[str, float],
        top_k: int,
        lambda_param: float,
    ) -> List[RerankResultItem]:
        EXCLUDE_THRESHOLD = -5000.0
        content_ids = [item.content_id for item in ranked_items
                       if rule_adjustments.get(item.content_id, 0.0) > EXCLUDE_THRESHOLD]
        original_scores = {item.content_id: item.final_score for item in ranked_items}

        content_embeddings = {}
        for cid in content_ids:
            emb = await self._content_index.get_content_embedding(cid)
            if emb is not None:
                content_embeddings[cid] = emb

        selected: List[str] = []
        remaining = set(content_ids)
        reranked_items: List[RerankResultItem] = []

        while len(selected) < top_k and remaining:
            best_cid = None
            best_mmr_score = float("-inf")
            best_diversity_penalty = 0.0

            for cid in remaining:
                original_score = original_scores.get(cid, 0.0)
                adjustment = rule_adjustments.get(cid, 0.0)
                relevance = original_score + adjustment

                diversity_penalty = 0.0
                if selected and cid in content_embeddings:
                    cid_emb = content_embeddings[cid]
                    max_sim = 0.0
                    for sel_cid in selected:
                        if sel_cid in content_embeddings:
                            sel_emb = content_embeddings[sel_cid]
                            norm1 = np.linalg.norm(cid_emb)
                            norm2 = np.linalg.norm(sel_emb)
                            if norm1 > 0 and norm2 > 0:
                                sim = float(np.dot(cid_emb, sel_emb) / (norm1 * norm2))
                                max_sim = max(max_sim, (sim + 1.0) / 2.0)
                    diversity_penalty = max_sim

                mmr_score = (
                    lambda_param * relevance
                    - (1 - lambda_param) * diversity_penalty
                )

                if mmr_score > best_mmr_score:
                    best_mmr_score = mmr_score
                    best_cid = cid
                    best_diversity_penalty = diversity_penalty

            if best_cid is None:
                break

            selected.append(best_cid)
            remaining.remove(best_cid)

            original_score = original_scores.get(best_cid, 0.0)
            adjustment = rule_adjustments.get(best_cid, 0.0)

            reranked_items.append(
                RerankResultItem(
                    content_id=best_cid,
                    final_score=original_score + adjustment,
                    diversity_penalty=best_diversity_penalty,
                    rule_adjustment=adjustment,
                )
            )

        return reranked_items

    async def _simple_rerank(
        self,
        ranked_items: List[RankResultItem],
        rule_adjustments: Dict[str, float],
        top_k: int,
    ) -> List[RerankResultItem]:
        EXCLUDE_THRESHOLD = -5000.0
        adjusted_scores = []
        for item in ranked_items:
            adjustment = rule_adjustments.get(item.content_id, 0.0)
            if adjustment <= EXCLUDE_THRESHOLD:
                continue
            adjusted_scores.append(
                (
                    item.content_id,
                    item.final_score + adjustment,
                    adjustment,
                )
            )

        adjusted_scores.sort(key=lambda x: x[1], reverse=True)
        adjusted_scores = adjusted_scores[:top_k]

        return [
            RerankResultItem(
                content_id=cid,
                final_score=score,
                diversity_penalty=0.0,
                rule_adjustment=adjustment,
            )
            for cid, score, adjustment in adjusted_scores
        ]

    async def _apply_business_rules(
        self,
        ranked_items: List[RankResultItem],
        rules: List[Dict[str, Any]],
    ) -> Dict[str, float]:
        adjustments: Dict[str, float] = defaultdict(float)

        if not rules:
            return adjustments

        content_infos = {}
        for item in ranked_items:
            info = await self._content_index.get_content_info(item.content_id)
            if info:
                content_infos[item.content_id] = info

        for rule in rules:
            rule_type = rule.get("type")
            adjustment = float(rule.get("adjustment", 0.0))
            filter_criteria = rule.get("filter", {})

            for item in ranked_items:
                cid = item.content_id
                info = content_infos.get(cid, {})

                if self._match_rule(cid, info, filter_criteria):
                    if rule_type == "boost":
                        adjustments[cid] += adjustment
                    elif rule_type == "penalize":
                        adjustments[cid] -= adjustment
                    elif rule_type == "pin":
                        adjustments[cid] += 1000.0 + adjustment
                    elif rule_type == "exclude":
                        adjustments[cid] -= 10000.0

        return adjustments

    def _match_rule(
        self,
        content_id: str,
        content_info: Dict[str, Any],
        filter_criteria: Dict[str, Any],
    ) -> bool:
        if "content_ids" in filter_criteria:
            if content_id not in filter_criteria["content_ids"]:
                return False

        if "content_type" in filter_criteria:
            if content_info.get("content_type") != filter_criteria["content_type"]:
                return False

        if "categories" in filter_criteria:
            content_cats = set(content_info.get("categories", []))
            filter_cats = set(filter_criteria["categories"])
            if not content_cats.intersection(filter_cats):
                return False

        if "tags" in filter_criteria:
            content_tags = set(content_info.get("tags", []))
            filter_tags = set(filter_criteria["tags"])
            if not content_tags.intersection(filter_tags):
                return False

        if "min_popularity" in filter_criteria:
            if content_info.get("popularity_score", 0) < filter_criteria["min_popularity"]:
                return False

        return True

    async def ensure_category_diversity(
        self,
        items: List[RerankResultItem],
        max_per_category: int = 3,
    ) -> List[RerankResultItem]:
        content_infos = {}
        for item in items:
            info = await self._content_index.get_content_info(item.content_id)
            if info:
                content_infos[item.content_id] = info

        category_counts: Dict[str, int] = defaultdict(int)
        result = []

        for item in items:
            info = content_infos.get(item.content_id, {})
            categories = info.get("categories", [])

            max_count = 0
            for cat in categories:
                max_count = max(max_count, category_counts[cat])

            if max_count < max_per_category:
                for cat in categories:
                    category_counts[cat] += 1
                result.append(item)

        return result
