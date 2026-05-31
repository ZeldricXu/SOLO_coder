import pytest


def test_create_embedding_endpoint(client):
    response = client.post(
        "/api/v1/vector/embed",
        json={"text": "Hello world", "provider": "mock", "dimension": 5},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "embedding" in data["data"]
    assert len(data["data"]["embedding"]) == 5


def test_batch_embed_endpoint(client):
    response = client.post(
        "/api/v1/vector/embed-batch",
        json={"texts": ["Hello", "World", "Test"], "provider": "mock", "dimension": 5},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "embeddings" in data["data"]
    assert len(data["data"]["embeddings"]) == 3


def test_build_index_endpoint(client, sample_vector_data):
    response = client.post(
        "/api/v1/vector/build-index",
        json={
            "vectors": sample_vector_data,
            "dimension": 5,
            "index_type": "flat",
            "index_name": "test_index",
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "index_name" in data["data"]
    assert data["data"]["index_size"] == 3


def test_search_vector_endpoint(client):
    vectors = [
        {"id": i, "vector": [0.1 * i, 0.2 * i, 0.3 * i, 0.4 * i, 0.5 * i]}
        for i in range(1, 11)
    ]

    client.post(
        "/api/v1/vector/build-index",
        json={
            "vectors": vectors,
            "dimension": 5,
            "index_type": "flat",
            "index_name": "search_test",
        },
    )

    response = client.post(
        "/api/v1/vector/search",
        json={
            "query_vector": [0.1, 0.2, 0.3, 0.4, 0.5],
            "index_name": "search_test",
            "k": 5,
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "results" in data["data"]
    assert len(data["data"]["results"]) <= 5


def test_get_index_info_endpoint(client):
    response = client.get("/api/v1/vector/index/test_index")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "index_name" in data["data"]


def test_list_indexes_endpoint(client):
    response = client.get("/api/v1/vector/indexes")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "indexes" in data["data"]


def test_delete_index_endpoint(client):
    response = client.delete("/api/v1/vector/index/test_index")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
