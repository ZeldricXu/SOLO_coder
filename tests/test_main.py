from __future__ import annotations


def test_root_endpoint(client):
    response = client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert data["data"]["name"] == "Enterprise Infrastructure Platform"
    assert data["data"]["version"] == "1.0.0"
    assert "modules" in data["data"]


def test_health_check(client):
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert data["data"]["status"] == "healthy"


def test_list_modules(client):
    response = client.get("/api/v1/modules")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
    assert len(data["data"]) == 9
