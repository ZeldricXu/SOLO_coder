import pytest


def test_parse_sql_endpoint(client, sample_sql_query):
    response = client.post(
        "/api/v1/query/parse",
        json={"sql": sample_sql_query},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "parsed" in data["data"]
    assert "tables" in data["data"]["parsed"]


def test_generate_logical_plan_endpoint(client, sample_sql_query):
    response = client.post(
        "/api/v1/query/logical-plan",
        json={"sql": sample_sql_query},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "logical_plan" in data["data"]


def test_generate_physical_plan_endpoint(client, sample_sql_query):
    response = client.post(
        "/api/v1/query/physical-plan",
        json={"sql": sample_sql_query, "mode": "streaming"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "physical_plan" in data["data"]
    assert "operators" in data["data"]["physical_plan"]


def test_optimize_plan_endpoint(client, sample_sql_query):
    response = client.post(
        "/api/v1/query/optimize",
        json={"sql": sample_sql_query},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "optimized_plan" in data["data"]


def test_execute_query_endpoint(client, sample_sql_query):
    response = client.post(
        "/api/v1/query/execute",
        json={"sql": sample_sql_query},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "result" in data["data"]
