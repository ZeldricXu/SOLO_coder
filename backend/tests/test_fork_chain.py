import pytest
import time


def test_fork_snippet_creates_copy(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Original Snippet",
            "description": "To be forked",
            "code": "def original(): pass",
            "language": "python",
            "visibility": "public",
            "tags": ["original", "test"],
        },
        headers=auth_headers,
    )
    assert response.status_code == 201
    original_id = response.json()["id"]

    response = client.post(f"/api/snippets/{original_id}/fork", headers=auth_headers2)
    assert response.status_code == 201
    fork_data = response.json()
    assert fork_data["parent_id"] == original_id
    assert fork_data["parent_title"] == "Original Snippet"
    assert fork_data["parent_author_username"] == "testuser"
    assert fork_data["visibility"] == "private"
    assert fork_data["code"] == "def original(): pass"
    assert "original" in fork_data["tags"]

    response = client.get(f"/api/snippets/{original_id}")
    assert response.json()["forks_count"] == 1


def test_fork_modification_does_not_affect_original(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Original Code",
            "code": "ORIGINAL_CODE",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    original_id = response.json()["id"]

    response = client.post(f"/api/snippets/{original_id}/fork", headers=auth_headers2)
    fork_id = response.json()["id"]

    client.put(
        f"/api/snippets/{fork_id}",
        json={"code": "MODIFIED_CODE"},
        headers=auth_headers2,
    )

    response = client.get(f"/api/snippets/{original_id}", headers=auth_headers)
    assert response.json()["code"] == "ORIGINAL_CODE"

    response = client.get(f"/api/snippets/{fork_id}", headers=auth_headers2)
    assert response.json()["code"] == "MODIFIED_CODE"


def test_fork_list_on_original_snippet(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Popular Snippet",
            "code": "code",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    original_id = response.json()["id"]

    fork_resp = client.post(f"/api/snippets/{original_id}/fork", headers=auth_headers2)
    fork_id = fork_resp.json()["id"]

    client.put(
        f"/api/snippets/{fork_id}",
        json={"visibility": "public"},
        headers=auth_headers2,
    )

    response = client.get(f"/api/snippets/{original_id}/forks")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
    assert len(data) >= 1
    assert data[0]["author_username"] == "testuser2"


def test_parent_updates_detected_by_fork(client, auth_headers, auth_headers2, db):
    from app.models.models import Snippet
    from datetime import datetime, timedelta

    response = client.post(
        "/api/snippets",
        json={
            "title": "Upstream Snippet",
            "code": "v1",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    original_id = response.json()["id"]

    response = client.post(f"/api/snippets/{original_id}/fork", headers=auth_headers2)
    fork_id = response.json()["id"]

    response = client.get(f"/api/snippets/{fork_id}", headers=auth_headers2)
    assert response.json()["parent_has_updates"] is False

    parent = db.query(Snippet).filter(Snippet.id == original_id).first()
    parent.updated_at = datetime.utcnow() + timedelta(days=1)
    db.commit()
    db.expire_all()

    response = client.get(f"/api/snippets/{fork_id}", headers=auth_headers2)
    assert response.json()["parent_has_updates"] is True
    assert response.json()["parent_updated_at"] is not None


def test_deep_fork_chain(client, auth_headers, auth_headers2, db):
    from app.models.models import Snippet
    from datetime import datetime, timedelta

    response = client.post(
        "/api/auth/register",
        json={"username": "testuser3", "email": "test3@example.com", "password": "testpass789"},
    )
    if response.status_code != 200:
        response = client.post(
            "/api/auth/login/json",
            json={"username": "testuser3", "password": "testpass789"},
        )
    assert response.status_code == 200
    token_c = response.json()["access_token"]
    headers_c = {"Authorization": f"Bearer {token_c}"}

    response = client.post(
        "/api/snippets",
        json={
            "title": "Root Snippet",
            "code": "root",
            "language": "python",
            "visibility": "public",
            "tags": ["chain"],
        },
        headers=auth_headers,
    )
    a_id = response.json()["id"]

    response = client.post(f"/api/snippets/{a_id}/fork", headers=auth_headers2)
    b_id = response.json()["id"]

    client.put(
        f"/api/snippets/{b_id}",
        json={"visibility": "public"},
        headers=auth_headers2,
    )

    response = client.post(f"/api/snippets/{b_id}/fork", headers=headers_c)
    assert response.status_code == 201
    c_id = response.json()["id"]

    response = client.get(f"/api/snippets/{c_id}", headers=headers_c)
    c_data = response.json()
    assert c_data["parent_id"] == b_id

    response = client.get(f"/api/snippets/{b_id}", headers=auth_headers2)
    b_data = response.json()
    assert b_data["parent_id"] == a_id

    parent_a = db.query(Snippet).filter(Snippet.id == a_id).first()
    parent_a.updated_at = datetime.utcnow() + timedelta(days=1)
    db.commit()
    db.expire_all()

    response = client.get(f"/api/snippets/{b_id}", headers=auth_headers2)
    assert response.json()["parent_has_updates"] is True


def test_cannot_fork_private_without_permission(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Private Snippet",
            "code": "secret",
            "language": "python",
            "visibility": "private",
            "tags": [],
        },
        headers=auth_headers,
    )
    snippet_id = response.json()["id"]

    response = client.post(f"/api/snippets/{snippet_id}/fork", headers=auth_headers2)
    assert response.status_code == 403


def test_forks_count_incremented(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Fork Count Test",
            "code": "test",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    snippet_id = response.json()["id"]

    assert response.json()["forks_count"] == 0

    client.post(f"/api/snippets/{snippet_id}/fork", headers=auth_headers2)

    response = client.get(f"/api/snippets/{snippet_id}")
    assert response.json()["forks_count"] == 1
