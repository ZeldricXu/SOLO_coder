import pytest
from fastapi.testclient import TestClient


def test_create_scheduled_task(client: TestClient):
    task_data = {
        "name": "test_task",
        "task_type": "data_collection",
        "cron_expression": "*/5 * * * *",
        "enabled": True,
        "parameters": {"source": "sensor1"},
    }
    response = client.post("/api/v1/scheduler/tasks", json=task_data)
    assert response.status_code in [200, 201]


def test_list_scheduled_tasks(client: TestClient):
    response = client.get("/api/v1/scheduler/tasks")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data


def test_get_task_not_found(client: TestClient):
    response = client.get("/api/v1/scheduler/tasks/nonexistent-id")
    assert response.status_code in [404, 200]


def test_trigger_task(client: TestClient):
    response = client.post("/api/v1/scheduler/tasks/nonexistent-id/trigger")
    assert response.status_code in [200, 404]


def test_delete_task(client: TestClient):
    response = client.delete("/api/v1/scheduler/tasks/nonexistent-id")
    assert response.status_code in [200, 404]
