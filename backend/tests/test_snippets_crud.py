import pytest


def _create_public_snippet(client, auth_headers, title="Hello World"):
    response = client.post(
        "/api/snippets",
        json={
            "title": title,
            "description": "A simple hello world",
            "code": "print('hello world')",
            "language": "python",
            "visibility": "public",
            "tags": ["python", "hello"],
        },
        headers=auth_headers,
    )
    assert response.status_code == 201
    return response.json()["id"]


def test_create_public_snippet(client, auth_headers):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.get(f"/api/snippets/{snippet_id}", headers=auth_headers)
    data = response.json()
    assert data["title"] == "Hello World"
    assert data["language"] == "python"
    assert data["visibility"] == "public"
    assert data["author_username"] == "testuser"
    assert "python" in data["tags"]
    assert "hello" in data["tags"]
    assert data["is_deleted"] is False


def test_create_private_snippet(client, auth_headers):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Private Snippet",
            "description": "My secret code",
            "code": "secret_code()",
            "language": "python",
            "visibility": "private",
            "tags": ["secret"],
        },
        headers=auth_headers,
    )
    assert response.status_code == 201
    data = response.json()
    assert data["visibility"] == "private"
    assert data["is_deleted"] is False


def test_get_snippet_detail(client, auth_headers):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.get(f"/api/snippets/{snippet_id}", headers=auth_headers)
    assert response.status_code == 200
    data = response.json()
    assert data["id"] == snippet_id
    assert data["code"] == "print('hello world')"
    assert data["views_count"] >= 1


def test_update_snippet(client, auth_headers):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.put(
        f"/api/snippets/{snippet_id}",
        json={
            "title": "Updated Title",
            "code": "print('updated')",
        },
        headers=auth_headers,
    )
    assert response.status_code == 200
    data = response.json()
    assert data["title"] == "Updated Title"
    assert data["code"] == "print('updated')"


def test_delete_snippet(client, auth_headers, db):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.delete(f"/api/snippets/{snippet_id}", headers=auth_headers)
    assert response.status_code == 204

    response = client.get(f"/api/snippets/{snippet_id}", headers=auth_headers)
    assert response.status_code == 404


def test_unauthorized_create_snippet(client):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Test",
            "code": "test",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
    )
    assert response.status_code == 401


def test_cannot_view_other_private_snippet(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "User1 Private",
            "code": "secret",
            "language": "python",
            "visibility": "private",
            "tags": [],
        },
        headers=auth_headers,
    )
    assert response.status_code == 201
    snippet_id = response.json()["id"]

    response = client.get(f"/api/snippets/{snippet_id}", headers=auth_headers2)
    assert response.status_code == 403


def test_public_snippet_visible_to_all(client, auth_headers, test_user2):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.get(f"/api/snippets/{snippet_id}")
    assert response.status_code == 200


def test_update_tags(client, auth_headers):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.put(
        f"/api/snippets/{snippet_id}",
        json={"tags": ["newtag1", "newtag2"]},
        headers=auth_headers,
    )
    assert response.status_code == 200
    data = response.json()
    assert "newtag1" in data["tags"]
    assert "newtag2" in data["tags"]
    assert "python" not in data["tags"]


def test_multiple_languages(client, auth_headers):
    languages = ["python", "javascript", "go", "rust", "java", "typescript", "ruby"]
    for lang in languages:
        response = client.post(
            "/api/snippets",
            json={
                "title": f"{lang} snippet",
                "code": "// test code",
                "language": lang,
                "visibility": "public",
                "tags": [lang],
            },
            headers=auth_headers,
        )
        assert response.status_code == 201
        assert response.json()["language"] == lang


def test_star_snippet(client, auth_headers, auth_headers2):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.post(f"/api/snippets/{snippet_id}/star", headers=auth_headers2)
    assert response.status_code == 200
    data = response.json()
    assert data["starred"] is True
    assert data["stars_count"] == 1

    response = client.post(f"/api/snippets/{snippet_id}/star", headers=auth_headers2)
    assert response.status_code == 200
    data = response.json()
    assert data["starred"] is False
    assert data["stars_count"] == 0


def test_favorite_snippet(client, auth_headers, auth_headers2):
    snippet_id = _create_public_snippet(client, auth_headers)
    response = client.post(f"/api/snippets/{snippet_id}/favorite", headers=auth_headers2)
    assert response.status_code == 200
    assert response.json()["favorited"] is True

    response = client.post(f"/api/snippets/{snippet_id}/favorite", headers=auth_headers2)
    assert response.status_code == 200
    assert response.json()["favorited"] is False


def test_list_snippets_pagination(client, auth_headers):
    for i in range(15):
        client.post(
            "/api/snippets",
            json={
                "title": f"Snippet {i}",
                "code": "test",
                "language": "python",
                "visibility": "public",
                "tags": [],
            },
            headers=auth_headers,
        )
    response = client.get("/api/snippets?page_size=10&page=1")
    assert response.status_code == 200
    data = response.json()
    assert len(data["items"]) == 10
    assert data["total_pages"] >= 2
    assert data["page"] == 1
