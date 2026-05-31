import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_data_list_schemas(client: AsyncClient):
    response = await client.get("/api/v1/data/schemas")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


@pytest.mark.asyncio
async def test_data_register_schema(client: AsyncClient):
    request = {
        "name": "test_schema",
        "version": 1,
        "fields": [
            {"name": "id", "type": "int", "nullable": False},
            {"name": "name", "type": "string", "nullable": False},
            {"name": "created_at", "type": "datetime", "nullable": True},
        ],
        "primary_key": ["id"],
        "status": "active",
        "description": "Test schema",
    }
    response = await client.post("/api/v1/data/schemas", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "schema_id" in data["data"]


@pytest.mark.asyncio
async def test_data_list_migrations(client: AsyncClient):
    response = await client.get("/api/v1/data/migrations")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
