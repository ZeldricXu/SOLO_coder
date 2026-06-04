import pytest
import json
from typing import List, Dict, Any, Optional
from unittest.mock import patch, MagicMock, AsyncMock
from datetime import datetime, timezone, timedelta

from recommendation_engine.realtime_rank_pipeline.business_rule_injector import (
    BusinessRuleInjector,
)
from recommendation_engine.models.schemas import (
    BusinessRule,
    BusinessRuleSet,
    BusinessRuleFilter,
    RerankResultItem,
    ExclusionPolicy,
)
from recommendation_engine.infrastructure.redis_client import RedisClient
from recommendation_engine.content_embedding_index.content_embedding_index import ContentEmbeddingIndex
from tests.factories.data_factories import generate_content_id
from config import settings

pytestmark = pytest.mark.unit


@pytest.fixture
def mock_redis_client():
    redis = MagicMock(spec=RedisClient)
    redis.get_json = AsyncMock(return_value=None)
    return redis


@pytest.fixture
def mock_content_index():
    index = MagicMock(spec=ContentEmbeddingIndex)
    index.get_content_info = AsyncMock(return_value=None)
    return index


@pytest.fixture
async def business_rule_injector(mock_redis_client, mock_content_index):
    with patch('config.settings.hot_reload_enabled', False):
        injector = BusinessRuleInjector(
            redis_client=mock_redis_client,
            content_index=mock_content_index,
            scene="home",
        )
        await injector.initialize()
        yield injector
        if injector._hot_reload_task and not injector._hot_reload_task.done():
            injector._hot_reload_task.cancel()
            try:
                await injector._hot_reload_task
            except asyncio.CancelledError:
                pass


def _create_rerank_item(content_id: Optional[str] = None, score: float = 0.5) -> RerankResultItem:
    return RerankResultItem(
        content_id=content_id or generate_content_id(),
        final_score=score,
        diversity_penalty=0.0,
        rule_adjustment=0.0,
        rank=None,
    )


def _create_content_info(
    categories: Optional[List[str]] = None,
    tags: Optional[List[str]] = None,
    content_type: str = "article",
    popularity_score: float = 50.0,
    publish_time: Optional[datetime] = None,
) -> Dict[str, Any]:
    return {
        "categories": categories or [],
        "tags": tags or [],
        "content_type": content_type,
        "popularity_score": popularity_score,
        "publish_time": (publish_time or datetime.now(timezone.utc)).isoformat(),
    }


def _create_rule(
    rule_type: str,
    rule_id: str = "test_rule",
    priority: int = 5,
    filter_criteria: Optional[BusinessRuleFilter] = None,
    params: Optional[Dict[str, Any]] = None,
    enabled: bool = True,
) -> BusinessRule:
    return BusinessRule(
        rule_id=rule_id,
        name=f"Test {rule_type}",
        type=rule_type,
        priority=priority,
        filter=filter_criteria or BusinessRuleFilter(),
        params=params or {},
        enabled=enabled,
    )


class TestBusinessRuleInjectorNormalPath:

    @pytest.mark.asyncio
    async def test_boost_rule_increases_matching_item_scores(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        electronics_id = generate_content_id()
        other_id = generate_content_id()

        electronics_info = _create_content_info(categories=["electronics"])
        other_info = _create_content_info(categories=["fashion"])

        mock_content_index.get_content_info.side_effect = lambda cid: {
            electronics_id: electronics_info,
            other_id: other_info,
        }.get(cid)

        items = [
            _create_rerank_item(electronics_id, score=0.6),
            _create_rerank_item(other_id, score=0.7),
        ]

        boost_rule = _create_rule(
            "boost",
            rule_id="boost_electronics",
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"factor": 0.3},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[boost_rule])

        result = await business_rule_injector.apply_rules(items)

        assert len(result) == 2
        electronics_item = next(i for i in result if i.content_id == electronics_id)
        other_item = next(i for i in result if i.content_id == other_id)

        assert electronics_item.final_score == pytest.approx(0.6 + 0.3)
        assert electronics_item.rule_adjustment == pytest.approx(0.3)
        assert other_item.final_score == pytest.approx(0.7)
        assert other_item.rule_adjustment == pytest.approx(0.0)

    @pytest.mark.asyncio
    async def test_penalize_rule_decreases_matching_item_scores(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        bad_id = generate_content_id()
        good_id = generate_content_id()

        bad_info = _create_content_info(tags=["spam"])
        good_info = _create_content_info(tags=["trusted"])

        mock_content_index.get_content_info.side_effect = lambda cid: {
            bad_id: bad_info,
            good_id: good_info,
        }.get(cid)

        items = [
            _create_rerank_item(bad_id, score=0.8),
            _create_rerank_item(good_id, score=0.7),
        ]

        penalize_rule = _create_rule(
            "penalize",
            rule_id="penalize_spam",
            filter_criteria=BusinessRuleFilter(tags=["spam"]),
            params={"factor": 0.4},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[penalize_rule])

        result = await business_rule_injector.apply_rules(items)

        bad_item = next(i for i in result if i.content_id == bad_id)
        good_item = next(i for i in result if i.content_id == good_id)

        assert bad_item.final_score == pytest.approx(0.8 - 0.4)
        assert bad_item.rule_adjustment == pytest.approx(-0.4)
        assert good_item.final_score == pytest.approx(0.7)

    @pytest.mark.asyncio
    async def test_pin_rule_moves_items_to_specific_positions(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        pinned_id = generate_content_id()
        normal_ids = [generate_content_id() for _ in range(5)]

        all_ids = [pinned_id] + normal_ids
        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info()

        items = [
            _create_rerank_item(cid, score=1.0 - (idx * 0.1))
            for idx, cid in enumerate(normal_ids + [pinned_id])
        ]

        pin_rule = _create_rule(
            "pin",
            rule_id="pin_item",
            filter_criteria=BusinessRuleFilter(content_ids=[pinned_id]),
            params={"position": 1},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[pin_rule])

        result = await business_rule_injector.apply_rules(items)

        assert result[0].content_id == pinned_id
        assert result[0].rank == 1

    @pytest.mark.asyncio
    async def test_exclude_rule_removes_matching_items(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        exclude_id = generate_content_id()
        keep_id = generate_content_id()

        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info()

        items = [
            _create_rerank_item(exclude_id, score=0.9),
            _create_rerank_item(keep_id, score=0.8),
        ]

        exclude_rule = _create_rule(
            "exclude",
            rule_id="exclude_item",
            filter_criteria=BusinessRuleFilter(content_ids=[exclude_id]),
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[exclude_rule])

        result = await business_rule_injector.apply_rules(items)

        assert len(result) == 1
        assert result[0].content_id == keep_id

    @pytest.mark.asyncio
    async def test_category_ratio_min_enforces_minimum_exposure(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        top_electronics_ids = [generate_content_id() for _ in range(4)]
        top_fashion_ids = [generate_content_id() for _ in range(16)]
        reserve_electronics_ids = [generate_content_id() for _ in range(10)]

        mock_content_index.get_content_info.side_effect = lambda cid: (
            _create_content_info(categories=["electronics"])
            if cid in top_electronics_ids or cid in reserve_electronics_ids
            else _create_content_info(categories=["fashion"])
        )

        top_items = [
            _create_rerank_item(cid, score=1.0 - (i * 0.01))
            for i, cid in enumerate(top_fashion_ids + top_electronics_ids)
        ]
        reserve_items = [
            _create_rerank_item(cid, score=0.3 - (i * 0.01))
            for i, cid in enumerate(reserve_electronics_ids)
        ]
        items = top_items + reserve_items

        ratio_rule = _create_rule(
            "category_ratio",
            rule_id="ratio_electronics",
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"category": "electronics", "min_ratio": 0.4, "max_ratio": 0.6},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[ratio_rule])

        result = await business_rule_injector.apply_rules(items)

        electronics_count = sum(
            1 for i in result
            if i.content_id in top_electronics_ids or i.content_id in reserve_electronics_ids
        )
        expected_min = int(0.4 * 20)
        assert electronics_count >= expected_min

    @pytest.mark.asyncio
    async def test_category_ratio_max_enforces_maximum_exposure(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        electronics_ids = [generate_content_id() for _ in range(10)]
        fashion_ids = [generate_content_id() for _ in range(10)]

        mock_content_index.get_content_info.side_effect = lambda cid: (
            _create_content_info(categories=["electronics"])
            if cid in electronics_ids
            else _create_content_info(categories=["fashion"])
        )

        items = [
            _create_rerank_item(cid, score=1.0 - (i * 0.01))
            for i, cid in enumerate(electronics_ids + fashion_ids)
        ]

        ratio_rule = _create_rule(
            "category_ratio",
            rule_id="ratio_electronics",
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"category": "electronics", "min_ratio": 0.0, "max_ratio": 0.3},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[ratio_rule])

        result = await business_rule_injector.apply_rules(items)

        electronics_in_result = [i for i in result if i.content_id in electronics_ids]
        expected_max = int(0.3 * 20)
        assert len(electronics_in_result) <= expected_max

    @pytest.mark.asyncio
    async def test_cold_start_boost_boosts_recent_items(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        recent_id = generate_content_id()
        old_id = generate_content_id()

        recent_time = datetime.now(timezone.utc) - timedelta(hours=1)
        old_time = datetime.now(timezone.utc) - timedelta(hours=48)

        mock_content_index.get_content_info.side_effect = lambda cid: {
            recent_id: _create_content_info(categories=["new"], publish_time=recent_time),
            old_id: _create_content_info(categories=["new"], publish_time=old_time),
        }.get(cid)

        items = [
            _create_rerank_item(recent_id, score=0.3),
            _create_rerank_item(old_id, score=0.5),
        ]

        cs_rule = _create_rule(
            "cold_start_boost",
            rule_id="cold_start",
            filter_criteria=BusinessRuleFilter(max_publish_age_hours=24),
            params={"boost_factor": 0.5},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[cs_rule])

        result = await business_rule_injector.apply_rules(items)

        recent_item = next(i for i in result if i.content_id == recent_id)
        old_item = next(i for i in result if i.content_id == old_id)

        assert recent_item.final_score == pytest.approx(0.3 + 0.5)
        assert recent_item.rule_adjustment == pytest.approx(0.5)
        assert old_item.final_score == pytest.approx(0.5)
        assert old_item.rule_adjustment == pytest.approx(0.0)

    @pytest.mark.asyncio
    async def test_priority_resolution_high_priority_rule_overrides_low(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        electronics_id = generate_content_id()
        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info(
            categories=["electronics"]
        )

        items = [_create_rerank_item(electronics_id, score=0.5)]

        high_boost = _create_rule(
            "boost",
            rule_id="high_boost",
            priority=10,
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"factor": 0.5},
        )
        low_penalize = _create_rule(
            "penalize",
            rule_id="low_penalize",
            priority=1,
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"factor": 0.3},
        )
        business_rule_injector._rule_set = BusinessRuleSet(
            scene="home", rules=[low_penalize, high_boost]
        )

        result = await business_rule_injector.apply_rules(items)

        item = result[0]
        assert item.final_score == pytest.approx(0.5 + 0.5 - 0.3)
        assert item.final_score > 0.5

    @pytest.mark.asyncio
    async def test_rules_apply_after_mmr_order_preserved(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        pinned_id = generate_content_id()
        high_score_id = generate_content_id()
        med_score_id = generate_content_id()
        low_score_id = generate_content_id()

        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info()

        items = [
            _create_rerank_item(high_score_id, score=0.9),
            _create_rerank_item(med_score_id, score=0.8),
            _create_rerank_item(low_score_id, score=0.7),
            _create_rerank_item(pinned_id, score=0.5),
        ]

        pin_rule = _create_rule(
            "pin",
            rule_id="pin_1",
            filter_criteria=BusinessRuleFilter(content_ids=[pinned_id]),
            params={"position": 1},
        )
        boost_rule = _create_rule(
            "boost",
            rule_id="boost_1",
            priority=3,
            filter_criteria=BusinessRuleFilter(content_ids=[low_score_id]),
            params={"factor": 0.25},
        )
        business_rule_injector._rule_set = BusinessRuleSet(
            scene="home", rules=[pin_rule, boost_rule]
        )

        result = await business_rule_injector.apply_rules(items)

        assert result[0].content_id == pinned_id
        assert result[0].rank == 1

        scores = [item.final_score for item in result[1:]]
        assert scores == sorted(scores, reverse=True)

        for idx, item in enumerate(result, 1):
            assert item.rank == idx


class TestBusinessRuleInjectorExceptionPath:

    @pytest.mark.asyncio
    async def test_empty_items_returns_empty_list(
        self, business_rule_injector
    ):
        result = await business_rule_injector.apply_rules([])
        assert result == []

    @pytest.mark.asyncio
    async def test_invalid_rule_params_gracefully_handled(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        item_id = generate_content_id()
        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info()
        items = [_create_rerank_item(item_id, score=0.5)]

        bad_boost_rule = _create_rule(
            "boost",
            rule_id="bad_boost",
            filter_criteria=BusinessRuleFilter(content_ids=[item_id]),
            params={},
        )
        business_rule_injector._rule_set = BusinessRuleSet(
            scene="home", rules=[bad_boost_rule]
        )

        result = await business_rule_injector.apply_rules(items)

        assert len(result) == 1
        assert result[0].final_score == pytest.approx(0.5)

    @pytest.mark.asyncio
    async def test_redis_unavailable_uses_empty_ruleset(
        self, mock_redis_client, mock_content_index
    ):
        mock_redis_client.get_json = AsyncMock(side_effect=Exception("Redis down"))

        with patch('config.settings.hot_reload_enabled', False):
            injector = BusinessRuleInjector(
                redis_client=mock_redis_client,
                content_index=mock_content_index,
                scene="home",
            )
            await injector.initialize()

            assert len(injector._rule_set.rules) == 0

            item_id = generate_content_id()
            mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info()
            items = [_create_rerank_item(item_id, score=0.5)]

            result = await injector.apply_rules(items)
            assert len(result) == 1

    @pytest.mark.asyncio
    async def test_content_info_missing_items_still_processed(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        id1 = generate_content_id()
        id2 = generate_content_id()
        id3 = generate_content_id()

        mock_content_index.get_content_info.side_effect = lambda cid: {
            id1: _create_content_info(categories=["electronics"]),
            id2: None,
        }.get(cid)

        items = [
            _create_rerank_item(id1, score=0.8),
            _create_rerank_item(id2, score=0.7),
            _create_rerank_item(id3, score=0.6),
        ]

        boost_rule = _create_rule(
            "boost",
            rule_id="boost_electronics",
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"factor": 0.2},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[boost_rule])

        result = await business_rule_injector.apply_rules(items)

        assert len(result) == 3
        item1 = next(i for i in result if i.content_id == id1)
        item2 = next(i for i in result if i.content_id == id2)
        item3 = next(i for i in result if i.content_id == id3)

        assert item1.final_score == pytest.approx(0.8 + 0.2)
        assert item2.final_score == pytest.approx(0.7)
        assert item3.final_score == pytest.approx(0.6)

    @pytest.mark.asyncio
    async def test_invalid_publish_time_format_skipped(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        good_id = generate_content_id()
        bad_id = generate_content_id()

        bad_info = _create_content_info(categories=["new"])
        bad_info["publish_time"] = "NOT_A_VALID_DATE"
        mock_content_index.get_content_info.side_effect = lambda cid: {
            good_id: _create_content_info(categories=["new"]),
            bad_id: bad_info,
        }.get(cid)

        items = [
            _create_rerank_item(good_id, score=0.4),
            _create_rerank_item(bad_id, score=0.5),
        ]

        cs_rule = _create_rule(
            "cold_start_boost",
            rule_id="cold_start",
            filter_criteria=BusinessRuleFilter(max_publish_age_hours=24),
            params={"boost_factor": 0.5},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[cs_rule])

        result = await business_rule_injector.apply_rules(items)

        assert len(result) == 2

        bad_item = next(i for i in result if i.content_id == bad_id)
        assert bad_item.final_score == pytest.approx(0.5)


class TestBusinessRuleInjectorEdgeCases:

    @pytest.mark.asyncio
    async def test_max_pin_positions_respected(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        max_pin = settings.business_rules_max_pin_positions
        pin_ids = [generate_content_id() for _ in range(10)]

        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info()

        items = [
            _create_rerank_item(cid, score=0.9 - (i * 0.05))
            for i, cid in enumerate(pin_ids)
        ]

        rules = []
        for i, pid in enumerate(pin_ids, 1):
            rule = _create_rule(
                "pin",
                rule_id=f"pin_{i}",
                priority=10,
                filter_criteria=BusinessRuleFilter(content_ids=[pid]),
                params={"position": i},
            )
            rules.append(rule)

        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=rules)
        result = await business_rule_injector.apply_rules(items)

        pinned_in_result = [item for item in result if item.content_id in pin_ids[:max_pin]]
        for i, item in enumerate(pinned_in_result[:max_pin], 1):
            if i <= len(result):
                assert result[i - 1].content_id == pin_ids[i - 1]

    @pytest.mark.asyncio
    async def test_category_ratio_with_empty_reserve_pool(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        electronics_ids = [generate_content_id() for _ in range(2)]
        fashion_ids = [generate_content_id() for _ in range(8)]

        mock_content_index.get_content_info.side_effect = lambda cid: (
            _create_content_info(categories=["electronics"])
            if cid in electronics_ids
            else _create_content_info(categories=["fashion"])
        )

        items = [
            _create_rerank_item(cid, score=1.0 - (i * 0.05))
            for i, cid in enumerate(electronics_ids + fashion_ids)
        ]

        ratio_rule = _create_rule(
            "category_ratio",
            rule_id="ratio_electronics",
            params={"category": "electronics", "min_ratio": 0.5, "max_ratio": 0.8},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[ratio_rule])

        result = await business_rule_injector.apply_rules(items)

        assert len(result) == 10

    @pytest.mark.asyncio
    async def test_multiple_rules_same_item_multiple_adjustments(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        item_id = generate_content_id()
        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info(
            categories=["electronics"], tags=["trending"]
        )

        items = [_create_rerank_item(item_id, score=0.5)]

        boost1 = _create_rule(
            "boost",
            rule_id="boost_electronics",
            priority=8,
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"factor": 0.1},
        )
        boost2 = _create_rule(
            "boost",
            rule_id="boost_trending",
            priority=6,
            filter_criteria=BusinessRuleFilter(tags=["trending"]),
            params={"factor": 0.2},
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[boost1, boost2])

        result = await business_rule_injector.apply_rules(items)

        assert result[0].final_score == pytest.approx(0.5 + 0.1 + 0.2)
        assert result[0].rule_adjustment == pytest.approx(0.1 + 0.2)

    @pytest.mark.asyncio
    async def test_hot_reload_updates_rules(
        self, mock_redis_client, mock_content_index
    ):
        item_id = generate_content_id()
        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info(
            categories=["electronics"]
        )

        mock_redis_client.get_json = AsyncMock(return_value=None)

        with patch('config.settings.hot_reload_enabled', False):
            injector = BusinessRuleInjector(
                redis_client=mock_redis_client,
                content_index=mock_content_index,
                scene="home",
            )
            await injector.initialize()

            items = [_create_rerank_item(item_id, score=0.5)]
            result_before = await injector.apply_rules(items)
            assert result_before[0].final_score == pytest.approx(0.5)

            new_rule_set = BusinessRuleSet(
                scene="home",
                version="v2",
                rules=[
                    _create_rule(
                        "boost",
                        rule_id="boost_electronics",
                        filter_criteria=BusinessRuleFilter(categories=["electronics"]),
                        params={"factor": 0.3},
                    )
                ],
            )
            mock_redis_client.get_json = AsyncMock(
                return_value=new_rule_set.model_dump(mode="json")
            )

            await injector.load_rules()

            result_after = await injector.apply_rules(items)
            assert result_after[0].final_score == pytest.approx(0.5 + 0.3)

    @pytest.mark.asyncio
    async def test_disabled_rules_are_skipped(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        item_id = generate_content_id()
        mock_content_index.get_content_info.side_effect = lambda cid: _create_content_info(
            categories=["electronics"]
        )

        items = [_create_rerank_item(item_id, score=0.5)]

        disabled_rule = _create_rule(
            "boost",
            rule_id="disabled_boost",
            filter_criteria=BusinessRuleFilter(categories=["electronics"]),
            params={"factor": 0.3},
            enabled=False,
        )
        business_rule_injector._rule_set = BusinessRuleSet(scene="home", rules=[disabled_rule])

        result = await business_rule_injector.apply_rules(items)

        assert result[0].final_score == pytest.approx(0.5)
        assert result[0].rule_adjustment == pytest.approx(0.0)


class TestBusinessRuleModels:

    def test_exclusion_policy_whitelist_blocks_missing_tags(self):
        policy = ExclusionPolicy(user_tags_whitelist=["employee"])
        assert policy.is_excluded(user_id="user1", user_tags=["customer"]) is True
        assert policy.is_excluded(user_id="user1", user_tags=["employee"]) is False

    def test_exclusion_policy_blacklist_blocks_matching_tags(self):
        policy = ExclusionPolicy(user_tags_blacklist=["employee"])
        assert policy.is_excluded(user_id="user1", user_tags=["employee"]) is True
        assert policy.is_excluded(user_id="user1", user_tags=["customer"]) is False

    def test_exclusion_policy_user_id_pattern_matches(self):
        policy = ExclusionPolicy(user_id_pattern="^internal_.*")
        assert policy.is_excluded(user_id="internal_123", user_tags=[]) is True
        assert policy.is_excluded(user_id="external_456", user_tags=[]) is False

    def test_business_rule_priority_sorting(self):
        rule_low = _create_rule("boost", rule_id="low", priority=1)
        rule_med = _create_rule("boost", rule_id="med", priority=5)
        rule_high = _create_rule("boost", rule_id="high", priority=10)
        rule_disabled = _create_rule("boost", rule_id="disabled", priority=10, enabled=False)

        rule_set = BusinessRuleSet(
            scene="home",
            rules=[rule_low, rule_disabled, rule_high, rule_med],
        )

        sorted_rules = rule_set.sorted_rules()

        assert len(sorted_rules) == 3
        assert sorted_rules[0].rule_id == "high"
        assert sorted_rules[1].rule_id == "med"
        assert sorted_rules[2].rule_id == "low"
        assert all(r.rule_id != "disabled" for r in sorted_rules)

    def test_business_rule_filter_matches_correctly(
        self, mock_redis_client, mock_content_index, business_rule_injector
    ):
        item_id = generate_content_id()
        item = _create_rerank_item(item_id, score=0.5)

        info_electronics_trending = _create_content_info(
            categories=["electronics"], tags=["trending"], popularity_score=80.0
        )
        info_books_old = _create_content_info(
            categories=["books"], tags=["classic"], popularity_score=30.0
        )

        filter_cat = BusinessRuleFilter(categories=["electronics"])
        rule_cat = _create_rule("boost", filter_criteria=filter_cat)
        assert business_rule_injector._matches_rule(item, info_electronics_trending, rule_cat) is True
        assert business_rule_injector._matches_rule(item, info_books_old, rule_cat) is False

        filter_pop = BusinessRuleFilter(min_popularity=50.0)
        rule_pop = _create_rule("boost", filter_criteria=filter_pop)
        assert business_rule_injector._matches_rule(item, info_electronics_trending, rule_pop) is True
        assert business_rule_injector._matches_rule(item, info_books_old, rule_pop) is False

        filter_tag = BusinessRuleFilter(tags=["trending"])
        rule_tag = _create_rule("boost", filter_criteria=filter_tag)
        assert business_rule_injector._matches_rule(item, info_electronics_trending, rule_tag) is True
        assert business_rule_injector._matches_rule(item, info_books_old, rule_tag) is False

        filter_cid = BusinessRuleFilter(content_ids=[item_id])
        rule_cid = _create_rule("boost", filter_criteria=filter_cid)
        assert business_rule_injector._matches_rule(item, info_electronics_trending, rule_cid) is True

        other_item = _create_rerank_item(generate_content_id(), score=0.5)
        assert business_rule_injector._matches_rule(other_item, info_electronics_trending, rule_cid) is False

        filter_multi = BusinessRuleFilter(categories=["electronics"], tags=["trending"])
        rule_multi = _create_rule("boost", filter_criteria=filter_multi)
        assert business_rule_injector._matches_rule(item, info_electronics_trending, rule_multi) is True

        info_mix = _create_content_info(categories=["electronics"], tags=["classic"])
        assert business_rule_injector._matches_rule(item, info_mix, rule_multi) is False
