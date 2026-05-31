import pytest
from httpx import AsyncClient
from src.modules.document_pipeline import DocumentFormat, ChunkingStrategy


@pytest.mark.asyncio
async def test_list_formats(client: AsyncClient):
    response = await client.get("/api/v1/document-pipeline/formats")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert len(data["data"]) > 0


@pytest.mark.asyncio
async def test_list_chunking_strategies(client: AsyncClient):
    response = await client.get("/api/v1/document-pipeline/chunking-strategies")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert len(data["data"]) > 0


@pytest.mark.asyncio
async def test_parse_document(client: AsyncClient):
    content = "Hello World! This is a test document."
    request = {
        "format": DocumentFormat.TXT.value,
        "content": content,
        "metadata": {"source": "test"},
    }
    response = await client.post("/api/v1/document-pipeline/parse", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "document_id" in data["data"]


@pytest.mark.asyncio
async def test_process_document_pipeline(client: AsyncClient):
    content = "Hello World! " * 100
    request = {
        "format": DocumentFormat.TXT.value,
        "content": content,
        "chunking_strategy": ChunkingStrategy.RECURSIVE.value,
        "chunk_size": 100,
        "chunk_overlap": 20,
        "embedding_model": "default-embedding",
        "metadata": {"source": "test"},
    }
    response = await client.post("/api/v1/document-pipeline/process", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "document_id" in data["data"]
    assert "chunks" in data["data"]
    assert "embeddings" in data["data"]
