import pytest


def test_upload_snippet_over_size_limit(client, auth_headers):
    large_code = "x" * (600 * 1024)
    response = client.post(
        "/api/snippets",
        json={
            "title": "Large Snippet",
            "code": large_code,
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    assert response.status_code == 413
    data = response.json()
    assert "detail" in data
    assert "too large" in data["detail"].lower()


def test_update_snippet_over_size_limit(client, auth_headers):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Normal Snippet",
            "code": "small code",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    snippet_id = response.json()["id"]

    large_code = "y" * (700 * 1024)
    response = client.put(
        f"/api/snippets/{snippet_id}",
        json={"code": large_code},
        headers=auth_headers,
    )
    assert response.status_code == 413


def test_search_special_characters_safe(client, auth_headers):
    special_cases = [
        ("'", 200),
        ('"', 200),
        ("`", 200),
        ("; DELETE FROM users;--", 200),
        ("UNION SELECT 1,2,3--", 200),
        ("<img src=x onerror=alert(1)>", 200),
        ("{{ 7*7 }}", 200),
        ("${7*7}", 200),
        ("../../../../etc/passwd", 200),
        ("", 200),
        ("?", 200),
        ("*", 200),
        (".", 200),
        ("[", 200),
        ("]", 200),
        ("()", 200),
    ]
    from urllib.parse import quote
    for query, expected_status in special_cases:
        encoded = quote(query)
        response = client.get(f"/api/search?q={encoded}")
        assert response.status_code == expected_status, f"Failed for query: {query}"


def test_delete_snippet_with_forks_soft_delete(client, auth_headers, auth_headers2, db):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Snippet with Forks",
            "code": "original",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    original_id = response.json()["id"]

    response = client.post(f"/api/snippets/{original_id}/fork", headers=auth_headers2)
    fork_id = response.json()["id"]
    assert fork_id is not None

    response = client.delete(f"/api/snippets/{original_id}", headers=auth_headers)
    assert response.status_code == 204

    response = client.get(f"/api/snippets/{original_id}")
    assert response.status_code == 404

    response = client.get(f"/api/snippets/{fork_id}", headers=auth_headers2)
    assert response.status_code == 200
    data = response.json()
    assert data["parent_id"] == original_id

    response = client.get("/api/snippets")
    snippet_ids = [s["id"] for s in response.json()["items"]]
    assert original_id not in snippet_ids


def test_delete_forked_snippet_updates_parent_count(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Parent Snippet",
            "code": "parent",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    parent_id = response.json()["id"]

    response = client.post(f"/api/snippets/{parent_id}/fork", headers=auth_headers2)
    fork_id = response.json()["id"]

    response = client.get(f"/api/snippets/{parent_id}")
    assert response.json()["forks_count"] == 1

    client.delete(f"/api/snippets/{fork_id}", headers=auth_headers2)

    response = client.get(f"/api/snippets/{parent_id}")
    assert response.json()["forks_count"] == 0


def test_invalid_visibility_type(client, auth_headers):
    response = client.post(
        "/api/snippets",
        json={
            "title": "Invalid Visibility",
            "code": "test",
            "language": "python",
            "visibility": "invalid",
            "tags": [],
        },
        headers=auth_headers,
    )
    assert response.status_code == 400


def test_get_nonexistent_snippet(client):
    response = client.get("/api/snippets/99999")
    assert response.status_code == 404


def test_update_nonexistent_snippet(client, auth_headers):
    response = client.put(
        "/api/snippets/99999",
        json={"title": "test"},
        headers=auth_headers,
    )
    assert response.status_code == 404


def test_delete_nonexistent_snippet(client, auth_headers):
    response = client.delete("/api/snippets/99999", headers=auth_headers)
    assert response.status_code == 404


def test_cannot_edit_others_snippet(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "User1 Snippet",
            "code": "test",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    snippet_id = response.json()["id"]

    response = client.put(
        f"/api/snippets/{snippet_id}",
        json={"title": "Hacked!"},
        headers=auth_headers2,
    )
    assert response.status_code == 403


def test_cannot_delete_others_snippet(client, auth_headers, auth_headers2):
    response = client.post(
        "/api/snippets",
        json={
            "title": "User1 Snippet",
            "code": "test",
            "language": "python",
            "visibility": "public",
            "tags": [],
        },
        headers=auth_headers,
    )
    snippet_id = response.json()["id"]

    response = client.delete(f"/api/snippets/{snippet_id}", headers=auth_headers2)
    assert response.status_code == 403


def test_invalid_page_size(client):
    response = client.get("/api/snippets?page_size=0")
    assert response.status_code == 422

    response = client.get("/api/snippets?page_size=1000")
    assert response.status_code == 422
