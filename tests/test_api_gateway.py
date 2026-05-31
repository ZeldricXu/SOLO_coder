import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_gateway_list_protocols(client: AsyncClient):
    response = await client.get("/api/v1/gateway/protocols")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert len(data["data"]) > 0


@pytest.mark.asyncio
async def test_gateway_register_route(client: AsyncClient):
    request = {
        "path": "/api/v1/test-service",
        "methods": ["GET", "POST"],
        "service_name": "test-service",
        "targets": [
            {
                "host": "localhost",
                "port": 8080,
                "protocol": "http",
                "path": "/test",
                "weight": 1,
                "healthy": True,
            }
        ],
        "timeout_seconds": 30,
        "retry_count": 3,
        "protocol_in": "http",
        "protocol_out": "http",
    }
    response = await client.post("/api/v1/gateway/routes", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "route_id" in data["data"]


@pytest.mark.asyncio
async def test_gateway_list_routes(client: AsyncClient):
    response = await client.get("/api/v1/gateway/routes")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


@pytest.mark.asyncio
async def test_gateway_metrics(client: AsyncClient):
    response = await client.get("/api/v1/gateway/metrics")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
