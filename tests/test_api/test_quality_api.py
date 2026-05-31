import pytest


def test_validate_data_endpoint(client):
    response = client.post(
        "/api/v1/quality/validate",
        json={
            "table_name": "users",
            "data": [
                {"name": "Alice", "age": 25, "email": "alice@example.com"},
                {"name": None, "age": 30, "email": "invalid"},
                {"name": "Bob", "age": 200, "email": "bob@example.com"},
            ],
            "rules": [
                {"type": "null_check", "column": "name"},
                {"type": "range_check", "column": "age", "params": {"min": 0, "max": 150}},
                {"type": "regex_check", "column": "email", "params": {"pattern": r"^[^@]+@[^@]+\.[^@]+$"}},
            ],
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "validation_result" in data["data"]
    assert data["data"]["validation_result"]["total_rows"] == 3


def test_add_rule_endpoint(client):
    response = client.post(
        "/api/v1/quality/rules",
        json={
            "table_name": "users",
            "rule": {"type": "null_check", "column": "name"},
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "rule_id" in data["data"]


def test_get_rules_endpoint(client):
    response = client.get("/api/v1/quality/rules/users")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "rules" in data["data"]


def test_delete_rule_endpoint(client):
    response = client.delete("/api/v1/quality/rules/users/rule-123")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200


def test_create_scheduled_job_endpoint(client):
    response = client.post(
        "/api/v1/quality/schedule",
        json={
            "name": "daily_quality_check",
            "data_source": "users",
            "rule_configs": [{"type": "null_check", "column": "name"}],
            "schedule_type": "interval",
            "schedule_params": {"interval_seconds": 3600},
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "job_id" in data["data"]


def test_list_scheduled_jobs_endpoint(client):
    response = client.get("/api/v1/quality/schedule")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "jobs" in data["data"]


def test_delete_scheduled_job_endpoint(client):
    response = client.delete("/api/v1/quality/schedule/job-123")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200


def test_get_quality_report_endpoint(client):
    response = client.get("/api/v1/quality/report/users")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "report" in data["data"]


def test_get_quality_score_endpoint(client):
    response = client.get("/api/v1/quality/score/users")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "score" in data["data"]
