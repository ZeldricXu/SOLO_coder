import pytest
import json


def register_and_login(client, username, password, email):
    response = client.post(
        "/api/auth/register",
        json={
            "username": username,
            "email": email,
            "password": password,
        },
    )
    if response.status_code != 200:
        response = client.post(
            "/api/auth/login/json",
            json={"username": username, "password": password},
        )
    assert response.status_code == 200
    token = response.json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


def test_full_user_journey(client, db):
    alice_headers = register_and_login(client, "alice", "alice123", "alice@example.com")
    bob_headers = register_and_login(client, "bob", "bob456", "bob@example.com")

    snippet_data = {
        "title": "Python Data Processing Pipeline",
        "description": "A reusable data processing pipeline with error handling",
        "code": """import logging
from typing import List, Callable

logger = logging.getLogger(__name__)

def process_data(data: List[dict], pipeline: List[Callable]) -> List[dict]:
    result = []
    for item in data:
        try:
            for step in pipeline:
                item = step(item)
            result.append(item)
        except Exception as e:
            logger.error(f"Processing failed: {e}")
    return result

def validate_item(item: dict) -> dict:
    if "id" not in item:
        raise ValueError("Missing id field")
    return item""",
        "language": "python",
        "visibility": "public",
        "tags": ["python", "data", "pipeline", "utility"],
    }
    response = client.post("/api/snippets", json=snippet_data, headers=alice_headers)
    assert response.status_code == 201
    snippet_id = response.json()["id"]
    assert response.json()["author_username"] == "alice"
    assert set(response.json()["tags"]) == {"python", "data", "pipeline", "utility"}

    response = client.get("/api/search?q=process_data")
    assert response.status_code == 200
    search_results = response.json()["items"]
    found = any(s["id"] == snippet_id for s in search_results)
    assert found

    response = client.get("/api/search?tags=python&tags=utility")
    assert response.status_code == 200
    tag_results = response.json()["items"]
    found = any(s["id"] == snippet_id for s in tag_results)
    assert found
    for s in tag_results:
        assert "python" in s["tags"]
        assert "utility" in s["tags"]

    response = client.get("/api/search?language=javascript")
    assert response.status_code == 200
    js_results = response.json()["items"]
    found = any(s["id"] == snippet_id for s in js_results)
    assert not found

    response = client.post(f"/api/snippets/{snippet_id}/fork", headers=bob_headers)
    assert response.status_code == 201
    fork_id = response.json()["id"]
    assert response.json()["parent_id"] == snippet_id
    assert response.json()["parent_author_username"] == "alice"
    assert response.json()["visibility"] == "private"

    client.put(
        f"/api/snippets/{fork_id}",
        json={"visibility": "public"},
        headers=bob_headers,
    )

    response = client.get(f"/api/snippets/{snippet_id}/forks")
    assert response.status_code == 200
    forks = response.json()
    assert len(forks) >= 1

    response = client.get(f"/api/snippets/{snippet_id}")
    assert response.json()["forks_count"] == 1

    comment_response = client.post(
        f"/api/snippets/{snippet_id}/comments",
        json={"content": "Great snippet! Very useful for my project."},
        headers=bob_headers,
    )
    assert comment_response.status_code == 201
    comment_id = comment_response.json()["id"]
    assert comment_response.json()["author_username"] == "bob"

    response = client.get(f"/api/snippets/{snippet_id}/comments")
    assert response.status_code == 200
    comments = response.json()
    assert len(comments) >= 1
    assert any(c["id"] == comment_id for c in comments)

    ext_snippet = {
        "title": "Scraped Code Example",
        "description": "Saved from a webpage via extension",
        "code": "function debounce(fn, delay) {\n    let timer = null;\n    return function(...args) {\n        clearTimeout(timer);\n        timer = setTimeout(() => fn.apply(this, args), delay);\n    };\n}",
        "language": "javascript",
        "visibility": "public",
        "tags": ["javascript", "utility"],
    }
    response = client.post("/api/snippets", json=ext_snippet, headers=bob_headers)
    assert response.status_code == 201
    ext_snippet_id = response.json()["id"]

    response = client.get("/api/search?q=debounce")
    assert response.status_code == 200
    ext_results = response.json()["items"]
    found = any(s["id"] == ext_snippet_id for s in ext_results)
    assert found

    response = client.get(f"/api/snippets/{snippet_id}")
    assert response.status_code == 200
    assert response.json()["views_count"] >= 1

    response = client.post(f"/api/snippets/{snippet_id}/star", headers=bob_headers)
    assert response.status_code == 200
    assert response.json()["starred"] is True

    response = client.get(f"/api/snippets/{snippet_id}")
    assert response.json()["stars_count"] == 1


def test_complete_fork_chain(client, db):
    from app.models.models import Snippet
    from datetime import datetime, timedelta

    a_headers = register_and_login(client, "charlie", "charlie123", "charlie@example.com")
    b_headers = register_and_login(client, "dave", "dave456", "dave@example.com")
    c_headers = register_and_login(client, "eve", "eve789", "eve@example.com")

    response = client.post(
        "/api/snippets",
        json={
            "title": "Master Config Template",
            "description": "Base configuration template for all services",
            "code": "VERSION = '1.0.0'\nDEBUG = False\nMAX_RETRIES = 3",
            "language": "python",
            "visibility": "public",
            "tags": ["config", "template"],
        },
        headers=a_headers,
    )
    assert response.status_code == 201
    a_id = response.json()["id"]

    response = client.post(f"/api/snippets/{a_id}/fork", headers=b_headers)
    assert response.status_code == 201
    b_id = response.json()["id"]
    assert response.json()["parent_id"] == a_id

    client.put(
        f"/api/snippets/{b_id}",
        json={"visibility": "public"},
        headers=b_headers,
    )

    response = client.post(f"/api/snippets/{b_id}/fork", headers=c_headers)
    assert response.status_code == 201
    c_id = response.json()["id"]
    assert response.json()["parent_id"] == b_id

    client.put(
        f"/api/snippets/{c_id}",
        json={"visibility": "public"},
        headers=c_headers,
    )

    parent_a = db.query(Snippet).filter(Snippet.id == a_id).first()
    fork_b = db.query(Snippet).filter(Snippet.id == b_id).first()
    fork_c = db.query(Snippet).filter(Snippet.id == c_id).first()
    base_time = datetime.utcnow()
    parent_a.updated_at = base_time - timedelta(hours=2)
    fork_b.updated_at = base_time - timedelta(hours=1)
    fork_c.updated_at = base_time
    db.commit()
    db.expire_all()

    response = client.get(f"/api/snippets/{b_id}", headers=b_headers)
    assert response.json()["parent_has_updates"] is False

    response = client.get(f"/api/snippets/{c_id}", headers=c_headers)
    assert response.json()["parent_has_updates"] is False

    parent_a.updated_at = base_time + timedelta(hours=1)
    db.commit()
    db.expire_all()

    response = client.get(f"/api/snippets/{b_id}", headers=b_headers)
    b_data = response.json()
    assert b_data["parent_has_updates"] is True
    assert b_data["parent_updated_at"] is not None

    response = client.get(f"/api/snippets/{c_id}", headers=c_headers)
    c_data = response.json()
    assert c_data["parent_id"] == b_id
    assert c_data["parent_has_updates"] is False

    fork_b.updated_at = base_time + timedelta(hours=2)
    db.commit()
    db.expire_all()

    response = client.get(f"/api/snippets/{c_id}", headers=c_headers)
    c_data = response.json()
    assert c_data["parent_has_updates"] is True

    response = client.get(f"/api/snippets/{a_id}/forks")
    assert response.status_code == 200
    a_forks = response.json()
    direct_forks = [f for f in a_forks if f["author_username"] == "dave"]
    assert len(direct_forks) >= 1

    response = client.get(f"/api/snippets/{b_id}/forks")
    assert response.status_code == 200
    b_forks = response.json()
    assert len(b_forks) >= 1


def test_team_visibility_workflow(client, db):
    owner_headers = register_and_login(client, "frank", "frank123", "frank@example.com")
    member_headers = register_and_login(client, "grace", "grace456", "grace@example.com")
    outsider_headers = register_and_login(client, "heidi", "heidi789", "heidi@example.com")

    response = client.post(
        "/api/teams",
        json={"name": "engineering-team", "description": "Engineering team snippets"},
        headers=owner_headers,
    )
    team_id = response.json()["id"]

    response = client.post(
        f"/api/teams/{team_id}/members?username=grace",
        headers=owner_headers,
    )

    response = client.post(
        "/api/snippets",
        json={
            "title": "Team Internal Tool",
            "code": "def internal_tool(): pass",
            "language": "python",
            "visibility": "team",
            "team_id": team_id,
            "tags": ["internal"],
        },
        headers=owner_headers,
    )
    assert response.status_code == 201
    snippet_id = response.json()["id"]

    response = client.get(f"/api/snippets/{snippet_id}", headers=owner_headers)
    assert response.status_code == 200

    response = client.get(f"/api/snippets/{snippet_id}", headers=member_headers)
    assert response.status_code == 200

    response = client.get(f"/api/snippets/{snippet_id}", headers=outsider_headers)
    assert response.status_code == 403

    response = client.get(f"/api/snippets/{snippet_id}")
    assert response.status_code == 403

    response = client.get("/api/search?q=internal_tool")
    public_results = response.json()["items"]
    found_public = any(s["id"] == snippet_id for s in public_results)
    assert not found_public

    response = client.get("/api/search?q=internal_tool", headers=member_headers)
    member_results = response.json()["items"]
    found_member = any(s["id"] == snippet_id for s in member_results)
    assert found_member
