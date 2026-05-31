import pytest


def test_run_lifecycle_cycle_endpoint(client):
    response = client.post(
        "/api/v1/lifecycle/run-cycle",
        json={
            "table_name": "test_table",
            "hot_threshold_days": 7,
            "cold_threshold_days": 30,
            "archive_threshold_days": 90,
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "total_processed" in data["data"]


def test_get_storage_tiers_endpoint(client):
    response = client.get("/api/v1/lifecycle/tiers")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "tiers" in data["data"]


def test_archive_data_endpoint(client):
    response = client.post(
        "/api/v1/lifecycle/archive",
        json={
            "table_name": "test_table",
            "partition": "2024-01",
            "format": "json",
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "archive_path" in data["data"]


def test_cleanup_old_data_endpoint(client):
    response = client.post(
        "/api/v1/lifecycle/cleanup",
        json={
            "path": "/data/old",
            "days": 90,
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "removed_count" in data["data"]


def test_get_lifecycle_config_endpoint(client):
    response = client.get("/api/v1/lifecycle/config")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "config" in data["data"]
