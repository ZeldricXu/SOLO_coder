import pytest


@pytest.mark.asyncio
async def test_health_check(client):
    response = await client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert "timestamp" in data
    assert "version" in data


@pytest.mark.asyncio
async def test_root_endpoint(client):
    response = await client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "running"
    assert data["docs"] == "/docs"


@pytest.mark.asyncio
async def test_ready_check(client):
    response = await client.get("/ready")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ready"
    assert "services" in data


@pytest.mark.asyncio
async def test_create_resource(client):
    response = await client.post(
        "/api/v1/resources",
        json={
            "type": "workflow",
            "config": {"timeout": 60},
            "labels": {"env": "test"}
        }
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "data" in data
    assert data["data"]["status"] == "provisioning"
    resource_id = data["data"]["id"]
    assert resource_id.startswith("rsc_")


@pytest.mark.asyncio
async def test_get_resource_status(client):
    create_resp = await client.post(
        "/api/v1/resources",
        json={
            "type": "service",
            "config": {},
            "labels": {}
        }
    )
    resource_id = create_resp.json()["data"]["id"]

    response = await client.get(f"/api/v1/resources/{resource_id}/status")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert data["data"]["id"] == resource_id


@pytest.mark.asyncio
async def test_get_resource_not_found(client):
    response = await client.get("/api/v1/resources/nonexistent/status")
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_batch_operations(client):
    create_resp = await client.post(
        "/api/v1/resources",
        json={
            "type": "service",
            "config": {},
            "labels": {}
        }
    )
    resource_id = create_resp.json()["data"]["id"]

    response = await client.post(
        "/api/v1/resources/batch",
        json={
            "operations": [
                {"action": "stop", "id": resource_id},
                {"action": "delete", "id": "nonexistent"}
            ]
        }
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "data" in data
    assert "batch_id" in data["data"]
    assert len(data["data"]["results"]) == 2


@pytest.mark.asyncio
async def test_get_config(client):
    response = await client.get("/api/v1/config/default")
    assert response.status_code == 200
    data = response.json()
    assert data["namespace"] == "default"
    assert "config" in data


@pytest.mark.asyncio
async def test_get_metrics(client):
    response = await client.get("/api/v1/metrics")
    assert response.status_code == 200
    data = response.json()
    assert "counters" in data
    assert "gauges" in data
    assert "histograms" in data


@pytest.mark.asyncio
async def test_get_metrics_prometheus(client):
    response = await client.get("/api/v1/metrics/prometheus")
    assert response.status_code == 200
    content = response.text
    assert "TYPE" in content or "HELP" in content or content == ""


@pytest.mark.asyncio
async def test_list_entities(client):
    response = await client.get("/api/v1/entities")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
    if len(data) > 0:
        entity = data[0]
        assert "id" in entity
        assert "type" in entity
        assert "status" in entity


@pytest.mark.asyncio
async def test_list_configs(client):
    response = await client.get("/api/v1/configs")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
