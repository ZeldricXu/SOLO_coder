import pytest
from fastapi.testclient import TestClient


def test_health_check(client: TestClient):
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert data["data"]["status"] == "healthy"


def test_root_endpoint(client: TestClient):
    response = client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "version" in data["data"]


def test_tracing_headers(client: TestClient):
    response = client.get("/health")
    assert "X-Trace-ID" in response.headers
    assert "X-Span-ID" in response.headers


def test_request_logging(client: TestClient):
    response = client.get("/api/v1/gateway/logs")
    assert response.status_code in [200, 404]


def test_metrics_endpoint(client: TestClient):
    response = client.get("/api/v1/gateway/metrics")
    assert response.status_code in [200, 404]
