import pytest
from fastapi.testclient import TestClient


def test_register_device(client: TestClient):
    device_data = {
        "device_id": "test-device-001",
        "name": "Test Device",
        "device_model": "sensor-v1",
        "metadata": {"location": "room1"},
    }
    response = client.post("/api/v1/devices", json=device_data)
    assert response.status_code in [200, 201]


def test_list_devices(client: TestClient):
    response = client.get("/api/v1/devices")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data


def test_activate_device(client: TestClient):
    activate_data = {
        "device_key": "test-device-001",
        "activation_code": "test-code",
    }
    response = client.post("/api/v1/devices/activate", json=activate_data)
    assert response.status_code in [200, 404]


def test_device_heartbeat(client: TestClient):
    heartbeat_data = {
        "device_id": "test-device-001",
        "status": "online",
        "metrics": {"battery": 85},
    }
    response = client.post("/api/v1/devices/heartbeat", json=heartbeat_data)
    assert response.status_code in [200, 404]
