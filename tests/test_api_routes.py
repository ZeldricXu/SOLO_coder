import pytest


def test_root_endpoint(client):
    response = client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert "app" in data
    assert "version" in data
    assert "status" in data


def test_health_endpoint(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "healthy"}


def test_ask_question(client):
    response = client.post(
        "/api/v1/qa/ask",
        json={"user_id": "user_10086", "question": "忘记密码怎么办"}
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert "reply" in data["data"]
    assert "recommendations" in data["data"]


def test_ask_general_question(client):
    response = client.post(
        "/api/v1/qa/ask",
        json={"user_id": "user_10087", "question": "这是一个测试问题"}
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200


def test_list_knowledges(client):
    response = client.get("/api/v1/knowledge")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


def test_list_categories(client):
    response = client.get("/api/v1/knowledge/categories/list")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


def test_create_knowledge(client):
    response = client.post(
        "/api/v1/knowledge/create",
        json={
            "knowledge_title": "测试知识",
            "knowledge_content": "这是测试知识的内容",
            "knowledge_category": "测试分类",
            "knowledge_tags": ["测试"],
            "knowledge_keywords": ["测试"]
        }
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "knowledge_id" in data["data"]


def test_get_stats(client):
    response = client.get("/api/v1/qa/stats")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "stats" in data["data"]


def test_list_intents(client):
    response = client.get("/api/v1/intents")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
    assert len(data["data"]) > 0


def test_list_templates(client):
    response = client.get("/api/v1/templates")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
    assert len(data["data"]) > 0


def test_list_history(client):
    response = client.get("/api/v1/qa/history?limit=10")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
