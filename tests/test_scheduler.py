import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_scheduler_create_task(client: AsyncClient):
    request = {
        "name": "test-task",
        "type": "data_processing",
        "priority": 2,
        "payload": {"data": "test"},
        "timeout_seconds": 3600,
        "max_retries": 3,
    }
    response = await client.post("/api/v1/scheduler/tasks", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "task_id" in data["data"]


@pytest.mark.asyncio
async def test_scheduler_list_tasks(client: AsyncClient):
    response = await client.get("/api/v1/scheduler/tasks")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


@pytest.mark.asyncio
async def test_scheduler_summary(client: AsyncClient):
    response = await client.get("/api/v1/scheduler/summary")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "total" in data["data"]
