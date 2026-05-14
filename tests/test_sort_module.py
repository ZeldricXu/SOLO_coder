import sys
from pathlib import Path

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from datetime import datetime, timedelta
from searchengine.models.base import SearchIndex, SortStrategy
from searchengine.modules.sort_module import sort_module


class TestSortModule:
    def setup_method(self):
        self.indexes = [
            SearchIndex(
                index_id="idx_1",
                content_id="content_001",
                title="Python编程入门教程",
                content="Python是一种简单易学的编程语言，适合初学者学习",
                keywords=["Python", "编程", "入门"],
                category="技术",
                click_count=100,
                publish_time=datetime.utcnow() - timedelta(days=1)
            ),
            SearchIndex(
                index_id="idx_2",
                content_id="content_002",
                title="Java编程高级技巧",
                content="Java高级编程技巧，包括多线程、并发编程等内容",
                keywords=["Java", "编程", "高级"],
                category="技术",
                click_count=200,
                publish_time=datetime.utcnow() - timedelta(days=7)
            ),
            SearchIndex(
                index_id="idx_3",
                content_id="content_003",
                title="Python实战项目",
                content="Python实战项目开发，Web开发、数据分析等",
                keywords=["Python", "实战", "项目"],
                category="技术",
                click_count=50,
                publish_time=datetime.utcnow() - timedelta(hours=2)
            )
        ]
    
    def test_get_default_strategies(self):
        strategies = sort_module.list_strategies()
        assert len(strategies) >= 4
        
        strategy_types = [s.strategy_type for s in strategies]
        assert "relevance" in strategy_types
        assert "custom" in strategy_types
        assert "click" in strategy_types
        assert "time" in strategy_types
    
    def test_sort_by_relevance(self):
        keywords = ["Python", "编程"]
        results = sort_module.sort_results(self.indexes, "relevance", keywords)
        
        assert len(results) == 3
        
        python_results = [r for r in results if "Python" in r.title]
        assert len(python_results) >= 2
    
    def test_sort_by_click(self):
        keywords = ["编程"]
        results = sort_module.sort_results(self.indexes, "click", keywords)
        
        assert len(results) == 3
        click_counts = [r.click_count for r in results]
        assert click_counts == sorted(click_counts, reverse=True)
    
    def test_sort_by_time(self):
        keywords = ["编程"]
        results = sort_module.sort_results(self.indexes, "time", keywords)
        
        assert len(results) == 3
        
        publish_times = []
        for result in results:
            for idx in self.indexes:
                if idx.content_id == result.content_id:
                    publish_times.append(idx.publish_time)
                    break
        
        for i in range(len(publish_times) - 1):
            if publish_times[i] and publish_times[i + 1]:
                assert publish_times[i] >= publish_times[i + 1]
    
    def test_sort_empty_list(self):
        results = sort_module.sort_results([], "relevance", ["test"])
        assert len(results) == 0
    
    def test_invalid_sort_type(self):
        keywords = ["编程"]
        results = sort_module.sort_results(self.indexes, "invalid_type", keywords)
        assert len(results) == 3
    
    def test_add_custom_strategy(self):
        new_strategy = SortStrategy(
            strategy_id="strategy_custom_2",
            strategy_name="自定义排序2",
            strategy_type="custom_2",
            strategy_config={"sort_field": "click_count"},
            enabled=True
        )
        
        success = sort_module.add_strategy(new_strategy)
        assert success is True
        
        retrieved = sort_module.get_strategy("custom_2")
        assert retrieved is not None
        assert retrieved.strategy_name == "自定义排序2"
    
    def test_disabled_strategy_fallback(self):
        disabled_strategy = SortStrategy(
            strategy_id="strategy_disabled",
            strategy_name="禁用策略",
            strategy_type="disabled_test",
            strategy_config={},
            enabled=False
        )
        
        sort_module.add_strategy(disabled_strategy)
        
        keywords = ["编程"]
        results = sort_module.sort_results(self.indexes, "disabled_test", keywords)
        
        assert len(results) == 3
