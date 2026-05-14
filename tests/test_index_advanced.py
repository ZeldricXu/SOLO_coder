import sys
import threading
import time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from tests.test_data_builder import test_data_builder
from searchengine.modules.index_manager import index_manager, IndexUpdateError


class TestIndexIncrementalUpdate:
    def setup_method(self):
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_incremental_update_preserves_unchanged_fields(self):
        original_request = test_data_builder.create_index_update_request(
            content_id="content_001",
            title="原始标题",
            content="原始内容",
            keywords=["原始", "关键词"],
            category="技术",
            author="author_001"
        )
        
        index_manager.create_index(original_request)
        original_index = index_manager.get_index_by_content_id("content_001")
        
        incremental_request = test_data_builder.create_index_update_request(
            content_id="content_001",
            title="更新后的标题",
            content=original_request.content,
            keywords=original_request.keywords,
            category=original_request.category,
            author=original_request.author
        )
        
        updated_index = index_manager.incremental_update(incremental_request)
        
        assert updated_index.title == "更新后的标题"
        assert updated_index.content == original_index.content
        assert updated_index.keywords == original_index.keywords
        assert updated_index.category == original_index.category
        assert updated_index.author == original_index.author
        assert updated_index.click_count == original_index.click_count
    
    def test_incremental_update_partial_fields(self):
        original_request = test_data_builder.create_index_update_request(
            content_id="content_002",
            title="原始标题",
            content="原始内容",
            keywords=["原始"],
            category="技术",
            author="author_001"
        )
        index_manager.create_index(original_request)
        
        partial_request = test_data_builder.create_index_update_request(
            content_id="content_002",
            title="",
            content="",
            keywords=["新关键词"],
            category="",
            author=""
        )
        partial_request.title = ""
        partial_request.content = ""
        partial_request.category = None
        partial_request.author = None
        
        updated_index = index_manager.incremental_update(partial_request)
        
        assert updated_index.keywords == ["新关键词"]
        assert updated_index.title == "原始标题"
        assert updated_index.content == "原始内容"
        assert updated_index.category == "技术"
        assert updated_index.author == "author_001"
    
    def test_incremental_update_not_full_rebuild(self):
        original_request = test_data_builder.create_index_update_request(
            content_id="content_003",
            title="测试文章",
            content="测试内容",
            keywords=["测试"]
        )
        index_manager.create_index(original_request)
        
        original_index_id = index_manager.get_index_by_content_id("content_003").index_id
        
        incremental_request = test_data_builder.create_index_update_request(
            content_id="content_003",
            title="更新标题",
            content="",
            keywords=[]
        )
        incremental_request.content = ""
        incremental_request.keywords = []
        
        updated_index = index_manager.incremental_update(incremental_request)
        
        assert updated_index.index_id == original_index_id
        assert index_manager.get_index_count() == 1
    
    def test_multiple_incremental_updates(self):
        index_manager.create_index(
            test_data_builder.create_index_update_request(
                content_id="content_004",
                title="v1",
                content="内容v1",
                keywords=["v1"]
            )
        )
        
        for i in range(5):
            req = test_data_builder.create_index_update_request(
                content_id="content_004",
                title=f"v{i+2}",
                content="",
                keywords=[]
            )
            req.content = ""
            req.keywords = []
            index_manager.incremental_update(req)
        
        final_index = index_manager.get_index_by_content_id("content_004")
        assert final_index.title == "v6"
        assert final_index.content == "内容v1"


class TestIndexVersionControl:
    def setup_method(self):
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_version_increments_on_update(self):
        request = test_data_builder.create_index_update_request(
            content_id="version_test",
            title="版本1",
            content="内容1"
        )
        index_manager.create_index(request)
        
        version1 = index_manager.get_current_version("version_test")
        assert version1 == 1
        
        request.title = "版本2"
        index_manager.update_index(request)
        
        version2 = index_manager.get_current_version("version_test")
        assert version2 == 2
    
    def test_version_history_preserved(self):
        request = test_data_builder.create_index_update_request(
            content_id="history_test",
            title="v1",
            content="内容1"
        )
        index_manager.create_index(request)
        
        for i in range(4):
            request.title = f"v{i+2}"
            index_manager.update_index(request)
        
        history = index_manager.get_version_history("history_test")
        assert len(history) == 5
        assert history[0].version == 1
        assert history[-1].version == 5
        assert history[0].index.title == "v1"
        assert history[-1].index.title == "v5"
    
    def test_rollback_to_specific_version(self):
        request = test_data_builder.create_index_update_request(
            content_id="rollback_test",
            title="版本1",
            content="原始内容"
        )
        index_manager.create_index(request)
        
        for i in range(3):
            request.title = f"版本{i+2}"
            index_manager.update_index(request)
        
        current = index_manager.get_index_by_content_id("rollback_test")
        assert current.title == "版本4"
        
        success = index_manager.rollback_to_version("rollback_test", 2)
        assert success is True
        
        rolled_back = index_manager.get_index_by_content_id("rollback_test")
        assert rolled_back.title == "版本2"
    
    def test_rollback_to_previous(self):
        request = test_data_builder.create_index_update_request(
            content_id="prev_rollback",
            title="v1",
            content="c1"
        )
        index_manager.create_index(request)
        
        request.title = "v2"
        index_manager.update_index(request)
        
        request.title = "v3"
        index_manager.update_index(request)
        
        assert index_manager.get_index_by_content_id("prev_rollback").title == "v3"
        
        success = index_manager.rollback_to_previous("prev_rollback")
        assert success is True
        
        assert index_manager.get_index_by_content_id("prev_rollback").title == "v2"
    
    def test_rollback_nonexistent_version(self):
        request = test_data_builder.create_index_update_request(
            content_id="no_version",
            title="v1",
            content="c1"
        )
        index_manager.create_index(request)
        
        success = index_manager.rollback_to_version("no_version", 999)
        assert success is False
    
    def test_version_history_max_limit(self):
        index_manager.set_max_versions(3)
        
        request = test_data_builder.create_index_update_request(
            content_id="max_versions",
            title="v1",
            content="c1"
        )
        index_manager.create_index(request)
        
        for i in range(10):
            request.title = f"v{i+2}"
            index_manager.update_index(request)
        
        history = index_manager.get_version_history("max_versions")
        assert len(history) == 3
        assert history[0].version == 9
        assert history[-1].version == 11
    
    def test_click_count_preserved_across_versions(self):
        request = test_data_builder.create_index_update_request(
            content_id="click_preserve",
            title="标题",
            content="内容"
        )
        index_manager.create_index(request)
        
        index_manager.increment_click_count("click_preserve")
        index_manager.increment_click_count("click_preserve")
        
        request.title = "更新后的标题"
        index_manager.update_index(request)
        
        updated = index_manager.get_index_by_content_id("click_preserve")
        assert updated.click_count == 2
        
        index_manager.rollback_to_previous("click_preserve")
        rolled_back = index_manager.get_index_by_content_id("click_preserve")
        assert rolled_back.click_count == 2


class TestIndexRollbackOnFailure:
    def setup_method(self):
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_safe_update_preserves_version(self):
        request = test_data_builder.create_index_update_request(
            content_id="safe_update",
            title="原始标题",
            content="原始内容"
        )
        index_manager.create_index(request)
        
        version_before = index_manager.get_current_version("safe_update")
        
        request.title = "更新标题"
        result = index_manager.safe_update(request)
        
        version_after = index_manager.get_current_version("safe_update")
        assert version_after == version_before + 1
        assert result.title == "更新标题"
    
    def test_rollback_on_nonexistent_content(self):
        success = index_manager.rollback_to_previous("nonexistent")
        assert success is False
    
    def test_click_count_rollback(self):
        request = test_data_builder.create_index_update_request(
            content_id="click_rollback",
            title="v1",
            content="c1"
        )
        index_manager.create_index(request)
        
        for _ in range(10):
            index_manager.increment_click_count("click_rollback")
        
        request.title = "v2"
        index_manager.update_index(request)
        
        for _ in range(5):
            index_manager.increment_click_count("click_rollback")
        
        index_manager.rollback_to_version("click_rollback", 1)
        
        final = index_manager.get_index_by_content_id("click_rollback")
        assert final.title == "v1"
        assert final.click_count == 15


class TestIndexConcurrency:
    def setup_method(self):
        index_manager.clear_all_indexes()
        test_data_builder.reset_counters()
    
    def test_concurrent_updates_same_content(self):
        request = test_data_builder.create_index_update_request(
            content_id="concurrent_1",
            title="初始",
            content="内容"
        )
        index_manager.create_index(request)
        
        def update_content(thread_id):
            local_request = test_data_builder.create_index_update_request(
                content_id="concurrent_1",
                title=f"线程{thread_id}更新",
                content=f"线程{thread_id}内容"
            )
            return index_manager.update_index(local_request)
        
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(update_content, i) for i in range(20)]
            results = [f.result() for f in as_completed(futures)]
        
        assert len(results) == 20
        final = index_manager.get_index_by_content_id("concurrent_1")
        assert final is not None
        assert index_manager.get_index_count() == 1
    
    def test_concurrent_searches_and_updates(self):
        for i in range(10):
            request = test_data_builder.create_index_update_request(
                content_id=f"search_{i}",
                title=f"文章{i}",
                content=f"内容{i}",
                keywords=["关键词", f"kw{i}"]
            )
            index_manager.create_index(request)
        
        errors = []
        
        def search_worker():
            try:
                for _ in range(10):
                    results = index_manager.search_indexes("关键词")
                    assert len(results) >= 0
            except Exception as e:
                errors.append(e)
        
        def update_worker():
            try:
                for i in range(10):
                    request = test_data_builder.create_index_update_request(
                        content_id=f"search_{i}",
                        title=f"更新文章{i}",
                        content=""
                    )
                    request.content = ""
                    index_manager.incremental_update(request)
            except Exception as e:
                errors.append(e)
        
        with ThreadPoolExecutor(max_workers=6) as executor:
            futures = []
            for _ in range(3):
                futures.append(executor.submit(search_worker))
            for _ in range(3):
                futures.append(executor.submit(update_worker))
            
            for f in as_completed(futures):
                f.result()
        
        assert len(errors) == 0
    
    def test_concurrent_click_count_increments(self):
        request = test_data_builder.create_index_update_request(
            content_id="click_concurrent",
            title="点击测试",
            content="内容"
        )
        index_manager.create_index(request)
        
        def increment_click():
            for _ in range(100):
                index_manager.increment_click_count("click_concurrent")
        
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(increment_click) for _ in range(10)]
            for f in as_completed(futures):
                f.result()
        
        final = index_manager.get_index_by_content_id("click_concurrent")
        assert final.click_count == 1000
    
    def test_concurrent_create_and_search(self):
        errors = []
        
        def create_worker(start, end):
            try:
                for i in range(start, end):
                    request = test_data_builder.create_index_update_request(
                        content_id=f"concurrent_create_{i}",
                        title=f"标题{i}",
                        content=f"内容{i}",
                        keywords=["并发", f"测试{i}"]
                    )
                    index_manager.create_index(request)
            except Exception as e:
                errors.append(e)
        
        def search_worker():
            try:
                for _ in range(50):
                    results = index_manager.search_indexes("并发")
                    assert len(results) >= 0
            except Exception as e:
                errors.append(e)
        
        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = []
            futures.append(executor.submit(create_worker, 0, 50))
            futures.append(executor.submit(create_worker, 50, 100))
            for _ in range(6):
                futures.append(executor.submit(search_worker))
            
            for f in as_completed(futures):
                f.result()
        
        assert len(errors) == 0
        assert index_manager.get_index_count() == 100
    
    def test_concurrent_version_history(self):
        request = test_data_builder.create_index_update_request(
            content_id="version_concurrent",
            title="v0",
            content="c0"
        )
        index_manager.create_index(request)
        
        def update_and_check():
            for i in range(10):
                req = test_data_builder.create_index_update_request(
                    content_id="version_concurrent",
                    title=f"update_{threading.current_thread().name}_{i}",
                    content="内容"
                )
                index_manager.update_index(req)
                version = index_manager.get_current_version("version_concurrent")
                assert version >= 1
        
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(update_and_check) for _ in range(5)]
            for f in as_completed(futures):
                f.result()
        
        history = index_manager.get_version_history("version_concurrent")
        assert len(history) >= 10
