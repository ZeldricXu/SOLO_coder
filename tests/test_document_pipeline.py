import pytest
from document_pipeline import (
    DocumentParseRequest,
    ChunkingRequest,
    ChunkingStrategy,
    document_pipeline_service,
)


@pytest.mark.asyncio
async def test_parse_txt_document():
    content = b"Hello World!\nThis is a test document.\n\nSecond paragraph."
    request = DocumentParseRequest(
        file_content=content,
        file_name="test.txt",
    )
    result = await document_pipeline_service.parse_document(request)
    assert "Hello World" in result.text_content
    assert result.document.format == "txt"


@pytest.mark.asyncio
async def test_chunk_text_recursive():
    text = "Lorem ipsum " * 100
    request = ChunkingRequest(
        text=text,
        strategy=ChunkingStrategy.RECURSIVE,
        chunk_size=200,
        chunk_overlap=20,
    )
    result = await document_pipeline_service.chunk_text(request)
    assert result.total_chunks > 0
    assert all(len(c.content) <= 220 for c in result.chunks)


@pytest.mark.asyncio
async def test_chunk_text_paragraph():
    text = "Paragraph 1.\n\nParagraph 2.\n\nParagraph 3."
    request = ChunkingRequest(
        text=text,
        strategy=ChunkingStrategy.PARAGRAPH,
        chunk_size=100,
        chunk_overlap=0,
    )
    result = await document_pipeline_service.chunk_text(request)
    assert result.total_chunks == 3
