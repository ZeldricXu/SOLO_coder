from __future__ import annotations


def test_list_services(client):
    response = client.get("/api/v1/discovery/services")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
    assert len(data["data"]) >= 5


def test_search_services(client):
    response = client.get("/api/v1/discovery/search", params={"q": "user"})
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


def test_dependency_graph(client):
    response = client.get("/api/v1/discovery/graph")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "nodes" in data["data"]
    assert "edges" in data["data"]
