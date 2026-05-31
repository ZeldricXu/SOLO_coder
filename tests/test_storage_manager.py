import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_storage_list_types(client: AsyncClient):
    response = await client.get("/api/v1/storage/types")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert len(data["data"]) > 0


@pytest.mark.asyncio
async def test_storage_usage(client: AsyncClient):
    response = await client.get("/api/v1/storage/usage")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "total_bytes" in data["data"]
    assert "used_bytes" in data["data"]


@pytest.mark.asyncio
async def test_storage_list_backups(client: AsyncClient):
    response = await client.get("/api/v1/storage/backups")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
