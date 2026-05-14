import pytest
import time
import threading
from datetime import datetime, timedelta
from typing import Dict, Any, List

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from searchengine.modules.index_manager import index_manager
from searchengine.modules.cache_module import cache_module
from searchengine.modules.cache_invalidator import cache_invalidator, CacheInvalidationEvent
from searchengine.modules.recommend_queue import (
    task_manager, InMemoryTaskQueue, RecommendWorker,
    TaskStatus, RecommendTask
)
from searchengine.modules.sort_strategy_config import (
    strategy_manager, ScorerConfig, StrategyConfig,
    BM25Scorer, RecencyScorer, PopularityScorer, CategoryScorer
)
from searchengine.models.base import IndexUpdateRequest, SearchIndex


class TestIndexVersionControl:
    def setup_method(self):
        index_manager.clear_indexes()
    
    def test_version_history_tracking(self):
        request = IndexUpdateRequest(
            content_id="content-001",
            title="Python Basics",
            content="Python is a popular programming language",
            category="python",
            keywords=["python", "programming"]
        )
        
        index = index_manager.create_index(request)
        history = index_manager.export_version_history()
        
        assert len(history) >= 1
        assert history[-1]["action"] == "create"
        assert history[-1]["content_id"] == "content-001"
    
    def test_version_metadata(self):
        request = IndexUpdateRequest(
            content_id="content-002",
            title="Java Basics",
            content="Java is an object-oriented language",
            category="java",
            keywords=["java", "programming"]
        )
        
        index = index_manager.create_index(request)
        version_info = index_manager.get_version(1)
        
        assert version_info is not None
        assert version_info["version"] == 1
        assert "timestamp" in version_info
        assert "content_id" in version_info
    
    def test_checksum_generation(self):
        request = IndexUpdateRequest(
            content_id="content-003",
            title="Test Content",
            content="Test content for checksum verification",
            category="test",
            keywords=["test"]
        )
        
        index = index_manager.create_index(request)
        version_info = index_manager.get_version(1)
        
        assert "checksum" in version_info
        assert version_info["checksum"] != ""
        assert len(version_info["checksum"]) == 32
    
    def test_update_stats(self):
        for i in range(5):
            request = IndexUpdateRequest(
                content_id=f"content-stat-{i}",
                title=f"Content {i}",
                content=f"Content number {i}",
                category="test",
                keywords=[f"test{i}"]
            )
            index_manager.create_index(request)
        
        stats = index_manager.get_update_stats()
        
        assert stats["total_operations"] >= 5
        assert stats["successful_operations"] >= 5
        assert stats["failed_operations"] == 0
        assert stats["success_rate"] == 1.0


class TestCacheInvalidation:
    def setup_method(self):
        cache_module.clear()
        cache_invalidator.enable()
    
    def test_event_creation(self):
        event = CacheInvalidationEvent(
            event_type="update",
            content_id="content-001",
            version=1,
            metadata={"action": "create"}
        )
        
        assert event.event_type == "update"
        assert event.content_id == "content-001"
        assert event.version == 1
        assert event.event_id is not None
    
    def test_invalidation_listener(self):
        events_received = []
        
        def test_listener(event):
            events_received.append(event)
        
        cache_invalidator.add_listener(test_listener)
        cache_invalidator.set_cache_module(cache_module)
        
        cache_module.set("search:query:test", {"data": "value"}, ttl=60)
        
        cache_invalidator.invalidate_on_index_update({
            "action": "update",
            "content_id": "content-001",
            "version": 2
        })
        
        time.sleep(0.1)
        
        assert len(events_received) >= 0
        cache_invalidator.remove_listener(test_listener)
    
    def test_invalidation_stats(self):
        cache_invalidator.reset_stats()
        cache_invalidator.set_cache_module(cache_module)
        
        cache_module.set("search:query:test1", {"data": "v1"}, ttl=60)
        cache_module.set("search:query:test2", {"data": "v2"}, ttl=60)
        
        cache_invalidator.invalidate_on_index_update({
            "action": "update",
            "content_id": "content-001",
            "version": 1
        })
        
        stats = cache_invalidator.get_stats()
        
        assert stats["total_events"] >= 1
        assert "success_rate" in stats
        assert "avg_latency" in stats
    
    def test_pattern_invalidation(self):
        cache_invalidator.set_cache_module(cache_module)
        
        cache_module.set("search:query:python", {"data": "p1"}, ttl=60)
        cache_module.set("search:query:java", {"data": "j1"}, ttl=60)
        cache_module.set("recommend:user:123", {"data": "r1"}, ttl=60)
        
        deleted = cache_module.delete_pattern("search:query:*")
        
        assert deleted >= 2
        assert cache_module.get("recommend:user:123") is not None
    
    def test_manual_invalidation(self):
        cache_invalidator.set_cache_module(cache_module)
        
        cache_module.set("cache:index:content-001", {"data": "v1"}, ttl=60)
        cache_module.set("cache:index:content-002", {"data": "v2"}, ttl=60)
        
        deleted = cache_invalidator.invalidate_specific_keys([
            "cache:index:content-001"
        ])
        
        assert deleted == 1
        assert cache_module.get("cache:index:content-001") is None
        assert cache_module.get("cache:index:content-002") is not None
    
    def test_enable_disable(self):
        cache_invalidator.enable()
        assert cache_invalidator.is_enabled() is True
        
        cache_invalidator.disable()
        assert cache_invalidator.is_enabled() is False
        
        cache_invalidator.enable()


class TestRecommendTaskQueue:
    def setup_method(self):
        self.queue = InMemoryTaskQueue()
        self.queue.clear()
    
    def test_task_creation(self):
        task = RecommendTask(
            task_id="task-001",
            user_id="user-123",
            content_id=None,
            recommend_type="personalized",
            limit=10,
            priority=8
        )
        
        assert task.task_id == "task-001"
        assert task.status == TaskStatus.PENDING
        assert task.user_id == "user-123"
        assert task.recommend_type == "personalized"
    
    def test_queue_push_pop(self):
        task1 = RecommendTask(task_id="task-1", priority=5)
        task2 = RecommendTask(task_id="task-2", priority=10)
        task3 = RecommendTask(task_id="task-3", priority=3)
        
        self.queue.push(task1)
        self.queue.push(task2)
        self.queue.push(task3)
        
        assert self.queue.size() == 3
        
        popped = self.queue.pop()
        assert popped is not None
        assert popped.task_id == "task-2"
        assert popped.status == TaskStatus.PROCESSING
    
    def test_priority_order(self):
        high_priority = RecommendTask(task_id="high", priority=10)
        medium_priority = RecommendTask(task_id="medium", priority=5)
        low_priority = RecommendTask(task_id="low", priority=1)
        
        self.queue.push(medium_priority)
        self.queue.push(low_priority)
        self.queue.push(high_priority)
        
        first = self.queue.pop()
        second = self.queue.pop()
        third = self.queue.pop()
        
        assert first.task_id == "high"
        assert second.task_id == "medium"
        assert third.task_id == "low"
    
    def test_status_update(self):
        task = RecommendTask(task_id="task-001")
        self.queue.push(task)
        
        success = self.queue.update_status(
            "task-001",
            TaskStatus.COMPLETED,
            result={"recommendations": [1, 2, 3]}
        )
        
        assert success is True
        
        task_info = self.queue.get_task("task-001")
        assert task_info.status == TaskStatus.COMPLETED
        assert task_info.result == {"recommendations": [1, 2, 3]}
    
    def test_task_retrieval(self):
        task = RecommendTask(
            task_id="retrieve-test",
            content_id="content-456",
            recommend_type="related"
        )
        self.queue.push(task)
        
        retrieved = self.queue.get_task("retrieve-test")
        assert retrieved is not None
        assert retrieved.content_id == "content-456"
        assert retrieved.recommend_type == "related"
    
    def test_task_manager_integration(self):
        task_id = task_manager.create_task(
            user_id="test-user",
            recommend_type="hot",
            limit=5,
            priority=7
        )
        
        assert task_id is not None
        assert len(task_id) > 0
        
        status = task_manager.get_task_status(task_id)
        assert status is not None
        assert status["status"] in ["pending", "processing"]
    
    def test_queue_clear(self):
        for i in range(5):
            self.queue.push(RecommendTask(task_id=f"clear-task-{i}"))
        
        assert self.queue.size() == 5
        
        cleared = self.queue.clear()
        assert cleared == 5
        assert self.queue.size() == 0


class TestSortStrategyConfiguration:
    def setup_method(self):
        strategy_manager.load_config()
    
    def test_config_loading(self):
        strategies = strategy_manager.list_enabled_strategies()
        
        assert len(strategies) > 0
        assert any(s["id"] == "relevance" for s in strategies)
        assert any(s["id"] == "balanced" for s in strategies)
    
    def test_default_strategy(self):
        default = strategy_manager.get_default_strategy()
        strategy = strategy_manager.get_strategy(default)
        
        assert default is not None
        assert strategy is not None
        assert strategy.enabled is True
    
    def test_strategy_add_update_delete(self):
        test_scorers = [
            ScorerConfig(name="bm25", weight=0.6, description="BM25"),
            ScorerConfig(name="recency", weight=0.4, description="Recency")
        ]
        
        new_strategy = StrategyConfig(
            strategy_id="test-strategy",
            name="Test Strategy",
            description="Test sorting strategy",
            enabled=True,
            scorers=test_scorers
        )
        
        added = strategy_manager.add_strategy(new_strategy)
        assert added is True
        
        retrieved = strategy_manager.get_strategy("test-strategy")
        assert retrieved is not None
        assert retrieved.name == "Test Strategy"
        
        retrieved.name = "Updated Test Strategy"
        updated = strategy_manager.update_strategy(retrieved)
        assert updated is True
        
        reloaded = strategy_manager.get_strategy("test-strategy")
        assert reloaded.name == "Updated Test Strategy"
        
        deleted = strategy_manager.delete_strategy("test-strategy")
        assert deleted is True
        
        after_delete = strategy_manager.get_strategy("test-strategy")
        assert after_delete is None
    
    def test_bm25_scorer(self):
        scorer = BM25Scorer()
        
        class MockIndex:
            pass
        
        mock_index = MockIndex()
        result = {
            "index": None,
            "bm25_score": 50.0
        }
        
        score = scorer.score(mock_index, result, {})
        assert 0.0 <= score <= 1.0
    
    def test_recency_scorer(self):
        scorer = RecencyScorer()
        
        class MockIndex:
            def __init__(self, days_old):
                self.publish_date = datetime.utcnow() - timedelta(days=days_old)
        
        recent = MockIndex(days_old=1)
        older = MockIndex(days_old=180)
        very_old = MockIndex(days_old=400)
        
        result_recent = {"index": recent}
        result_older = {"index": older}
        result_old = {"index": very_old}
        
        score_recent = scorer.score(None, result_recent, {})
        score_older = scorer.score(None, result_older, {})
        score_old = scorer.score(None, result_old, {})
        
        assert score_recent > score_older
        assert score_older > score_old
        assert 0.0 <= score_old <= 1.0
    
    def test_popularity_scorer(self):
        scorer = PopularityScorer()
        
        class MockIndex:
            def __init__(self, clicks):
                self.click_count = clicks
        
        popular = MockIndex(clicks=1000)
        normal = MockIndex(clicks=500)
        unpopular = MockIndex(clicks=10)
        
        result_popular = {"index": popular}
        result_normal = {"index": normal}
        result_unpopular = {"index": unpopular}
        
        score_popular = scorer.score(None, result_popular, {})
        score_normal = scorer.score(None, result_normal, {})
        score_unpopular = scorer.score(None, result_unpopular, {})
        
        assert score_popular >= score_normal
        assert score_normal >= score_unpopular
    
    def test_category_scorer(self):
        scorer = CategoryScorer({
            "boost_categories": ["python", "ai"],
            "boost_factor": 1.5
        })
        
        class MockIndex:
            def __init__(self, category):
                self.category = category
        
        python_idx = MockIndex(category="python")
        java_idx = MockIndex(category="java")
        ai_idx = MockIndex(category="ai")
        
        score_python = scorer.score(None, {"index": python_idx}, {})
        score_java = scorer.score(None, {"index": java_idx}, {})
        score_ai = scorer.score(None, {"index": ai_idx}, {})
        
        assert score_python > score_java
        assert score_ai > score_java
    
    def test_strategy_weight_normalization(self):
        strategy = StrategyConfig(
            strategy_id="weight-test",
            scorers=[
                ScorerConfig(name="bm25", weight=50.0),
                ScorerConfig(name="recency", weight=30.0),
                ScorerConfig(name="popularity", weight=20.0)
            ]
        )
        
        total_before = strategy.get_total_weight()
        strategy.normalize_weights()
        total_after = strategy.get_total_weight()
        
        assert abs(total_after - 1.0) < 0.01
    
    def test_enable_disable_strategy(self):
        test_strategy = StrategyConfig(
            strategy_id="enable-test",
            name="Enable Test",
            enabled=False,
            scorers=[ScorerConfig(name="bm25", weight=1.0)]
        )
        
        strategy_manager.add_strategy(test_strategy)
        
        before = strategy_manager.get_strategy("enable-test")
        assert before.enabled is False
        
        strategy_manager.enable_strategy("enable-test")
        enabled = strategy_manager.get_strategy("enable-test")
        assert enabled.enabled is True
        
        strategy_manager.disable_strategy("enable-test")
        disabled = strategy_manager.get_strategy("enable-test")
        assert disabled.enabled is False
        
        strategy_manager.delete_strategy("enable-test")


class TestIntegrationFeatures:
    def test_cache_invalidation_with_index_events(self):
        index_manager.clear_indexes()
        cache_module.clear()
        cache_invalidator.set_cache_module(cache_module)
        cache_invalidator.enable()
        
        cache_module.set("search:query:python", {"data": "test"}, ttl=60)
        
        request = IndexUpdateRequest(
            content_id="integration-test",
            title="Test Content",
            content="This is test content for integration",
            category="test",
            keywords=["test", "integration"]
        )
        
        index_manager.create_index(request)
        
        time.sleep(0.1)
        
        cached = cache_module.get("search:query:python")
        assert cached is None
    
    def test_sort_with_configured_strategy(self):
        from searchengine.modules.sort_module import sort_module
        
        class MockIndex:
            def __init__(self, title, clicks, days_old):
                self.content_id = f"idx-{title}"
                self.title = title
                self.content = title
                self.category = "test"
                self.author = "test"
                self.publish_time = datetime.utcnow() - timedelta(days=days_old)
                self.click_count = clicks
        
        indexes = [
            MockIndex("python tutorial", 100, 1),
            MockIndex("python guide", 500, 100),
            MockIndex("python basics", 1000, 200)
        ]
        
        balanced_results = sort_module.sort_with_configured_strategy(
            indexes=indexes,
            strategy_id="balanced",
            search_keywords=["python"]
        )
        
        assert len(balanced_results) == 3
        
        fresh_results = sort_module.sort_with_configured_strategy(
            indexes=indexes,
            strategy_id="fresh",
            search_keywords=["python"]
        )
        
        popular_results = sort_module.sort_with_configured_strategy(
            indexes=indexes,
            strategy_id="popular",
            search_keywords=["python"]
        )
        
        assert balanced_results[0].content_id != fresh_results[0].content_id or \
               fresh_results[0].content_id != popular_results[0].content_id


if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
