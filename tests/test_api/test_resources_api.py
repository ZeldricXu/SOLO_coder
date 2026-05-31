import pytest


def test_create_resource_endpoint(client):
    response = client.post(
        "/api/v1/resources",
        json={
            "type": "job",
            "config": {"task": "metadata_crawl"},
            "labels": {"env": "test"},
        },
    )
    assert response.status_code == 201
    data = response.json()
    assert data["code"] == 201
    assert "id" in data["data"]
    assert data["data"]["status"] == "provisioning"


def test_get_resource_status_endpoint(client):
    response = client.get("/api/v1/resources/rsc-123/status")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "id" in data["data"]
    assert "status" in data["data"]


def test_batch_operation_endpoint(client):
    response = client.post(
        "/api/v1/resources/batch",
        json={
            "operations": [
                {"action": "start", "id": "rsc-001"},
                {"action": "stop", "id": "rsc-002"},
            ]
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "batch_id" in data["data"]
    assert "results" in data["data"]


def test_list_resources_endpoint(client):
    response = client.get("/api/v1/resources")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "resources" in data["data"]


def test_delete_resource_endpoint(client):
    response = client.delete("/api/v1/resources/rsc-123")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
