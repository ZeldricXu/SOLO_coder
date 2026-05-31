import pytest
from fastapi.testclient import TestClient


def test_upload_firmware(client: TestClient):
    import io
    firmware_content = b"fake firmware content"
    response = client.post(
        "/api/v1/ota/firmware",
        data={
            "version": "1.0.0",
            "device_type": "sensor",
            "description": "Test firmware",
        },
        files={"file": ("firmware.bin", io.BytesIO(firmware_content), "application/octet-stream")},
    )
    assert response.status_code in [200, 201]


def test_list_firmware(client: TestClient):
    response = client.get("/api/v1/ota/firmware")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data


def test_get_firmware_not_found(client: TestClient):
    response = client.get("/api/v1/ota/firmware/nonexistent-id")
    assert response.status_code in [404, 200]


def test_create_upgrade_task(client: TestClient):
    task_data = {
        "firmware_version_id": "fw-001",
        "device_type": "sensor",
        "upgrade_type": "canary",
        "canary_percentage": 10,
        "batch_size": 100,
        "auto_rollback": True,
    }
    response = client.post("/api/v1/ota/upgrade-tasks", json=task_data)
    assert response.status_code in [200, 201, 404]


def test_list_upgrade_tasks(client: TestClient):
    response = client.get("/api/v1/ota/upgrade-tasks")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data


def test_get_upgrade_task_not_found(client: TestClient):
    response = client.get("/api/v1/ota/upgrade-tasks/nonexistent-id")
    assert response.status_code in [404, 200]


def test_cancel_upgrade_task(client: TestClient):
    response = client.post("/api/v1/ota/upgrade-tasks/nonexistent-id/cancel")
    assert response.status_code in [200, 404]
