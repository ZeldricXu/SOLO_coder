import sys
from pathlib import Path

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from datetime import datetime
from searchengine.models.base import IndexUpdateRequest, SearchIndex
from searchengine.modules.index_manager import index_manager


class TestIndexManager:
    def setup_method(self):
        index_manager.clear_all_indexes()
    
    def test_create_index(self, sample_index_data):
        request = IndexUpdateRequest(**sample_index_data)
        result = index_manager.create_index(request)
        
        assert result is not None
        assert result.content_id == sample_index_data["content_id"]
        assert result.title == sample_index_data["title"]
        assert result.index_id.startswith("index_")
        assert index_manager.get_index_count() == 1
    
    def test_update_existing_index(self, sample_index_data):
        request = IndexUpdateRequest(**sample_index_data)
        index_manager.create_index(request)
        
        updated_data = sample_index_data.copy()
        updated_data["title"] = "更新后的标题"
        updated_request = IndexUpdateRequest(**updated_data)
        
        result = index_manager.update_index(updated_request)
        
        assert result.title == "更新后的标题"
        assert index_manager.get_index_count() == 1
    
    def test_search_indexes(self):
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_001",
            title="Python编程入门",
            content="学习Python编程的基础知识",
            keywords=["Python", "编程"]
        ))
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_002",
            title="Java编程教程",
            content="Java编程从入门到精通",
            keywords=["Java", "编程"]
        ))
        
        results = index_manager.search_indexes("Python")
        assert len(results) == 1
        assert results[0].content_id == "content_001"
    
    def test_search_with_filters(self):
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_001",
            title="技术文章1",
            content="技术内容",
            keywords=["技术"],
            category="技术"
        ))
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_002",
            title="生活文章",
            content="生活内容",
            keywords=["生活"],
            category="生活"
        ))
        
        results = index_manager.search_indexes("文章", {"category": "技术"})
        assert len(results) == 1
        assert results[0].category == "技术"
    
    def test_delete_index(self):
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_001",
            title="测试文章",
            content="测试内容",
            keywords=["测试"]
        ))
        
        assert index_manager.get_index_count() == 1
        result = index_manager.delete_index("content_001")
        
        assert result is True
        assert index_manager.get_index_count() == 0
    
    def test_delete_nonexistent_index(self):
        result = index_manager.delete_index("nonexistent")
        assert result is False
    
    def test_get_index_by_content_id(self):
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_001",
            title="测试文章",
            content="测试内容",
            keywords=["测试"]
        ))
        
        result = index_manager.get_index_by_content_id("content_001")
        assert result is not None
        assert result.content_id == "content_001"
    
    def test_increment_click_count(self):
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_001",
            title="测试文章",
            content="测试内容",
            keywords=["测试"]
        ))
        
        count = index_manager.increment_click_count("content_001")
        assert count == 1
        
        count = index_manager.increment_click_count("content_001")
        assert count == 2
    
    def test_clear_all_indexes(self):
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_001",
            title="测试文章1",
            content="测试内容1",
            keywords=["测试"]
        ))
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_002",
            title="测试文章2",
            content="测试内容2",
            keywords=["测试"]
        ))
        
        assert index_manager.get_index_count() == 2
        index_manager.clear_all_indexes()
        assert index_manager.get_index_count() == 0
    
    def test_get_all_indexes(self):
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_001",
            title="测试文章1",
            content="测试内容1",
            keywords=["测试"]
        ))
        index_manager.create_index(IndexUpdateRequest(
            content_id="content_002",
            title="测试文章2",
            content="测试内容2",
            keywords=["测试"]
        ))
        
        all_indexes = index_manager.get_all_indexes()
        assert len(all_indexes) == 2
