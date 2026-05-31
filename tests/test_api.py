import pytest
from fastapi import status


class TestHealthEndpoint:
    @pytest.mark.asyncio
    async def test_health_check(self, client):
        response = await client.get("/health")
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["status"] == "healthy"
        assert "app_name" in data
        assert "version" in data

    @pytest.mark.asyncio
    async def test_root_endpoint(self, client):
        response = await client.get("/")
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert "message" in data
        assert "docs" in data


class TestCoreEndpoints:
    @pytest.mark.asyncio
    async def test_create_resource(self, client):
        response = await client.post(
            "/api/v1/core/resources",
            json={
                "type": "workflow",
                "config": {"key": "value"},
                "labels": {"env": "test"},
                "namespace": "default",
            },
        )
        assert response.status_code == status.HTTP_201_CREATED
        data = response.json()
        assert data["code"] == 201
        assert data["data"]["status"] == "active"
        assert "id" in data["data"]

    @pytest.mark.asyncio
    async def test_get_resource_status(self, client):
        response = await client.get("/api/v1/core/resources/test-123/status")
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["code"] == 200
        assert data["data"]["id"] == "test-123"
        assert "status" in data["data"]

    @pytest.mark.asyncio
    async def test_batch_operations(self, client):
        response = await client.post(
            "/api/v1/core/resources/batch",
            json={
                "operations": [
                    {"action": "start", "id": "rsc_001"},
                    {"action": "stop", "id": "rsc_002"},
                ],
                "timeout_seconds": 30,
            },
        )
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["code"] == 200
        assert data["data"]["total_count"] == 2

    @pytest.mark.asyncio
    async def test_execute_task(self, client):
        response = await client.post(
            "/api/v1/core/tasks/execute",
            json={
                "task_type": "test_task",
                "namespace": "default",
                "payload": {"input": "test"},
                "priority": 1,
            },
        )
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["code"] == 200
        assert data["data"]["status"] == "completed"
        assert "task_id" in data["data"]

    @pytest.mark.asyncio
    async def test_list_tasks(self, client):
        response = await client.get("/api/v1/core/tasks")
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["code"] == 200
        assert isinstance(data["data"], list)


class TestMonitoringEndpoints:
    @pytest.mark.asyncio
    async def test_get_metrics(self, client):
        response = await client.get("/api/v1/monitoring/metrics")
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["code"] == 200
        assert "system" in data["data"]
        assert "requests" in data["data"]

    @pytest.mark.asyncio
    async def test_create_snapshot(self, client):
        response = await client.post(
            "/api/v1/monitoring/snapshots",
            json={
                "snapshot_id": "snap_test",
                "metrics": {"throughput": 100, "latency_p99": 50},
                "dimensions": {"host": "test-host"},
            },
        )
        assert response.status_code == status.HTTP_201_CREATED
        data = response.json()
        assert data["code"] == 201

    @pytest.mark.asyncio
    async def test_get_audit_logs(self, client):
        response = await client.get("/api/v1/monitoring/audit-logs")
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["code"] == 200
        assert isinstance(data["data"]["items"], list)


class TestFeatureStoreEndpoints:
    @pytest.mark.asyncio
    async def test_register_feature(self, client):
        response = await client.post(
            "/api/v1/features",
            json={
                "name": "test_feature",
                "namespace": "default",
                "description": "Test feature",
                "value_type": "float",
            },
        )
        assert response.status_code == status.HTTP_201_CREATED
        data = response.json()
        assert data["code"] == 201

    @pytest.mark.asyncio
    async def test_list_features(self, client):
        response = await client.get("/api/v1/features")
        assert response.status_code == status.HTTP_200_OK
        data = response.json()
        assert data["code"] == 200
        assert isinstance(data["data"], list)


class TestAuthEndpoints:
    @pytest.mark.asyncio
    async def test_login(self, client):
        response = await client.post(
            "/api/v1/auth/login",
            data={"username": "testuser", "password": "testpass"},
        )
        assert response.status_code in [status.HTTP_200_OK, status.HTTP_401_UNAUTHORIZED]

    @pytest.mark.asyncio
    async def test_register(self, client):
        response = await client.post(
            "/api/v1/auth/register",
            json={
                "username": "newuser",
                "email": "new@example.com",
                "password": "password123",
            },
        )
        assert response.status_code in [status.HTTP_201_CREATED, status.HTTP_409_CONFLICT]
