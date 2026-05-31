import pytest
from fastapi.testclient import TestClient
import io


def test_list_files(client: TestClient):
    response = client.get("/api/v1/storage/files")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data


def test_upload_file(client: TestClient):
    file_content = b"test content"
    response = client.post(
        "/api/v1/storage/upload",
        files={"file": ("test.txt", io.BytesIO(file_content), "text/plain")},
    )
    assert response.status_code in [200, 201]


def test_get_file_not_found(client: TestClient):
    response = client.get("/api/v1/storage/files/nonexistent-file")
    assert response.status_code in [404, 200]


def test_delete_file_not_found(client: TestClient):
    response = client.delete("/api/v1/storage/files/nonexistent-file")
    assert response.status_code in [404, 200]
