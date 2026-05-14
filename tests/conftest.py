import sys
import pytest
from pathlib import Path

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from fastapi.testclient import TestClient
from main import create_app


@pytest.fixture
def app():
    return create_app()


@pytest.fixture
def client(app):
    return TestClient(app)


@pytest.fixture
def sample_index_data():
    return {
        "content_id": "content_001",
        "content_type": "article",
        "title": "Python编程技术入门",
        "content": "这是一篇关于Python编程的入门文章，包含基础语法、数据类型、函数等内容。",
        "keywords": ["Python", "编程", "入门"],
        "category": "技术",
        "author": "author_001"
    }


@pytest.fixture
def sample_search_request():
    return {
        "keyword": "Python编程",
        "filters": {"category": "技术"},
        "sort_type": "relevance",
        "page": 1,
        "page_size": 10
    }
