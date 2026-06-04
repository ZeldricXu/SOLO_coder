import pytest
import asyncio
import json
import numpy as np
import random
from collections import defaultdict
import os
import sys
from unittest.mock import Mock, patch, MagicMock, AsyncMock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from recommendation_engine.feedback_collector import FeedbackCollector
from tests.factories.data_factories import (
    FeedbackEventFactory,
    ContentItemFactory,
    generate_content_id,
    generate_user_id,
    generate_embedding,
)


@pytest.mark.concurrent
class TestFeedbackCollectorConcurrentFlush:
    @pytest.fixture
    def setup_collector(self):
        tmp_dir = os.path.join(os.path.dirname(__file__), "tmp_concurrent")
        os.makedirs(tmp_dir, exist_ok=True)

        with patch('config.settings.feedback_collector_max_queue_size', 10000), \
             patch('config.settings.feedback_collector_worker_count', 4), \
             patch('config.settings.feedback_collector_batch_size', 100), \
             patch('config.settings.iceberg_warehouse', tmp_dir):

            collector = FeedbackCollector.__new__(FeedbackCollector)
            collector._iceberg_writer = MagicMock()
            collector._iceberg_writer.write_events = Mock(return_value=True)
            collector._iceberg_writer.close = Mock()
            collector._iceberg_writer.get_stats = Mock(return_value={"available": True})

            sent_events = []
            send_lock = asyncio.Lock()

            async def mock_send_batch(topic, messages, key_field=None):
                async with send_lock:
                    sent_events.extend(messages)
                return len(messages)

            collector._producer = MagicMock()
            collector._producer.send_batch = AsyncMock(side_effect=mock_send_batch)
            collector._consumer = MagicMock()

            collector._stats = {
                "events_received": 0,
                "events_sent_to_kafka": 0,
                "events_written_to_iceberg": 0,
                "events_dropped": 0,
                "kafka_send_failures": 0,
                "iceberg_write_failures": 0,
            }
            collector._event_queue = asyncio.Queue(maxsize=10000)
            collector._running = True
            collector._producer_tasks = []
            collector._consumer_task = None
            collector._max_queue_size = 10000
            collector._batch_size = 100
            collector._topic = "test-feedback-concurrent"
            collector._worker_count = 4

            yield collector, sent_events, send_lock

    @pytest.mark.asyncio
    async def test_multiple_workers_flush_batch_integrity(self, setup_collector):
        collector, sent_events, send_lock = setup_collector

        num_events = 1000
        events = FeedbackEventFactory.build_batch(num_events)
        event_ids = {e.event_id for e in events}

        for i in range(collector._worker_count):
            task = asyncio.create_task(collector._producer_worker(i))
            collector._producer_tasks.append(task)

        await asyncio.sleep(0.1)

        for event in events:
            await collector._event_queue.put(event)
            collector._stats["events_received"] += 1

        await asyncio.sleep(2.0)

        collector._running = False
        for task in collector._producer_tasks:
            if not task.done():
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass

        assert len(sent_events) == num_events, f"Expected {num_events} events, got {len(sent_events)}"

        sent_ids = {e["event_id"] for e in sent_events}
        assert sent_ids == event_ids, "Event IDs mismatch - some events lost or duplicated"

        id_counts = defaultdict(int)
        for e in sent_events:
            id_counts[e["event_id"]] += 1

        duplicates = [eid for eid, count in id_counts.items() if count > 1]
        assert len(duplicates) == 0, f"Found duplicate events: {duplicates[:5]}"

        stats = collector.get_stats()
        assert stats["events_received"] == num_events
        assert stats["events_sent_to_kafka"] == num_events
        assert stats["kafka_send_failures"] == 0

    @pytest.mark.asyncio
    async def test_concurrent_collect_and_flush_no_data_loss(self, setup_collector):
        collector, sent_events, send_lock = setup_collector

        num_events = 2000
        events = FeedbackEventFactory.build_batch(num_events)

        for i in range(collector._worker_count):
            task = asyncio.create_task(collector._producer_worker(i))
            collector._producer_tasks.append(task)

        await asyncio.sleep(0.1)

        async def producer_worker(events_chunk):
            for event in events_chunk:
                await collector._event_queue.put(event)
                async with send_lock:
                    collector._stats["events_received"] += 1
                await asyncio.sleep(0.001)

        chunk_size = num_events // 4
        chunks = [events[i:i + chunk_size] for i in range(0, num_events, chunk_size)]

        producer_tasks = [
            asyncio.create_task(producer_worker(chunk))
            for chunk in chunks
        ]

        await asyncio.gather(*producer_tasks)

        await asyncio.sleep(3.0)

        collector._running = False
        for task in collector._producer_tasks:
            if not task.done():
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass

        assert len(sent_events) == num_events, f"Expected {num_events} events, got {len(sent_events)}"

        assert collector._event_queue.empty(), "Queue should be empty after flush"

    @pytest.mark.asyncio
    async def test_batch_boundaries_preserved_under_concurrency(self, setup_collector):
        collector, sent_events, send_lock = setup_collector

        batch_size = collector._batch_size
        num_batches = 10
        num_events = num_batches * batch_size

        events = []
        for i in range(num_events):
            event = FeedbackEventFactory(event_id=f"event_{i:06d}")
            events.append(event)

        batch_boundaries = []

        original_flush = collector._flush_batch

        async def tracking_flush(batch, worker_id):
            batch_boundaries.append((worker_id, [e.event_id for e in batch]))
            await original_flush(batch, worker_id)

        collector._flush_batch = tracking_flush

        for i in range(collector._worker_count):
            task = asyncio.create_task(collector._producer_worker(i))
            collector._producer_tasks.append(task)

        await asyncio.sleep(0.1)

        for event in events:
            await collector._event_queue.put(event)
            collector._stats["events_received"] += 1

        await asyncio.sleep(2.0)

        collector._running = False
        for task in collector._producer_tasks:
            if not task.done():
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass

        all_flushed_ids = []
        for _, ids in batch_boundaries:
            all_flushed_ids.extend(ids)

        assert len(all_flushed_ids) == num_events, "Not all events were flushed"

        expected_ids = {f"event_{i:06d}" for i in range(num_events)}
        assert set(all_flushed_ids) == expected_ids, "Event IDs mismatch"

        for worker_id, batch_ids in batch_boundaries:
            assert len(batch_ids) <= batch_size, f"Batch size {len(batch_ids)} exceeds limit {batch_size}"


@pytest.mark.concurrent
class TestFAISSConcurrentUpdateAndQuery:
    @pytest.fixture
    async def content_index_with_data(self, mock_redis, mock_postgres):
        from recommendation_engine.content_embedding_index.content_embedding_index import ContentEmbeddingIndex

        dim = 64
        n_initial = 200

        items = []
        for i in range(n_initial):
            cid = generate_content_id()
            category = random.choice(["tech", "sports", "finance", "entertainment"])
            vec = generate_embedding(dim)
            items.append({
                "content_id": cid,
                "title": f"Content {i}",
                "content_type": "article",
                "categories": [category],
                "tags": [f"tag_{i}"],
                "author": "test",
                "popularity_score": random.uniform(0, 100),
                "embedding": json.dumps(vec),
            })

        mock_postgres._tables["content_items"] = items

        service = ContentEmbeddingIndex.__new__(ContentEmbeddingIndex)
        service._instance = None
        service._redis = mock_redis
        service._postgres = mock_postgres
        service._embedding_client = MagicMock()
        service._index = None
        service._id_mapping = {}
        service._reverse_mapping = {}
        service._next_id = 0
        service._dirty_ids = set()
        service._pending_updates = []
        service._lock = asyncio.Lock()
        service._embedding_dim = dim
        service._index_path = "./data/faiss_index"
        service._index_type = "Flat"
        service._nprobe = 64
        service._rebuild_batch_size = 10000
        service._hot_reload_task = None
        service._running = False
        service._vectors: dict = {}

        for item in items:
            cid = item["content_id"]
            vec_data = item["embedding"]
            if isinstance(vec_data, str):
                vec_data = json.loads(vec_data)
            vec_arr = np.array(vec_data, dtype=np.float32)
            service._vectors[cid] = vec_arr
            idx = service._next_id
            service._next_id += 1
            service._id_mapping[idx] = cid
            service._reverse_mapping[cid] = idx

        async def _mock_search(query_vector, top_k=100):
            if not service._vectors:
                return np.array([[]], dtype=np.float32), np.array([[]], dtype=np.int64)
            sims = []
            for cid, vec in service._vectors.items():
                norm1 = np.linalg.norm(query_vector)
                norm2 = np.linalg.norm(vec)
                if norm1 > 0 and norm2 > 0:
                    sim = float(np.dot(query_vector, vec) / (norm1 * norm2))
                else:
                    sim = 0.0
                sims.append((cid, sim))
            sims.sort(key=lambda x: x[1], reverse=True)
            sims = sims[:top_k]
            indices = [service._reverse_mapping.get(cid, -1) for cid, _ in sims]
            scores = np.array([[s for _, s in sims]], dtype=np.float32)
            idx_arr = np.array([indices], dtype=np.int64)
            return scores, idx_arr

        async def _mock_add_embedding(content_id, embedding, embedding_type="text"):
            if isinstance(embedding, list):
                embedding = np.array(embedding, dtype=np.float32)
            service._vectors[content_id] = embedding
            if content_id not in service._reverse_mapping:
                idx = service._next_id
                service._next_id += 1
                service._id_mapping[idx] = content_id
                service._reverse_mapping[content_id] = idx

        async def _mock_get_index_stats():
            return {
                "total_vectors": len(service._vectors),
                "index_type": "mock",
                "available": True,
            }

        async def _mock_flush_pending_updates():
            pass

        service.search = _mock_search
        service.add_embedding = _mock_add_embedding
        service.get_index_stats = _mock_get_index_stats
        service.flush_pending_updates = _mock_flush_pending_updates

        yield service, dim, items

        service._instance = None

    @pytest.mark.asyncio
    async def test_queries_return_valid_results_during_incremental_updates(self, content_index_with_data):
        index, dim, initial_items = content_index_with_data

        query_count = 100
        update_count = 50

        query_results = []
        query_errors = []

        async def continuous_queries():
            for i in range(query_count):
                try:
                    query_vector = np.random.randn(dim).astype(np.float32)
                    scores, indices = await index.search(query_vector, top_k=10)
                    if isinstance(indices, np.ndarray) and indices.ndim == 2:
                        idx_list = indices[0].tolist()
                    elif isinstance(indices, np.ndarray):
                        idx_list = indices.tolist()
                    else:
                        idx_list = list(indices)
                    valid_ids = [index._id_mapping.get(int(idx)) for idx in idx_list if int(idx) >= 0]
                    query_results.append((i, len(valid_ids), valid_ids))
                    await asyncio.sleep(0.01)
                except Exception as e:
                    query_errors.append((i, str(e)))

        async def incremental_updates():
            for i in range(update_count):
                try:
                    cid = generate_content_id()
                    embedding = generate_embedding(dim)
                    await index.add_embedding(cid, np.array(embedding, dtype=np.float32))
                    await asyncio.sleep(0.02)
                except Exception as e:
                    print(f"Update error at {i}: {e}")
                    raise

        query_task = asyncio.create_task(continuous_queries())
        update_task = asyncio.create_task(incremental_updates())

        await asyncio.gather(query_task, update_task)

        assert len(query_errors) == 0, f"Query errors occurred: {query_errors}"

        assert len(query_results) == query_count, f"Expected {query_count} queries, got {len(query_results)}"

        for i, n_results, ids in query_results:
            assert n_results > 0, f"Query {i} returned empty results"
            assert n_results <= 10, f"Query {i} returned too many results: {n_results}"

    @pytest.mark.asyncio
    async def test_lock_protects_index_integrity_during_concurrent_flush(self, content_index_with_data):
        index, dim, initial_items = content_index_with_data

        num_workers = 3
        updates_per_worker = 30
        total_updates = num_workers * updates_per_worker

        async def update_worker(worker_id):
            for i in range(updates_per_worker):
                cid = f"worker_{worker_id}_content_{i}"
                embedding = generate_embedding(dim)
                await index.add_embedding(cid, np.array(embedding, dtype=np.float32))
                await asyncio.sleep(0.005)

        worker_tasks = [
            asyncio.create_task(update_worker(i))
            for i in range(num_workers)
        ]

        await asyncio.gather(*worker_tasks)

        stats = await index.get_index_stats()
        expected_total = len(initial_items) + total_updates
        assert stats["total_vectors"] == expected_total, f"Expected {expected_total} vectors, got {stats['total_vectors']}"

        test_content_id = "worker_0_content_0"
        assert test_content_id in index._reverse_mapping, f"Content {test_content_id} not found in mapping"

    @pytest.mark.asyncio
    async def test_search_consistency_before_and_after_flush(self, content_index_with_data):
        index, dim, initial_items = content_index_with_data

        query_vector = np.random.randn(dim).astype(np.float32)

        scores_before, indices_before = await index.search(query_vector, top_k=50)
        if isinstance(indices_before, np.ndarray) and indices_before.ndim == 2:
            ids_before = {index._id_mapping.get(int(idx)) for idx in indices_before[0] if int(idx) >= 0}
        else:
            ids_before = set()

        new_content_ids = []
        for i in range(10):
            cid = generate_content_id()
            new_content_ids.append(cid)
            embedding = generate_embedding(dim)
            await index.add_embedding(cid, np.array(embedding, dtype=np.float32))

        stats = await index.get_index_stats()
        expected_total = len(initial_items) + 10
        assert stats["total_vectors"] == expected_total

        for cid in new_content_ids:
            assert cid in index._reverse_mapping, f"Content {cid} not indexed after add"

    @pytest.mark.asyncio
    async def test_concurrent_batch_updates_and_queries(self, content_index_with_data):
        index, dim, initial_items = content_index_with_data

        query_task_count = 5
        update_task_count = 3
        queries_per_task = 20
        updates_per_task = 20

        successful_queries = 0
        failed_queries = 0
        query_lock = asyncio.Lock()

        async def query_task_fn():
            nonlocal successful_queries, failed_queries
            for _ in range(queries_per_task):
                try:
                    query_vector = np.random.randn(dim).astype(np.float32)
                    scores, indices = await index.search(query_vector, top_k=20)
                    async with query_lock:
                        successful_queries += 1
                    await asyncio.sleep(0.01)
                except Exception:
                    async with query_lock:
                        failed_queries += 1

        async def update_task_fn():
            for i in range(updates_per_task):
                try:
                    for j in range(5):
                        cid = generate_content_id()
                        embedding = generate_embedding(dim)
                        await index.add_embedding(cid, np.array(embedding, dtype=np.float32))
                    await asyncio.sleep(0.03)
                except Exception:
                    pass

        query_tasks = [asyncio.create_task(query_task_fn()) for _ in range(query_task_count)]
        update_tasks = [asyncio.create_task(update_task_fn()) for _ in range(update_task_count)]

        await asyncio.gather(*query_tasks, *update_tasks)

        assert failed_queries == 0, f"{failed_queries} queries failed during concurrent updates"
        assert successful_queries == query_task_count * queries_per_task
