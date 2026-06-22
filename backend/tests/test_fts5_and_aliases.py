from urllib.parse import quote


def test_fts5_weighted_ranking_title_over_code(client, auth_headers):
    title_match = client.post("/api/snippets", json={
        "title": "Parse Configuration Files",
        "description": "Utility for configuration parsing",
        "code": "data = load_file(path)",
        "language": "python",
        "visibility": "public",
        "tags": ["config"],
    }, headers=auth_headers)
    assert title_match.status_code == 201

    code_match = client.post("/api/snippets", json={
        "title": "File Loader Utility",
        "description": "General file loading helper",
        "code": "def parse(line):\n    return line.strip().split(',')",
        "language": "python",
        "visibility": "public",
        "tags": ["utility"],
    }, headers=auth_headers)
    assert code_match.status_code == 201

    title_id = title_match.json()["id"]
    code_id = code_match.json()["id"]

    response = client.get("/api/search?q=parse&sort_by=relevance&sort_order=desc", headers=auth_headers)
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 2

    ids = [item["id"] for item in data["items"]]
    title_pos = ids.index(title_id) if title_id in ids else len(ids)
    code_pos = ids.index(code_id) if code_id in ids else len(ids)
    assert title_pos < code_pos, (
        f"Snippet with 'parse' in title (id={title_id}) should rank higher than "
        f"snippet with 'parse' only in code (id={code_id})"
    )


def test_fts5_search_stars_boost(client, auth_headers, auth_headers2):
    s1 = client.post("/api/snippets", json={
        "title": "HTTP Handler Alpha",
        "description": "Alpha handler implementation",
        "code": "def handler_alpha(): pass",
        "language": "python",
        "visibility": "public",
        "tags": ["handler"],
    }, headers=auth_headers)
    assert s1.status_code == 201

    s2 = client.post("/api/snippets", json={
        "title": "HTTP Handler Beta",
        "description": "Beta handler implementation",
        "code": "def handler_beta(): pass",
        "language": "python",
        "visibility": "public",
        "tags": ["handler"],
    }, headers=auth_headers)
    assert s2.status_code == 201

    s2_id = s2.json()["id"]
    client.post(f"/api/snippets/{s2_id}/star", headers=auth_headers)
    client.post(f"/api/snippets/{s2_id}/star", headers=auth_headers2)

    response = client.get("/api/search?q=handler&sort_by=relevance&sort_order=desc", headers=auth_headers)
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 2

    ids = [item["id"] for item in data["items"]]
    assert ids[0] == s2_id, "Starred snippet should rank first with relevance sort"


def test_tag_alias_creation_and_resolution(client, auth_headers):
    snippet1 = client.post("/api/snippets", json={
        "title": "Docker Compose Setup",
        "description": "Docker compose configuration",
        "code": "version: '3'\nservices:\n  web:\n    image: nginx",
        "language": "yaml",
        "visibility": "public",
        "tags": ["docker"],
    }, headers=auth_headers)
    assert snippet1.status_code == 201

    alias_resp = client.post(
        "/api/search/tags/aliases?alias=container&canonical_tag=docker",
        headers=auth_headers,
    )
    assert alias_resp.status_code == 201
    assert alias_resp.json()["alias"] == "container"
    assert alias_resp.json()["canonical_tag"] == "docker"

    aliases_resp = client.get("/api/search/tags/aliases")
    assert aliases_resp.status_code == 200
    aliases = aliases_resp.json()
    assert any(a["alias"] == "container" and a["canonical_tag"] == "docker" for a in aliases)

    snippet2 = client.post("/api/snippets", json={
        "title": "Container Orchestration",
        "description": "Container orchestration guide",
        "code": "kubectl apply -f deployment.yaml",
        "language": "bash",
        "visibility": "public",
        "tags": ["container"],
    }, headers=auth_headers)
    assert snippet2.status_code == 201

    snippet2_tags = snippet2.json()["tags"]
    assert "docker" in snippet2_tags, (
        f"Tag 'container' should be resolved to canonical 'docker', got {snippet2_tags}"
    )

    search_resp = client.get("/api/search?tag=container", headers=auth_headers)
    assert search_resp.status_code == 200
    search_data = search_resp.json()
    found_ids = [item["id"] for item in search_data["items"]]
    assert snippet1.json()["id"] in found_ids
    assert snippet2.json()["id"] in found_ids


def test_tag_alias_deletion(client, auth_headers):
    client.post("/api/snippets", json={
        "title": "K8s Deploy",
        "description": "Kubernetes deployment",
        "code": "kubectl get pods",
        "language": "bash",
        "visibility": "public",
        "tags": ["kubernetes"],
    }, headers=auth_headers)

    alias_resp = client.post(
        "/api/search/tags/aliases?alias=k8s&canonical_tag=kubernetes",
        headers=auth_headers,
    )
    assert alias_resp.status_code == 201

    aliases_before = client.get("/api/search/tags/aliases").json()
    alias_entry = next(a for a in aliases_before if a["alias"] == "k8s")
    alias_id = alias_entry["id"]

    delete_resp = client.delete(
        f"/api/search/tags/aliases/{alias_id}",
        headers=auth_headers,
    )
    assert delete_resp.status_code == 204

    aliases_after = client.get("/api/search/tags/aliases").json()
    assert not any(a["alias"] == "k8s" for a in aliases_after)


def test_tag_suggestion_with_alias(client, auth_headers):
    client.post("/api/snippets", json={
        "title": "Networking Basics",
        "description": "Network fundamentals",
        "code": "import socket\ns = socket.socket()",
        "language": "python",
        "visibility": "public",
        "tags": ["networking"],
    }, headers=auth_headers)

    client.post("/api/snippets", json={
        "title": "Network Utils",
        "description": "Network utility functions",
        "code": "import urllib.request",
        "language": "python",
        "visibility": "public",
        "tags": ["network"],
    }, headers=auth_headers)

    alias_resp = client.post(
        "/api/search/tags/aliases?alias=net&canonical_tag=network",
        headers=auth_headers,
    )
    assert alias_resp.status_code == 201

    suggest_resp = client.get("/api/search/tags/suggest?q=net")
    assert suggest_resp.status_code == 200
    suggestions = suggest_resp.json()
    assert len(suggestions) > 0

    network_suggestion = next(
        (s for s in suggestions if s["name"] == "network"),
        None,
    )
    assert network_suggestion is not None, f"Expected 'network' in suggestions, got {suggestions}"
    assert network_suggestion["is_alias"] is True
    assert "net" in network_suggestion["aliases"]


def test_search_special_characters_fts5_safety(client, auth_headers):
    client.post("/api/snippets", json={
        "title": "Safe Snippet",
        "description": "For special character testing",
        "code": "print('hello world')",
        "language": "python",
        "visibility": "public",
        "tags": ["safety"],
    }, headers=auth_headers)

    special_queries = [
        '"quoted"',
        '"; DROP TABLE snippets;--',
        "<script>alert(1)</script>",
        "'; SELECT * FROM users;--",
        "test&value=1",
        "a | b",
        "foo && bar",
    ]
    for query in special_queries:
        encoded = quote(query)
        response = client.get(f"/api/search?q={encoded}", headers=auth_headers)
        assert response.status_code == 200, (
            f"Query {query!r} (encoded: {encoded!r}) returned {response.status_code}"
        )


def test_rendered_html_on_snippet_creation(client, auth_headers):
    resp = client.post("/api/snippets", json={
        "title": "Rendered HTML Test",
        "description": "Test pre-rendered HTML",
        "code": "def hello():\n    print('Hello, World!')",
        "language": "python",
        "visibility": "public",
        "tags": ["test"],
    }, headers=auth_headers)
    assert resp.status_code == 201

    snippet_id = resp.json()["id"]
    detail_resp = client.get(f"/api/snippets/{snippet_id}", headers=auth_headers)
    assert detail_resp.status_code == 200

    rendered_html = detail_resp.json()["rendered_html"]
    assert rendered_html is not None, "rendered_html should not be null"
    assert "<pre>" in rendered_html, f"rendered_html should contain <pre> tag, got: {rendered_html[:200]}"
