import pytest


def test_crawl_metadata_endpoint(client, mock_data_source):
    response = client.post(
        "/api/v1/metadata/crawl",
        json={
            "data_source": mock_data_source,
            "options": {"sample_size": 100},
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert "schema" in data["data"]
    assert data["data"]["status"] == "completed"


def test_get_schema_endpoint(client):
    response = client.get("/api/v1/metadata/schema/test_db/users")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert "table" in data["data"]


def test_list_tables_endpoint(client):
    response = client.get("/api/v1/metadata/tables/test_db")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert "tables" in data["data"]


def test_get_stats_endpoint(client):
    response = client.get("/api/v1/metadata/stats/test_db/users")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert "table_stats" in data["data"]


def test_get_sample_data_endpoint(client):
    response = client.get(
        "/api/v1/metadata/sample/test_db/users",
        params={"size": 10},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert "sample_data" in data["data"]
