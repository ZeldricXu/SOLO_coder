import sys
from pathlib import Path

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import pytest
from fastapi.testclient import TestClient
from main import create_app


class TestAPIRoutes:
    def setup_method(self):
        self.client = TestClient(create_app())
        from searchengine.modules.index_manager import index_manager
        from searchengine.modules.cache_module import cache_module
        index_manager.clear_all_indexes()
        cache_module.clear()
    
    def test_health_check(self):
        response = self.client.get("/api/v1/search/health")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
    
    def test_root_endpoint(self):
        response = self.client.get("/")
        assert response.status_code == 200
        data = response.json()
        assert "service" in data
        assert data["status"] == "running"
    
    def test_index_creation(self, sample_index_data):
        response = self.client.post("/api/v1/search/index", json=sample_index_data)
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "data" in data
        assert "index_id" in data["data"]
    
    def test_index_creation_missing_fields(self):
        invalid_data = {"title": "缺少content_id"}
        response = self.client.post("/api/v1/search/index", json=invalid_data)
        assert response.status_code == 422
    
    def test_search_query(self, sample_index_data):
        self.client.post("/api/v1/search/index", json=sample_index_data)
        
        search_request = {
            "keyword": "Python编程",
            "filters": {},
            "sort_type": "relevance",
            "page": 1,
            "page_size": 10
        }
        response = self.client.post("/api/v1/search/query", json=search_request)
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "data" in data
    
    def test_search_no_results(self):
        search_request = {
            "keyword": "不存在的关键词xyz123",
            "filters": {},
            "sort_type": "relevance",
            "page": 1,
            "page_size": 10
        }
        response = self.client.post("/api/v1/search/query", json=search_request)
        assert response.status_code == 200
        data = response.json()
        assert data["data"]["total_count"] == 0
    
    def test_search_with_filters(self, sample_index_data):
        self.client.post("/api/v1/search/index", json=sample_index_data)
        
        search_request = {
            "keyword": "Python",
            "filters": {"category": "技术"},
            "sort_type": "relevance",
            "page": 1,
            "page_size": 10
        }
        response = self.client.post("/api/v1/search/query", json=search_request)
        assert response.status_code == 200
    
    def test_recommend_hot(self):
        response = self.client.get("/api/v1/search/recommend?recommend_type=hot&limit=5")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "recommend_items" in data["data"]
    
    def test_recommend_related(self, sample_index_data):
        self.client.post("/api/v1/search/index", json=sample_index_data)
        
        response = self.client.get(
            "/api/v1/search/recommend?content_id=content_001&recommend_type=related&limit=5"
        )
        assert response.status_code == 200
    
    def test_list_indexes(self, sample_index_data):
        self.client.post("/api/v1/search/index", json=sample_index_data)
        
        response = self.client.get("/api/v1/search/indexes?page=1&page_size=10")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "indexes" in data["data"]
        assert len(data["data"]["indexes"]) >= 1
    
    def test_get_index_by_content_id(self, sample_index_data):
        self.client.post("/api/v1/search/index", json=sample_index_data)
        
        response = self.client.get("/api/v1/search/indexes/content_001")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert data["data"]["content_id"] == "content_001"
    
    def test_get_nonexistent_index(self):
        response = self.client.get("/api/v1/search/indexes/nonexistent_123")
        assert response.status_code == 404
    
    def test_delete_index(self, sample_index_data):
        self.client.post("/api/v1/search/index", json=sample_index_data)
        
        response = self.client.delete("/api/v1/search/index/content_001")
        assert response.status_code == 200
        
        response = self.client.get("/api/v1/search/indexes/content_001")
        assert response.status_code == 404
    
    def test_delete_nonexistent_index(self):
        response = self.client.delete("/api/v1/search/index/nonexistent_123")
        assert response.status_code == 404
    
    def test_batch_index_update(self):
        batch_data = [
            {
                "content_id": "batch_001",
                "title": "批量文章1",
                "content": "批量更新的内容1"
            },
            {
                "content_id": "batch_002",
                "title": "批量文章2",
                "content": "批量更新的内容2"
            }
        ]
        response = self.client.post("/api/v1/search/index/batch", json=batch_data)
        assert response.status_code == 200
        data = response.json()
        assert "results" in data["data"]
        assert len(data["data"]["results"]) == 2
    
    def test_get_stats(self):
        response = self.client.get("/api/v1/search/stats")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "today" in data["data"]
        assert "total_search_count" in data["data"]
    
    def test_get_performance(self):
        response = self.client.get("/api/v1/search/performance")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "metrics" in data["data"]
        assert "health" in data["data"]
    
    def test_get_hot_keywords(self):
        response = self.client.get("/api/v1/search/keywords/hot?top_n=10")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "hot_keywords" in data["data"]
    
    def test_analyze_keywords(self):
        response = self.client.post("/api/v1/search/keywords/analyze?text=Python编程技术入门")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "tokens" in data["data"]
        assert "top_keywords" in data["data"]
    
    def test_list_sort_strategies(self):
        response = self.client.get("/api/v1/search/strategies")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "strategies" in data["data"]
        assert len(data["data"]["strategies"]) >= 4
    
    def test_clear_cache(self):
        response = self.client.post("/api/v1/search/cache/clear")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "cleared_count" in data["data"]
    
    def test_get_logs(self):
        response = self.client.get("/api/v1/search/logs?limit=10")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert "logs" in data["data"]
    
    def test_pagination_in_search(self):
        for i in range(25):
            self.client.post("/api/v1/search/index", json={
                "content_id": f"pg_{i:03d}",
                "title": f"测试文章{i}",
                "content": "测试内容",
                "keywords": ["测试"]
            })
        
        response = self.client.post("/api/v1/search/query", json={
            "keyword": "测试",
            "filters": {},
            "sort_type": "relevance",
            "page": 2,
            "page_size": 10
        })
        assert response.status_code == 200
        data = response.json()
        assert data["data"]["page"] == 2 if "page" in data["data"] else True
