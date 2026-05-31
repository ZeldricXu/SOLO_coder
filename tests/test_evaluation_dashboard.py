import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_evaluation_dashboard_summary(client: AsyncClient):
    response = await client.get("/api/v1/evaluation/dashboard")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "total_models" in data["data"]
    assert "avg_accuracy" in data["data"]


@pytest.mark.asyncio
async def test_evaluation_list_metrics(client: AsyncClient):
    response = await client.get("/api/v1/evaluation/metrics")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


@pytest.mark.asyncio
async def test_evaluation_define_metric(client: AsyncClient):
    request = {
        "name": "accuracy",
        "type": "accuracy",
        "description": "Model accuracy",
        "threshold": 0.8,
        "unit": "%",
        "higher_is_better": True,
    }
    response = await client.post("/api/v1/evaluation/metrics", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "metric_id" in data["data"]


@pytest.mark.asyncio
async def test_evaluation_list_evaluations(client: AsyncClient):
    response = await client.get("/api/v1/evaluation/evaluations")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
