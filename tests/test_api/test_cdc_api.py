import pytest


def test_start_cdc_capture_endpoint(client, mock_data_source):
    response = client.post(
        "/api/v1/cdc/start",
        json={
            "data_source": mock_data_source,
            "output_type": "console",
            "tables": ["users", "orders"],
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "capture_id" in data["data"]
    assert data["data"]["status"] == "running"


def test_stop_cdc_capture_endpoint(client):
    response = client.post("/api/v1/cdc/stop/test-capture-id")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200


def test_get_cdc_status_endpoint(client):
    response = client.get("/api/v1/cdc/status/test-capture-id")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "status" in data["data"]


def test_list_cdc_captures_endpoint(client):
    response = client.get("/api/v1/cdc/list")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "captures" in data["data"]
