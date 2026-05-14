import sys
import time
from pathlib import Path
from datetime import datetime, timedelta

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from tests.test_data_builder import test_data_builder
from searchengine.modules.sort_module import sort_module
from searchengine.models.base import SearchIndex, SortStrategy


class TestSortStrategyBehavior:
    def setup_method(self):
        test_data_builder.reset_counters()
    
    def test_relevance_vs_click_sort_difference(self):
        indexes = [
            SearchIndex(
                index_id="idx_1",
                content_id="content_1",
                title="Python编程完全指南 Python Python",
                content="Python是一种优秀的编程语言 Python Python",
                keywords=["Python", "编程", "Python", "Python"],
                category="技术",
                click_count=10,
                publish_time=datetime.utcnow() - timedelta(days=30)
            ),
            SearchIndex(
                index_id="idx_2",
                content_id="content_2",
                title="Java开发教程",
                content="Java企业级应用开发",
                keywords=["Java", "开发"],
                category="技术",
                click_count=1000,
                publish_time=datetime.utcnow() - timedelta(days=1)
            ),
            SearchIndex(
                index_id="idx_3",
                content_id="content_3",
                title="Python基础入门",
                content="Python基础语法学习",
                keywords=["Python", "入门"],
                category="技术",
                click_count=100,
                publish_time=datetime.utcnow() - timedelta(days=7)
            )
        ]
        
        keywords = ["Python", "编程"]
        
        relevance_results = sort_module.sort_results(indexes, "relevance", keywords)
        click_results = sort_module.sort_results(indexes, "click", keywords)
        
        relevance_ids = [r.content_id for r in relevance_results]
        click_ids = [r.content_id for r in click_results]
        
        assert relevance_ids != click_ids
        
        assert relevance_ids[0] == "content_1" or relevance_ids[0] == "content_3"
        assert click_ids[0] == "content_2"
    
    def test_time_sort_order(self):
        indexes = test_data_builder.create_time_gradient_indexes(10)
        
        keywords = ["时间"]
        
        desc_results = sort_module.sort_results(indexes, "time", keywords)
        
        assert len(desc_results) == 10
        
        publish_times = []
        for result in desc_results:
            for idx in indexes:
                if idx.content_id == result.content_id:
                    publish_times.append(idx.publish_time)
                    break
        
        for i in range(len(publish_times) - 1):
            if publish_times[i] and publish_times[i + 1]:
                assert publish_times[i] >= publish_times[i + 1]
    
    def test_click_sort_order(self):
        indexes = test_data_builder.create_click_gradient_indexes(10)
        
        keywords = ["热门"]
        
        results = sort_module.sort_results(indexes, "click", keywords)
        
        click_counts = [r.click_count for r in results]
        
        assert click_counts == sorted(click_counts, reverse=True)
    
    def test_custom_sort_strategy(self):
        custom_strategy = SortStrategy(
            strategy_id="strategy_custom_field",
            strategy_name="按标题长度排序",
            strategy_type="custom_field",
            strategy_config={
                "sort_field": "title",
                "sort_order": "asc"
            },
            enabled=True
        )
        sort_module.add_strategy(custom_strategy)
        
        indexes = [
            SearchIndex(
                index_id="idx_1",
                content_id="c1",
                title="Zebra",
                content="内容1",
                keywords=["kw"],
                click_count=10
            ),
            SearchIndex(
                index_id="idx_2",
                content_id="c2",
                title="Alpha",
                content="内容2",
                keywords=["kw"],
                click_count=20
            ),
            SearchIndex(
                index_id="idx_3",
                content_id="c3",
                title="Middle",
                content="内容3",
                keywords=["kw"],
                click_count=30
            )
        ]
        
        keywords = ["kw"]
        
        relevance_results = sort_module.sort_results(indexes, "relevance", keywords)
        custom_results = sort_module.sort_results(indexes, "custom_field", keywords)
        
        relevance_ids = [r.content_id for r in relevance_results]
        custom_ids = [r.content_id for r in custom_results]
        
        assert relevance_ids != custom_ids


class TestSortStrategyDynamicLoading:
    def setup_method(self):
        test_data_builder.reset_counters()
    
    def test_add_new_sort_strategy(self):
        new_strategy = SortStrategy(
            strategy_id="strategy_test_dynamic",
            strategy_name="动态测试策略",
            strategy_type="dynamic_test",
            strategy_config={
                "weight_title": 0.6,
                "weight_content": 0.2,
                "weight_click": 0.2
            },
            enabled=True
        )
        
        strategies_before = sort_module.list_strategies()
        sort_module.add_strategy(new_strategy)
        strategies_after = sort_module.list_strategies()
        
        assert len(strategies_after) >= len(strategies_before)
        
        retrieved = sort_module.get_strategy("dynamic_test")
        assert retrieved is not None
        assert retrieved.strategy_name == "动态测试策略"
    
    def test_disabled_strategy_fallback(self):
        disabled_strategy = SortStrategy(
            strategy_id="strategy_disabled_test",
            strategy_name="禁用策略",
            strategy_type="disabled_test",
            strategy_config={},
            enabled=False
        )
        
        sort_module.add_strategy(disabled_strategy)
        
        indexes = test_data_builder.create_python_article_indexes(3)
        keywords = ["Python", "编程"]
        
        results = sort_module.sort_results(indexes, "disabled_test", keywords)
        
        assert len(results) == 3
    
    def test_strategy_overwrite(self):
        strategy_v1 = SortStrategy(
            strategy_id="strategy_overwrite",
            strategy_name="版本1",
            strategy_type="overwrite_test",
            strategy_config={"weight": 0.5},
            enabled=True
        )
        sort_module.add_strategy(strategy_v1)
        
        strategy_v2 = SortStrategy(
            strategy_id="strategy_overwrite",
            strategy_name="版本2",
            strategy_type="overwrite_test",
            strategy_config={"weight": 0.9},
            enabled=True
        )
        sort_module.add_strategy(strategy_v2)
        
        retrieved = sort_module.get_strategy("overwrite_test")
        assert retrieved.strategy_name == "版本2"
        assert retrieved.strategy_config["weight"] == 0.9
    
    def test_list_all_strategies(self):
        strategies = sort_module.list_strategies()
        
        assert len(strategies) >= 4
        
        strategy_types = [s.strategy_type for s in strategies]
        assert "relevance" in strategy_types
        assert "click" in strategy_types
        assert "time" in strategy_types
        assert "custom" in strategy_types


class TestSortWeightCalculation:
    def setup_method(self):
        test_data_builder.reset_counters()
    
    def test_relevance_weight_impact(self):
        high_title_weight = SortStrategy(
            strategy_id="strategy_high_title",
            strategy_name="高标题权重",
            strategy_type="high_title",
            strategy_config={
                "weight_title": 0.8,
                "weight_content": 0.1,
                "weight_click": 0.05,
                "weight_time": 0.05
            },
            enabled=True
        )
        
        high_click_weight = SortStrategy(
            strategy_id="strategy_high_click",
            strategy_name="高点击权重",
            strategy_type="high_click",
            strategy_config={
                "weight_title": 0.1,
                "weight_content": 0.1,
                "weight_click": 0.7,
                "weight_time": 0.1
            },
            enabled=True
        )
        
        sort_module.add_strategy(high_title_weight)
        sort_module.add_strategy(high_click_weight)
        
        indexes = [
            SearchIndex(
                index_id="idx_title",
                content_id="content_title",
                title="Python编程 Python Python",
                content="普通内容",
                keywords=["Python", "编程"],
                category="技术",
                click_count=10,
                publish_time=datetime.utcnow()
            ),
            SearchIndex(
                index_id="idx_click",
                content_id="content_click",
                title="Java开发",
                content="Java内容",
                keywords=["Java", "开发"],
                category="技术",
                click_count=1000,
                publish_time=datetime.utcnow()
            )
        ]
        
        keywords = ["Python", "编程", "Java", "开发"]
        
        results1 = sort_module.sort_results(indexes, "high_title", keywords)
        results2 = sort_module.sort_results(indexes, "high_click", keywords)
        
        ids1 = [r.content_id for r in results1]
        ids2 = [r.content_id for r in results2]
        
        assert ids1 != ids2
    
    def test_time_weight_in_relevance(self):
        recent_high_click = SearchIndex(
            index_id="idx_recent",
            content_id="content_recent",
            title="Python教程",
            content="Python内容",
            keywords=["Python", "教程"],
            click_count=100,
            publish_time=datetime.utcnow()
        )
        
        old_high_click = SearchIndex(
            index_id="idx_old",
            content_id="content_old",
            title="Python教程2",
            content="Python内容2",
            keywords=["Python", "教程"],
            click_count=200,
            publish_time=datetime.utcnow() - timedelta(days=365)
        )
        
        indexes = [recent_high_click, old_high_click]
        keywords = ["Python", "教程"]
        
        results = sort_module.sort_results(indexes, "relevance", keywords)
        
        assert len(results) == 2
    
    def test_weight_sum_validation(self):
        strategy = sort_module.get_strategy("relevance")
        config = strategy.strategy_config
        
        total_weight = (
            config.get("weight_title", 0) +
            config.get("weight_content", 0) +
            config.get("weight_click", 0) +
            config.get("weight_time", 0)
        )
        
        assert total_weight > 0
    
    def test_empty_indexes_sort(self):
        keywords = ["Python"]
        
        results = sort_module.sort_results([], "relevance", keywords)
        
        assert len(results) == 0
        assert results == []
    
    def test_single_index_sort(self):
        index = SearchIndex(
            index_id="idx_single",
            content_id="content_single",
            title="单篇文章",
            content="单篇内容",
            keywords=["单篇"],
            click_count=50
        )
        
        keywords = ["单篇"]
        
        results = sort_module.sort_results([index], "relevance", keywords)
        
        assert len(results) == 1
        assert results[0].content_id == "content_single"
        assert results[0].position == 1
        assert 0 <= results[0].relevance <= 1


class TestSortStrategyEdgeCases:
    def setup_method(self):
        test_data_builder.reset_counters()
    
    def test_null_field_handling(self):
        indexes = [
            SearchIndex(
                index_id="idx_1",
                content_id="c1",
                title="有时间",
                content="内容1",
                keywords=["kw"],
                click_count=100,
                publish_time=datetime.utcnow()
            ),
            SearchIndex(
                index_id="idx_2",
                content_id="c2",
                title="无时间",
                content="内容2",
                keywords=["kw"],
                click_count=50,
                publish_time=None
            )
        ]
        
        keywords = ["kw"]
        
        results = sort_module.sort_results(indexes, "time", keywords)
        
        assert len(results) == 2
    
    def test_identical_scores_handling(self):
        indexes = [
            SearchIndex(
                index_id="idx_1",
                content_id="c1",
                title="相同文章1",
                content="相同内容",
                keywords=["相同"],
                click_count=100
            ),
            SearchIndex(
                index_id="idx_2",
                content_id="c2",
                title="相同文章2",
                content="相同内容",
                keywords=["相同"],
                click_count=100
            )
        ]
        
        keywords = ["相同"]
        
        results = sort_module.sort_results(indexes, "relevance", keywords)
        
        assert len(results) == 2
        assert results[0].position == 1
        assert results[1].position == 2
    
    def test_extreme_click_counts(self):
        indexes = [
            SearchIndex(
                index_id="idx_low",
                content_id="c_low",
                title="低点击",
                content="内容",
                keywords=["kw"],
                click_count=0
            ),
            SearchIndex(
                index_id="idx_high",
                content_id="c_high",
                title="高点击",
                content="内容",
                keywords=["kw"],
                click_count=1000000
            )
        ]
        
        keywords = ["kw"]
        
        results = sort_module.sort_results(indexes, "click", keywords)
        
        assert len(results) == 2
        assert results[0].content_id == "c_high"
        assert results[0].relevance >= results[1].relevance
    
    def test_all_disabled_keywords(self):
        indexes = test_data_builder.create_python_article_indexes(5)
        
        keywords = ["完全不相关的关键词xyz123"]
        
        results = sort_module.sort_results(indexes, "relevance", keywords)
        
        assert len(results) == 5
    
    def test_relevance_range(self):
        indexes = test_data_builder.create_python_article_indexes(10)
        keywords = ["Python", "编程", "开发"]
        
        results = sort_module.sort_results(indexes, "relevance", keywords)
        
        for result in results:
            assert 0 <= result.relevance <= 1
            assert 1 <= result.position <= len(results)
    
    def test_position_consistency(self):
        indexes = test_data_builder.create_click_gradient_indexes(10)
        keywords = ["热门"]
        
        results = sort_module.sort_results(indexes, "click", keywords)
        
        positions = [r.position for r in results]
        assert positions == list(range(1, 11))
