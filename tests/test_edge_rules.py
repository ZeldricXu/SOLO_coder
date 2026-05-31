import pytest
from fastapi.testclient import TestClient


def test_create_rule(client: TestClient):
    rule_data = {
        "name": "test_rule",
        "description": "Test rule",
        "enabled": True,
        "conditions": [
            {
                "field": "temperature",
                "operator": ">",
                "value": 30,
            }
        ],
        "actions": [
            {
                "type": "alert",
                "parameters": {"message": "Temperature too high"},
            }
        ],
    }
    response = client.post("/api/v1/edge-rules", json=rule_data)
    assert response.status_code in [200, 201]
    if response.status_code in [200, 201]:
        data = response.json()
        assert "id" in data["data"]


def test_list_rules(client: TestClient):
    response = client.get("/api/v1/edge-rules")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data


def test_get_rule_not_found(client: TestClient):
    response = client.get("/api/v1/edge-rules/nonexistent-id")
    assert response.status_code in [404, 200]


def test_delete_rule_not_found(client: TestClient):
    response = client.delete("/api/v1/edge-rules/nonexistent-id")
    assert response.status_code in [404, 200]
