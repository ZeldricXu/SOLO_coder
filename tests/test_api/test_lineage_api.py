import pytest


def test_extract_lineage_endpoint(client):
    response = client.post(
        "/api/v1/lineage/extract",
        json={
            "sql_queries": [
                "CREATE TABLE user_summary AS SELECT id, name FROM users",
                "CREATE TABLE user_report AS SELECT * FROM user_summary",
            ]
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "table_lineages" in data["data"]
    assert len(data["data"]["table_lineages"]) == 2


def test_get_upstream_lineage_endpoint(client):
    response = client.get("/api/v1/lineage/upstream/user_report")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "upstream" in data["data"]


def test_get_downstream_lineage_endpoint(client):
    response = client.get("/api/v1/lineage/downstream/users")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "downstream" in data["data"]


def test_find_path_endpoint(client):
    response = client.get("/api/v1/lineage/path/users/user_report")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "path" in data["data"]


def test_impact_analysis_endpoint(client):
    response = client.get("/api/v1/lineage/impact/users")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "impact" in data["data"]


def test_visualize_lineage_endpoint(client):
    response = client.get("/api/v1/lineage/visualize")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "graphviz" in data["data"]
    assert "digraph" in data["data"]["graphviz"]
