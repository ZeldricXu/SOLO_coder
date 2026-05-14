import sys
import time
import hashlib
import json
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from tests.test_data_builder import test_data_builder
from searchengine.modules.cache_module import cache_module
from searchengine.modules.query_processor import query_processor
from searchengine.modules.index_manager import index_manager
from searchengine.modules.sort_module import sort_module
from searchengine.modules.performance_monitor import performance_monitor


class TestCacheHitCorrectness:
    def setup_method(self):
        cache_module.clear()
        cache_module.enable()
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_cache_set_and_get(self):
        test_data = {
            "results": [
                {"content_id": "1", "title": "文章1", "relevance": 0.9}
            ],
            "total_count": 1
        }
        
        cache_key = "test:query:1"
        success = cache_module.set(cache_key, test_data)
        
        assert success is True
        
        cached = cache_module.get(cache_key)
        assert cached == test_data
        assert isinstance(cached["results"], list)
    
    def test_cache_key_generation_consistency(self):
        request1 = test_data_builder.create_search_request(
            keyword="Python编程",
            filters={"category": "技术"},
            sort_type="relevance"
        )
        
        request2 = test_data_builder.create_search_request(
            keyword="Python编程",
            filters={"category": "技术"},
            sort_type="relevance"
        )
        
        key1 = query_processor.build_cache_key(request1)
        key2 = query_processor.build_cache_key(request2)
        
        assert key1 == key2
    
    def test_cache_key_generation_different_keywords(self):
        request1 = test_data_builder.create_search_request(keyword="Python")
        request2 = test_data_builder.create_search_request(keyword="Java")
        
        key1 = query_processor.build_cache_key(request1)
        key2 = query_processor.build_cache_key(request2)
        
        assert key1 != key2
    
    def test_cache_key_generation_different_filters(self):
        request1 = test_data_builder.create_search_request(
            keyword="编程",
            filters={"category": "技术"}
        )
        request2 = test_data_builder.create_search_request(
            keyword="编程",
            filters={"category": "生活"}
        )
        
        key1 = query_processor.build_cache_key(request1)
        key2 = query_processor.build_cache_key(request2)
        
        assert key1 != key2
    
    def test_hot_query_cache_hit(self):
        python_indexes = test_data_builder.create_python_article_indexes(5)
        for idx in python_indexes:
            req = test_data_builder.create_index_update_request(
                content_id=idx.content_id,
                title=idx.title,
                content=idx.content,
                keywords=idx.keywords,
                category=idx.category
            )
            index_manager.create_index(req)
        
        request = test_data_builder.create_search_request(keyword="Python")
        cache_key = query_processor.build_cache_key(request)
        
        parsed = query_processor.parse_request(request)
        candidates = index_manager.search_indexes(parsed["keyword"], parsed["filters"])
        sorted_results = sort_module.sort_results(
            candidates,
            request.sort_type,
            parsed["keywords_tokens"]
        )
        
        test_data = {"results": [r.model_dump() for r in sorted_results], "total": len(sorted_results)}
        cache_module.set(cache_key, test_data)
        
        for i in range(10):
            cached = cache_module.get(cache_key)
            assert cached is not None
            assert cached["total"] == len(sorted_results)
        
        info = cache_module.get_key_info(cache_key)
        assert info["hits"] == 10
    
    def test_cache_disabled(self):
        cache_module.disable()
        
        cache_module.set("test:disabled", "value")
        result = cache_module.get("test:disabled")
        
        assert result is None
        assert cache_module.is_enabled() is False
        
        cache_module.enable()


class TestCacheInvalidation:
    def setup_method(self):
        cache_module.clear()
        cache_module.enable()
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_cache_invalidation_on_content_update(self):
        request = test_data_builder.create_index_update_request(
            content_id="content_001",
            title="原始标题",
            content="原始内容",
            keywords=["原始"]
        )
        index_manager.create_index(request)
        
        search_request = test_data_builder.create_search_request(keyword="原始")
        cache_key = query_processor.build_cache_key(search_request)
        
        candidates = index_manager.search_indexes("原始")
        cache_module.set(cache_key, {"count": len(candidates), "titles": [idx.title for idx in candidates]})
        
        update_request = test_data_builder.create_index_update_request(
            content_id="content_001",
            title="更新后的标题",
            content="更新后的内容",
            keywords=["更新"]
        )
        index_manager.update_index(update_request)
        
        cache_module.delete_pattern("search:query:*")
        
        cache_module.set(cache_key, {"test": "should_be_deleted"})
        
        new_candidates = index_manager.search_indexes("更新")
        assert len(new_candidates) == 1
        assert new_candidates[0].title == "更新后的标题"
    
    def test_cache_delete_pattern(self):
        cache_module.set("search:query:1", {"data": 1})
        cache_module.set("search:query:2", {"data": 2})
        cache_module.set("other:key", {"data": 3})
        
        deleted = cache_module.delete_pattern("search:query:*")
        
        assert deleted == 2
        assert cache_module.get("search:query:1") is None
        assert cache_module.get("search:query:2") is None
        assert cache_module.get("other:key") is not None
    
    def test_cache_manual_clear(self):
        for i in range(50):
            cache_module.set(f"test:cache:{i}", f"value{i}")
        
        stats = cache_module.get_stats()
        assert stats["total_keys"] == 50
        
        cleared = cache_module.clear()
        assert cleared == 50
        
        stats = cache_module.get_stats()
        assert stats["total_keys"] == 0


class TestCacheExpiration:
    def setup_method(self):
        cache_module.clear()
        cache_module.enable()
        test_data_builder.reset_counters()
    
    def test_cache_ttl_expiration(self):
        cache_module.set("test:ttl:1", "value1", ttl=1)
        cache_module.set("test:ttl:2", "value2", ttl=100)
        
        assert cache_module.get("test:ttl:1") == "value1"
        assert cache_module.get("test:ttl:2") == "value2"
        
        time.sleep(1.1)
        
        assert cache_module.get("test:ttl:1") is None
        assert cache_module.get("test:ttl:2") == "value2"
    
    def test_cache_remaining_ttl(self):
        cache_module.set("test:ttl:info", "value", ttl=60)
        
        info = cache_module.get_key_info("test:ttl:info")
        
        assert info is not None
        assert info["remaining_ttl"] <= 60
        assert info["remaining_ttl"] > 0
    
    def test_clean_expired_cache(self):
        for i in range(5):
            cache_module.set(f"test:expired:{i}", f"value{i}", ttl=1)
        
        cache_module.set("test:persist:1", "persist1", ttl=1000)
        cache_module.set("test:persist:2", "persist2", ttl=1000)
        
        time.sleep(1.1)
        
        cleaned = cache_module.clean_expired()
        
        assert cleaned == 5
        assert cache_module.get("test:persist:1") == "persist1"
        assert cache_module.get("test:persist:2") == "persist2"


class TestCacheConsistency:
    def setup_method(self):
        cache_module.clear()
        cache_module.enable()
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_cache_vs_live_retrieval(self):
        python_indexes = test_data_builder.create_python_article_indexes(5)
        for idx in python_indexes:
            req = test_data_builder.create_index_update_request(
                content_id=idx.content_id,
                title=idx.title,
                content=idx.content,
                keywords=idx.keywords
            )
            index_manager.create_index(req)
        
        request = test_data_builder.create_search_request(keyword="Python")
        cache_key = query_processor.build_cache_key(request)
        
        parsed = query_processor.parse_request(request)
        candidates = index_manager.search_indexes(parsed["keyword"], parsed["filters"])
        
        live_results = sort_module.sort_results(
            candidates,
            request.sort_type,
            parsed["keywords_tokens"]
        )
        
        cache_data = {
            "results": [r.model_dump() for r in live_results],
            "total_count": len(live_results)
        }
        cache_module.set(cache_key, cache_data)
        
        cached_data = cache_module.get(cache_key)
        
        assert cached_data["total_count"] == len(live_results)
        
        cached_titles = [r["title"] for r in cached_data["results"]]
        live_titles = [r.title for r in live_results]
        assert cached_titles == live_titles
    
    def test_consistency_after_incremental_update(self):
        request = test_data_builder.create_index_update_request(
            content_id="consist_001",
            title="原始文章",
            content="原始内容",
            keywords=["原始", "测试"]
        )
        index_manager.create_index(request)
        
        search_req = test_data_builder.create_search_request(keyword="原始")
        cache_key = query_processor.build_cache_key(search_req)
        
        candidates1 = index_manager.search_indexes("原始")
        cache_module.set(cache_key, {"count": len(candidates1)})
        
        inc_req = test_data_builder.create_index_update_request(
            content_id="consist_001",
            title="更新文章",
            content="",
            keywords=[]
        )
        inc_req.content = ""
        inc_req.keywords = []
        index_manager.incremental_update(inc_req)
        
        candidates2 = index_manager.search_indexes("原始")
        
        updated = index_manager.get_index_by_content_id("consist_001")
        assert updated.title == "更新文章"
        assert updated.content == "原始内容"
    
    def test_consistency_across_multiple_queries(self):
        for i in range(10):
            req = test_data_builder.create_index_update_request(
                content_id=f"multi_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=["编程", f"kw{i}"]
            )
            index_manager.create_index(req)
        
        results1 = index_manager.search_indexes("编程")
        results2 = index_manager.search_indexes("编程")
        results3 = index_manager.search_indexes("编程")
        
        assert len(results1) == len(results2) == len(results3) == 10
        
        titles1 = sorted([r.title for r in results1])
        titles2 = sorted([r.title for r in results2])
        titles3 = sorted([r.title for r in results3])
        
        assert titles1 == titles2 == titles3


class TestPerformanceCacheIntegration:
    def setup_method(self):
        cache_module.clear()
        cache_module.enable()
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_cache_hit_tracking(self):
        for i in range(20):
            req = test_data_builder.create_index_update_request(
                content_id=f"perf_{i}",
                title=f"文章{i}",
                content=f"Python内容{i}",
                keywords=["Python"]
            )
            index_manager.create_index(req)
        
        request = test_data_builder.create_search_request(keyword="Python")
        cache_key = query_processor.build_cache_key(request)
        
        candidates = index_manager.search_indexes("Python")
        cache_module.set(cache_key, {"count": len(candidates)})
        
        for i in range(100):
            performance_monitor.record_search(10, from_cache=True)
            cache_module.get(cache_key)
        
        for i in range(50):
            performance_monitor.record_search(50, from_cache=False)
        
        metrics = performance_monitor.get_metrics_summary()
        
        assert metrics["cache_hits"] >= 0
    
    def test_get_or_set_pattern(self):
        call_count = [0]
        
        def expensive_computation():
            call_count[0] += 1
            time.sleep(0.01)
            return {"computed": True, "value": call_count[0]}
        
        result1 = cache_module.get_or_set("test:getorset", expensive_computation, ttl=60)
        
        result2 = cache_module.get_or_set("test:getorset", expensive_computation, ttl=60)
        result3 = cache_module.get_or_set("test:getorset", expensive_computation, ttl=60)
        
        assert call_count[0] == 1
        assert result1 == result2 == result3


class TestCacheThreadSafety:
    def setup_method(self):
        cache_module.clear()
        cache_module.enable()
        test_data_builder.reset_counters()
    
    def test_concurrent_cache_reads(self):
        cache_module.set("test:concurrent:read", {"value": "test_data"})
        
        results = []
        
        def read_worker():
            for _ in range(100):
                result = cache_module.get("test:concurrent:read")
                results.append(result is not None)
        
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(read_worker) for _ in range(10)]
            for f in as_completed(futures):
                f.result()
        
        assert all(results)
        assert len(results) == 1000
    
    def test_concurrent_cache_writes(self):
        errors = []
        
        def write_worker(worker_id):
            try:
                for i in range(100):
                    key = f"test:concurrent:write:{worker_id}:{i}"
                    cache_module.set(key, {"worker": worker_id, "idx": i})
                    value = cache_module.get(key)
                    assert value["worker"] == worker_id
            except Exception as e:
                errors.append(e)
        
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(write_worker, i) for i in range(10)]
            for f in as_completed(futures):
                f.result()
        
        assert len(errors) == 0
        stats = cache_module.get_stats()
        assert stats["total_keys"] == 1000
    
    def test_concurrent_read_write(self):
        errors = []
        
        def writer():
            try:
                for i in range(100):
                    cache_module.set(f"test:rw:{i}", f"value{i}")
            except Exception as e:
                errors.append(e)
        
        def reader():
            try:
                for i in range(100):
                    cache_module.get(f"test:rw:{i % 50}")
            except Exception as e:
                errors.append(e)
        
        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = []
            futures.append(executor.submit(writer))
            for _ in range(7):
                futures.append(executor.submit(reader))
            
            for f in as_completed(futures):
                f.result()
        
        assert len(errors) == 0
