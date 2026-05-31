import pytest
from httpx import AsyncClient
from src.modules.feature_store import FeatureType, FeatureValueType


@pytest.mark.asyncio
async def test_feature_store_list_entities(client: AsyncClient):
    response = await client.get("/api/v1/feature-store/entities")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


@pytest.mark.asyncio
async def test_feature_store_register_entity(client: AsyncClient):
    request = {
        "name": "user",
        "description": "User entity",
        "join_keys": ["user_id"],
        "labels": {"domain": "user"},
    }
    response = await client.post("/api/v1/feature-store/entities", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201


@pytest.mark.asyncio
async def test_feature_store_list_features(client: AsyncClient):
    response = await client.get("/api/v1/feature-store/features")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
