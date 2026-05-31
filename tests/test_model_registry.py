import pytest
import uuid
from httpx import AsyncClient
from src.modules.model_registry import ModelFramework, ModelStage


async def _register_model(client: AsyncClient, name_prefix: str = "test-model"):
    request = {
        "name": f"{name_prefix}-{uuid.uuid4().hex[:8]}",
        "description": "Test model",
        "framework": ModelFramework.PYTORCH.value,
        "framework_version": "2.0.0",
        "tags": ["test", "classification"],
        "labels": {"team": "ml"},
    }
    response = await client.post("/api/v1/model-registry/models", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "model_id" in data["data"]
    return data["data"]["model_id"]


@pytest.mark.asyncio
async def test_register_model(client: AsyncClient):
    model_id = await _register_model(client)
    assert model_id is not None


@pytest.mark.asyncio
async def test_list_models(client: AsyncClient):
    response = await client.get("/api/v1/model-registry/models")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


@pytest.mark.asyncio
async def test_create_version(client: AsyncClient):
    model_id = await _register_model(client)
    request = {
        "model_id": model_id,
        "version": "1.0.0",
        "description": "Initial version",
        "metrics": {"accuracy": 0.95, "f1": 0.93},
        "artifacts_uri": "s3://models/test/v1",
        "signature": {"inputs": [{"name": "input", "type": "float"}]},
        "dependencies": ["torch==2.0.0"],
    }
    response = await client.post("/api/v1/model-registry/versions", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "version_id" in data["data"]


@pytest.mark.asyncio
async def test_get_model_summary(client: AsyncClient):
    model_id = await _register_model(client)
    response = await client.get(f"/api/v1/model-registry/models/{model_id}/summary")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "total_versions" in data["data"]
