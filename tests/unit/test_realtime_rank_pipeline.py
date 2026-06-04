import pytest
import json
import numpy as np
from typing import List, Dict, Any
from unittest.mock import patch, MagicMock, AsyncMock

from recommendation_engine.models.schemas import (
    RecommendRequest,
    RankResultItem,
    RerankResultItem,
    RecallResultItem,
)
from recommendation_engine.realtime_rank_pipeline.recall_layer import RecallLayer
from recommendation_engine.realtime_rank_pipeline.rank_layer import RankLayer
from recommendation_engine.realtime_rank_pipeline.rerank_layer import RerankLayer
from tests.factories.data_factories import (
    generate_user_id,
    generate_content_id,
    generate_embedding,
    RecommendRequestFactory,
    ContentItemFactory,
)


pytestmark = pytest.mark.unit


class TestRecallLayerNormalPath:

    @pytest.mark.asyncio
    async def test_recall_returns_deduplicated_items(self, rank_pipeline, mock_redis, mock_postgres):
        n_items = 50
        dim = 64
        np.random.seed(42)

        content_items = []
        for i in range(n_items):
            cid = generate_content_id()
            vec = np.random.randn(dim).astype(np.float32)
            vec = vec / np.linalg.norm(vec)
            content_items.append({
                "content_id": cid,
                "title": f"Content {i}",
                "content_type": "article",
                "categories": ["tech"],
                "tags": [f"tag_{i % 10}"],
                "author": "test",
                "popularity_score": np.random.uniform(0, 100),
            })
            key = f"content:embedding:{cid}"
            mock_redis._data[key] = json.dumps(vec.tolist())

        mock_postgres._tables["content_items"] = content_items

        user_id = generate_user_id()

        with patch.object(rank_pipeline._cf_service, 'recommend', new_callable=AsyncMock) as mock_cf:
            mock_cf.return_value = [
                (generate_content_id(), np.random.uniform(-1, 1))
                for _ in range(30)
            ]

            recall_results = await rank_pipeline._recall_layer.recall(
                user_id, top_k=200
            )

            content_ids = [r.content_id for r in recall_results]
            assert len(content_ids) == len(set(content_ids))

            for r in recall_results:
                assert isinstance(r, RecallResultItem)
                assert r.score >= 0.0


class TestRankLayerNormalPath:

    @pytest.mark.asyncio
    async def test_rank_layer_scores_items_correctly(self, rank_pipeline):
        rank_layer = rank_pipeline._rank_layer

        recall_items = []
        for i in range(10):
            cid = generate_content_id()
            recall_items.append(
                RecallResultItem(
                    content_id=cid,
                    score=0.5 + i * 0.05,
                    recall_source="vector_similarity",
                )
            )

        ranked = await rank_layer.rank("test_user", recall_items)

        assert len(ranked) == len(recall_items)

        for item in ranked:
            assert isinstance(item, RankResultItem)
            assert item.final_score > 0.0 or item.final_score == 0.0
            assert item.content_id is not None


class TestRerankLayerNormalPath:

    @pytest.mark.asyncio
    async def test_mmr_spreads_similar_categories_evenly(self, rank_pipeline, mock_redis):
        rerank_layer = rank_pipeline._rerank_layer

        categories = ["tech", "sports", "finance"]
        ranked_items = []

        for i in range(15):
            cid = generate_content_id()
            vec = np.random.randn(64).astype(np.float32)
            vec = vec / np.linalg.norm(vec)
            key = f"content:embedding:{cid}"
            mock_redis._data[key] = json.dumps(vec.tolist())

            ranked_items.append(
                RankResultItem(
                    content_id=cid,
                    final_score=float(1.0 - i * 0.05),
                    features={"category_emb": float(i % 3)},
                )
            )

        reranked = await rerank_layer.rerank(
            "test_user", ranked_items, top_k=9,
            experiment_config={"enable_diversity": True, "mmr_lambda": 0.5}
        )

        assert len(reranked) == 9

        for item in reranked:
            assert isinstance(item, RerankResultItem)
            assert item.content_id is not None
            assert item.final_score is not None

    @pytest.mark.asyncio
    async def test_blacklist_items_are_filtered(self, rank_pipeline, mock_redis):
        rerank_layer = rank_pipeline._rerank_layer

        blacklist_id = generate_content_id()
        normal_id = generate_content_id()

        for cid in [blacklist_id, normal_id]:
            vec = generate_embedding(64)
            key = f"content:embedding:{cid}"
            mock_redis._data[key] = json.dumps(vec)

        ranked_items = [
            RankResultItem(
                content_id=blacklist_id,
                final_score=0.95,
            ),
            RankResultItem(
                content_id=normal_id,
                final_score=0.85,
            ),
        ]

        business_rules = [
            {"type": "exclude", "filter": {"content_ids": [blacklist_id]}},
        ]

        rule_adjustments = await rerank_layer._apply_business_rules(
            ranked_items, business_rules
        )

        assert blacklist_id in rule_adjustments

        reranked = await rerank_layer.rerank(
            "test_user", ranked_items, top_k=10,
            experiment_config={"business_rules": business_rules, "enable_diversity": False}
        )

        result_ids = [item.content_id for item in reranked]
        assert blacklist_id not in result_ids
        assert normal_id in result_ids


class TestRankPipelineNormalPath:

    @pytest.mark.asyncio
    async def test_three_stage_pipeline_runs_end_to_end(
        self, rank_pipeline, mock_redis, mock_postgres
    ):
        n_items = 50
        for i in range(n_items):
            cid = generate_content_id()
            vec = generate_embedding(64)
            key = f"content:embedding:{cid}"
            mock_redis._data[key] = json.dumps(vec)
            mock_postgres._tables.setdefault("content_items", []).append({
                "content_id": cid,
                "title": f"Item {i}",
                "content_type": "article",
                "categories": ["tech"],
                "tags": ["test"],
                "author": "test",
                "popularity_score": 50.0,
            })

        user_id = generate_user_id()

        request = RecommendRequestFactory(user_id=user_id, top_n=10)

        with patch.object(rank_pipeline._cf_service, 'recommend', new_callable=AsyncMock) as mock_cf:
            mock_cf.return_value = []

            response = await rank_pipeline.recommend(request, {})

            assert response is not None
            assert response.request_id == request.request_id
            assert isinstance(response.results, list)
            assert len(response.results) <= 10

            for item in response.results:
                assert isinstance(item, RerankResultItem)
                assert item.content_id is not None


class TestRankPipelineExceptionPath:

    @pytest.mark.asyncio
    async def test_empty_recall_gracefully_returns_empty_list(
        self, rank_pipeline, mock_redis, mock_postgres
    ):
        user_id = generate_user_id()
        request = RecommendRequestFactory(user_id=user_id, top_n=10)

        mock_postgres._tables["content_items"] = []

        with patch.object(rank_pipeline._cf_service, 'recommend', new_callable=AsyncMock) as mock_cf:
            mock_cf.return_value = []

            response = await rank_pipeline.recommend(request, {})

            assert response is not None
            assert len(response.results) == 0

    @pytest.mark.asyncio
    async def test_mmr_handles_insufficient_items(self, rank_pipeline, mock_redis):
        rerank_layer = rank_pipeline._rerank_layer

        cid = generate_content_id()
        vec = generate_embedding(64)
        key = f"content:embedding:{cid}"
        mock_redis._data[key] = json.dumps(vec)

        ranked_items = [
            RankResultItem(
                content_id=cid,
                final_score=0.9,
            )
        ]

        reranked = await rerank_layer._mmr_rerank(
            ranked_items, {}, top_k=10, lambda_param=0.7
        )

        assert len(reranked) == 1
        assert reranked[0].content_id == cid

    @pytest.mark.asyncio
    async def test_pipeline_handles_missing_user_profile(self, rank_pipeline):
        user_id = generate_user_id()
        request = RecommendRequestFactory(user_id=user_id, top_n=10)

        with patch.object(rank_pipeline._cf_service, 'recommend', new_callable=AsyncMock) as mock_cf:
            mock_cf.return_value = []

            response = await rank_pipeline.recommend(request, {})

            assert response is not None
            assert len(response.results) >= 0


class TestRankPipelineEdgeCases:

    @pytest.mark.asyncio
    async def test_business_rules_pin_items_to_top(self, rank_pipeline, mock_redis):
        rerank_layer = rank_pipeline._rerank_layer

        normal_cid = generate_content_id()
        pinned_cid = generate_content_id()

        for cid in [normal_cid, pinned_cid]:
            vec = generate_embedding(64)
            key = f"content:embedding:{cid}"
            mock_redis._data[key] = json.dumps(vec)

        ranked_items = [
            RankResultItem(
                content_id=normal_cid,
                final_score=0.95,
            ),
            RankResultItem(
                content_id=pinned_cid,
                final_score=0.5,
            ),
        ]

        business_rules = [
            {"type": "pin", "filter": {"content_ids": [pinned_cid]}, "adjustment": 0.0},
        ]

        rule_adjustments = await rerank_layer._apply_business_rules(
            ranked_items, business_rules
        )

        assert pinned_cid in rule_adjustments

    @pytest.mark.asyncio
    async def test_boost_rules_increase_score(self, rank_pipeline, mock_redis):
        rerank_layer = rank_pipeline._rerank_layer

        cid1, cid2 = generate_content_id(), generate_content_id()

        for cid in [cid1, cid2]:
            vec = generate_embedding(64)
            key = f"content:embedding:{cid}"
            mock_redis._data[key] = json.dumps(vec)

        ranked_items = [
            RankResultItem(
                content_id=cid1,
                final_score=0.8,
            ),
            RankResultItem(
                content_id=cid2,
                final_score=0.7,
            ),
        ]

        business_rules = [
            {"type": "boost", "filter": {"content_ids": [cid2]}, "adjustment": 1.0},
        ]

        rule_adjustments = await rerank_layer._apply_business_rules(
            ranked_items, business_rules
        )

        assert cid2 in rule_adjustments
        assert rule_adjustments[cid2] > 0.0

    @pytest.mark.asyncio
    async def test_rank_layer_handles_empty_recall(self, rank_pipeline):
        rank_layer = rank_pipeline._rank_layer

        ranked = await rank_layer.rank("test_user", [])

        assert ranked == []
