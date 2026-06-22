import pytest


def _create_test_snippets(client, auth_headers):
    snippets = [
        {
            "title": "Python Utility Functions",
            "description": "Collection of utility functions",
            "code": "def parse_date(date_str):\n    return datetime.strptime(date_str, '%Y-%m-%d')\n\ndef format_number(n):\n    return f'{n:,}'",
            "language": "python",
            "visibility": "public",
            "tags": ["python", "utility", "date"],
        },
        {
            "title": "JavaScript Date Parser",
            "description": "Date parsing in JS",
            "code": "function parseDate(dateStr) {\n    return new Date(dateStr);\n}\n\nfunction formatNumber(n) {\n    return n.toLocaleString();\n}",
            "language": "javascript",
            "visibility": "public",
            "tags": ["javascript", "utility", "date"],
        },
        {
            "title": "Go HTTP Server",
            "description": "Simple HTTP server in Go",
            "code": "func main() {\n    http.HandleFunc('/', handler)\n    http.ListenAndServe(':8080', nil)\n}",
            "language": "go",
            "visibility": "public",
            "tags": ["go", "http", "server"],
        },
        {
            "title": "Python HTTP Client",
            "description": "HTTP client using requests",
            "code": "import requests\n\ndef fetch_data(url):\n    resp = requests.get(url)\n    return resp.json()",
            "language": "python",
            "visibility": "public",
            "tags": ["python", "http", "client"],
        },
    ]
    ids = []
    for s in snippets:
        response = client.post("/api/snippets", json=s, headers=auth_headers)
        assert response.status_code == 201
        ids.append(response.json()["id"])
    return ids


def test_search_by_title(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?q=Python")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 2
    titles = [item["title"] for item in data["items"]]
    assert any("Python" in t for t in titles)


def test_search_by_function_name(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?q=parse_date")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 1
    found = False
    for item in data["items"]:
        if "parse_date" in item.get("title", "") or item["language"] == "python":
            found = True
            break
    assert found


def test_search_by_code_content(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?q=requests.get")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 1


def test_filter_by_language(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?language=python")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 2
    for item in data["items"]:
        assert item["language"] == "python"


def test_filter_by_language_go(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?language=go")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 1
    for item in data["items"]:
        assert item["language"] == "go"


def test_filter_by_single_tag(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?tag=http")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 2
    for item in data["items"]:
        assert "http" in item["tags"]


def test_filter_by_multiple_tags_and(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?tags=python&tags=http")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 1
    for item in data["items"]:
        assert "python" in item["tags"]
        assert "http" in item["tags"]


def test_filter_by_multiple_tags_no_match(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?tags=python&tags=go")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] == 0


def test_filter_by_language_and_tag(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?language=python&tag=utility")
    assert response.status_code == 200
    data = response.json()
    assert data["total"] >= 1
    for item in data["items"]:
        assert item["language"] == "python"
        assert "utility" in item["tags"]


def test_search_special_characters_no_error(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    special_queries = [
        "`",
        "'",
        '"',
        "; DROP TABLE snippets;--",
        "OR 1=1--",
        "<script>alert(1)</script>",
        "../../etc/passwd",
        "%s",
        "?",
        "*",
        ".",
        "[",
        "]",
        "()",
        "\\",
    ]
    for query in special_queries:
        response = client.get(f"/api/search?q={query}")
        assert response.status_code == 200


def test_sort_by_stars(client, auth_headers, auth_headers2):
    ids = _create_test_snippets(client, auth_headers)
    for i, sid in enumerate(ids[:3]):
        for _ in range(i + 1):
            client.post(f"/api/snippets/{sid}/star", headers=auth_headers2)

    response = client.get("/api/search?sort_by=stars_count&sort_order=desc")
    assert response.status_code == 200
    data = response.json()
    stars = [item["stars_count"] for item in data["items"]]
    for i in range(len(stars) - 1):
        assert stars[i] >= stars[i + 1]


def test_sort_by_updated_at(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search?sort_by=updated_at&sort_order=desc")
    assert response.status_code == 200
    data = response.json()
    dates = [item["updated_at"] for item in data["items"]]
    for i in range(len(dates) - 1):
        assert dates[i] >= dates[i + 1]


def test_get_popular_tags(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search/tags")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
    assert len(data) > 0
    tag_names = [t["name"] for t in data]
    assert "python" in tag_names


def test_get_languages(client, auth_headers):
    _create_test_snippets(client, auth_headers)
    response = client.get("/api/search/languages")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
    languages = [l["language"] for l in data]
    assert "python" in languages
    assert "javascript" in languages
