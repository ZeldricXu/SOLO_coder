import pytest
import pytest_asyncio

from core.utils import (
    generate_id,
    utc_now,
    validate_params,
    calculate_hash,
    mask_sensitive_data,
)
from core.exceptions import ValidationError


def test_generate_id():
    id1 = generate_id("test")
    id2 = generate_id("test")

    assert id1.startswith("test_")
    assert id2.startswith("test_")
    assert id1 != id2
    assert len(id1) > 8


def test_utc_now():
    now1 = utc_now()
    now2 = utc_now()

    assert now1.tzinfo is not None
    assert now1 <= now2


def test_validate_params_success():
    params = {"name": "test", "age": 25}
    rules = {
        "name": lambda x: x is not None and len(x) > 0,
        "age": lambda x: x is not None and x >= 0,
    }

    validate_params(params, rules)


def test_validate_params_failure():
    params = {"name": "", "age": -1}
    rules = {
        "name": lambda x: x is not None and len(x) > 0,
        "age": lambda x: x is not None and x >= 0,
    }

    with pytest.raises(ValidationError) as exc_info:
        validate_params(params, rules)

    assert "name" in exc_info.value.details
    assert "age" in exc_info.value.details


def test_calculate_hash():
    data = {"key": "value", "number": 123}
    hash1 = calculate_hash(data)
    hash2 = calculate_hash(data)

    assert hash1 == hash2
    assert len(hash1) == 64


def test_mask_sensitive_data():
    data = {
        "password": "secret123",
        "token": "abc123",
        "name": "张三",
        "email": "test@example.com",
    }

    masked = mask_sensitive_data(data)

    assert masked["password"] == "***"
    assert masked["token"] == "***"
    assert masked["name"] == "张三"
    assert masked["email"] == "test@example.com"


def test_mask_sensitive_data_nested():
    data = {
        "user": {
            "password": "secret",
            "name": "test",
        },
        "api_key": "key123",
    }

    masked = mask_sensitive_data(data)

    assert masked["user"]["password"] == "***"
    assert masked["user"]["name"] == "test"
    assert masked["api_key"] == "***"


def test_app_health(test_client):
    response = test_client.get("/health")

    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert data["data"]["status"] == "healthy"


def test_app_root(test_client):
    response = test_client.get("/")

    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert data["data"]["status"] == "running"


def test_create_resource(test_client):
    payload = {
        "type": "task",
        "config": {"timeout": 30},
        "labels": {"priority": "high"},
        "tenant_id": "tnt_001",
    }

    response = test_client.post("/api/v1/resources", json=payload)

    assert response.status_code == 201
    data = response.json()
    assert data["code"] == 201
    assert data["data"]["status"] == "provisioning"
    assert "id" in data["data"]


def test_get_resource_status(test_client):
    create_payload = {
        "type": "task",
        "config": {},
        "tenant_id": "tnt_001",
    }
    create_response = test_client.post("/api/v1/resources", json=create_payload)
    resource_id = create_response.json()["data"]["id"]

    response = test_client.get(f"/api/v1/resources/{resource_id}/status")

    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert data["data"]["id"] == resource_id


def test_get_resource_status_not_found(test_client):
    response = test_client.get("/api/v1/resources/non_existent/status")

    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 404
    assert data["data"]["status"] == "not_found"


def test_batch_operations(test_client):
    create_payload = {
        "type": "task",
        "config": {},
        "tenant_id": "tnt_001",
    }
    create_response = test_client.post("/api/v1/resources", json=create_payload)
    resource_id = create_response.json()["data"]["id"]

    batch_payload = {
        "operations": [
            {"action": "restart", "id": resource_id},
            {"action": "stop", "id": resource_id},
            {"action": "delete", "id": "non_existent"},
        ]
    }

    response = test_client.post("/api/v1/resources/batch", json=batch_payload)

    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "batch_id" in data["data"]
    assert len(data["data"]["results"]) == 3
    assert data["data"]["success_count"] >= 2
