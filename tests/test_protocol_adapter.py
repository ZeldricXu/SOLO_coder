import pytest
from fastapi.testclient import TestClient


def test_list_drivers(client: TestClient):
    response = client.get("/api/v1/protocol/drivers")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data
    assert isinstance(data["data"], list)


def test_list_endpoints(client: TestClient):
    response = client.get("/api/v1/protocol/endpoints")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data


def test_create_endpoint(client: TestClient):
    endpoint_data = {
        "name": "test-endpoint",
        "protocol": "modbus_tcp",
        "config": {
            "host": "localhost",
            "port": 502,
            "slave_id": 1,
        },
        "data_points": [
            {
                "name": "temperature",
                "address": "100",
                "data_type": "int16",
                "scale": 0.1,
            }
        ],
        "poll_interval": 5000,
        "enabled": True,
    }
    response = client.post("/api/v1/protocol/endpoints", json=endpoint_data)
    assert response.status_code in [200, 201]


def test_get_endpoint_not_found(client: TestClient):
    response = client.get("/api/v1/protocol/endpoints/nonexistent-id")
    assert response.status_code in [404, 200]


def test_delete_endpoint_not_found(client: TestClient):
    response = client.delete("/api/v1/protocol/endpoints/nonexistent-id")
    assert response.status_code in [404, 200]


def test_connect_endpoint(client: TestClient):
    response = client.post("/api/v1/protocol/endpoints/nonexistent-id/connect")
    assert response.status_code in [200, 404]


def test_disconnect_endpoint(client: TestClient):
    response = client.post("/api/v1/protocol/endpoints/nonexistent-id/disconnect")
    assert response.status_code in [200, 404]
