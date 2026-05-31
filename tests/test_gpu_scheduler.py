import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_gpu_cluster_stats(client: AsyncClient):
    response = await client.get("/api/v1/gpu/cluster/stats")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "total_gpus" in data["data"]
    assert "total_memory_gb" in data["data"]


@pytest.mark.asyncio
async def test_gpu_list_devices(client: AsyncClient):
    response = await client.get("/api/v1/gpu/devices")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
    assert len(data["data"]) > 0


@pytest.mark.asyncio
async def test_gpu_submit_task(client: AsyncClient):
    request = {
        "name": "test-gpu-task",
        "priority": 2,
        "required_memory_gb": 4.0,
        "required_gpus": 1,
        "allow_preemption": True,
        "payload": {"model": "test-model"},
        "max_runtime_seconds": 3600,
    }
    response = await client.post("/api/v1/gpu/tasks", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "task_id" in data["data"]
