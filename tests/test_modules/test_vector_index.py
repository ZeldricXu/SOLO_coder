import pytest
import math
from streamsql.modules.vector_index.embedding import EmbeddingService, EmbeddingProvider
from streamsql.modules.vector_index.index_builder import VectorIndexBuilder, IndexType
from streamsql.modules.vector_index.ann_search import ANNSearcher


def test_embedding_service_mock():
    service = EmbeddingService(provider=EmbeddingProvider.MOCK, dimension=5)
    text = "Hello world"
    embedding = service.embed(text)
    assert len(embedding) == 5
    assert all(isinstance(x, float) for x in embedding)


def test_embedding_service_batch():
    service = EmbeddingService(provider=EmbeddingProvider.MOCK, dimension=5)
    texts = ["Hello", "World", "Test"]
    embeddings = service.embed_batch(texts)
    assert len(embeddings) == 3
    assert all(len(e) == 5 for e in embeddings)


def test_cosine_similarity():
    service = EmbeddingService(provider=EmbeddingProvider.MOCK)
    v1 = [1.0, 0.0, 0.0]
    v2 = [1.0, 0.0, 0.0]
    v3 = [0.0, 1.0, 0.0]
    assert abs(service.cosine_similarity(v1, v2) - 1.0) < 0.001
    assert abs(service.cosine_similarity(v1, v3)) < 0.001


def test_vector_index_builder_flat(sample_vector_data):
    builder = VectorIndexBuilder(index_type=IndexType.FLAT, dimension=5)
    vectors = [d["vector"] for d in sample_vector_data]
    ids = [d["id"] for d in sample_vector_data]
    builder.build(vectors, ids)
    assert builder.index_size() == 3
    assert builder.dimension == 5


def test_vector_index_builder_hnsw(sample_vector_data):
    builder = VectorIndexBuilder(index_type=IndexType.HNSW, dimension=5)
    vectors = [d["vector"] for d in sample_vector_data]
    ids = [d["id"] for d in sample_vector_data]
    builder.build(vectors, ids)
    assert builder.index_size() == 3


def test_vector_index_builder_ivf(sample_vector_data):
    builder = VectorIndexBuilder(index_type=IndexType.IVF, dimension=5, nlist=2)
    vectors = [d["vector"] for d in sample_vector_data]
    ids = [d["id"] for d in sample_vector_data]
    builder.build(vectors, ids)
    assert builder.index_size() == 3


def test_vector_index_save_load(tmp_path, sample_vector_data):
    builder = VectorIndexBuilder(index_type=IndexType.FLAT, dimension=5)
    vectors = [d["vector"] for d in sample_vector_data]
    ids = [d["id"] for d in sample_vector_data]
    builder.build(vectors, ids)

    index_path = tmp_path / "test_index.pkl"
    builder.save(str(index_path))
    assert index_path.exists()

    builder2 = VectorIndexBuilder(index_type=IndexType.FLAT, dimension=5)
    builder2.load(str(index_path))
    assert builder2.index_size() == 3


def test_ann_searcher_flat(sample_vector_data):
    builder = VectorIndexBuilder(index_type=IndexType.FLAT, dimension=5)
    vectors = [d["vector"] for d in sample_vector_data]
    ids = [d["id"] for d in sample_vector_data]
    builder.build(vectors, ids)

    searcher = ANNSearcher(builder)
    query = [0.1, 0.2, 0.3, 0.4, 0.5]
    results = searcher.search(query, k=2)
    assert len(results) == 2
    assert results[0]["id"] == 1
    assert results[0]["distance"] <= results[1]["distance"]


def test_ann_searcher_hnsw(sample_vector_data):
    builder = VectorIndexBuilder(index_type=IndexType.HNSW, dimension=5)
    vectors = [d["vector"] for d in sample_vector_data]
    ids = [d["id"] for d in sample_vector_data]
    builder.build(vectors, ids)

    searcher = ANNSearcher(builder)
    query = [0.1, 0.2, 0.3, 0.4, 0.5]
    results = searcher.search(query, k=2)
    assert len(results) == 2


def test_ann_searcher_with_threshold(sample_vector_data):
    builder = VectorIndexBuilder(index_type=IndexType.FLAT, dimension=5)
    vectors = [d["vector"] for d in sample_vector_data]
    ids = [d["id"] for d in sample_vector_data]
    builder.build(vectors, ids)

    searcher = ANNSearcher(builder)
    query = [0.1, 0.2, 0.3, 0.4, 0.5]
    results = searcher.search(query, k=10, threshold=0.5)
    assert all(r["distance"] <= 0.5 for r in results)
