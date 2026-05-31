import pytest
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.document_index import get_document_index, DocumentSource, DocumentType


def test_index_and_search():
    doc_index = get_document_index()
    from modules.document_index import Document
    import uuid
    doc = Document(
        doc_id=f"doc_{uuid.uuid4().hex[:8]}",
        title="Test Document",
        content="This is a test document about Python programming and FastAPI",
        source=DocumentSource.LOCAL,
        doc_type=DocumentType.GUIDE,
    )
    doc_index.index_document(doc)

    results = doc_index.search("Python FastAPI")
    assert len(results) > 0
    assert any(r.title == "Test Document" for r in results)


def test_get_document():
    doc_index = get_document_index()
    from modules.document_index import Document
    import uuid
    doc_id = f"doc_{uuid.uuid4().hex[:8]}"
    doc = Document(
        doc_id=doc_id,
        title="Get Test",
        content="Get document test",
        source=DocumentSource.LOCAL,
        doc_type=DocumentType.GUIDE,
    )
    doc_index.index_document(doc)

    fetched = doc_index.get_document(doc_id)
    assert fetched is not None
    assert fetched.doc_id == doc_id
    assert fetched.title == "Get Test"


def test_list_documents():
    doc_index = get_document_index()
    docs = doc_index.list_documents()
    assert isinstance(docs, list)


def test_delete_document():
    doc_index = get_document_index()
    from modules.document_index import Document
    import uuid
    doc_id = f"doc_{uuid.uuid4().hex[:8]}"
    doc = Document(
        doc_id=doc_id,
        title="Delete Test",
        content="Delete test",
        source=DocumentSource.LOCAL,
        doc_type=DocumentType.GUIDE,
    )
    doc_index.index_document(doc)

    success = doc_index.delete_document(doc_id)
    assert success is True
    assert doc_index.get_document(doc_id) is None


def test_search_with_limit():
    doc_index = get_document_index()
    results = doc_index.search("test", limit=5)
    assert len(results) <= 5
