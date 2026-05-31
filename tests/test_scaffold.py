from __future__ import annotations


def test_list_templates(client):
    response = client.get("/api/v1/scaffold/templates")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)
    assert len(data["data"]) >= 3


def test_generate_project(client):
    response = client.post(
        "/api/v1/scaffold/generate",
        json={
            "template_id": "fastapi-basic",
            "name": "test-project",
            "description": "Test project",
            "author": "Test Author",
            "email": "test@example.com",
            "variables": {
                "project_name": "test-project",
                "database": "none",
                "include_auth": False,
                "include_docker": False,
            },
        },
    )
    assert response.status_code == 201
    data = response.json()
    assert data["code"] == 201
    assert data["data"]["success"] is True
