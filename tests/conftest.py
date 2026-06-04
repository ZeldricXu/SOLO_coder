import pytest
import asyncio
import json
import re
from typing import Optional, Any, Dict, List, Tuple
from unittest.mock import Mock, patch, MagicMock
from datetime import datetime, timezone
import numpy as np
import random

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recommendation_engine.infrastructure import RedisClient, PostgresClient
from recommendation_engine.user_profile_service import UserProfileService
from recommendation_engine.content_embedding_index import ContentEmbeddingIndex
from recommendation_engine.collaborative_filter import CollaborativeFilter
from recommendation_engine.realtime_rank_pipeline import RealtimeRankPipeline
from recommendation_engine.ab_test_router import ABTestRouter
from recommendation_engine.feedback_collector import FeedbackCollector
from recommendation_engine.model_serving_gateway import ModelServingGateway
from tests.factories.data_factories import (
    UserBehaviorEventFactory,
    UserProfileFactory,
    ContentItemFactory,
    ContentEmbeddingFactory,
    FeedbackEventFactory,
    ABTestExperimentFactory,
    RecommendRequestFactory,
    InterestTagFactory,
    create_interactions,
    generate_user_id,
    generate_content_id,
    generate_embedding,
)


class MockRedisClient:
    def __init__(self):
        self._data: Dict[str, Any] = {}
        self._expirations: Dict[str, float] = {}
        self._counters: Dict[str, int] = {}

    async def initialize(self):
        pass

    async def close(self):
        self._data.clear()
        self._counters.clear()

    async def health_check(self):
        return True

    async def set(self, key: str, value: Any, ttl_seconds=None, nx=False):
        if nx and key in self._data:
            return False
        if isinstance(value, (dict, list)):
            value = json.dumps(value, ensure_ascii=False)
        self._data[key] = value
        if ttl_seconds:
            self._expirations[key] = ttl_seconds
        return True

    async def get(self, key: str):
        return self._data.get(key)

    async def get_json(self, key: str):
        value = self._data.get(key)
        if value is None:
            return None
        try:
            return json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return value

    async def delete(self, *keys):
        count = 0
        for key in keys:
            if key in self._data:
                del self._data[key]
                count += 1
        return count

    async def exists(self, key: str):
        return key in self._data

    async def expire(self, key: str, ttl_seconds: int):
        if key in self._data:
            self._expirations[key] = ttl_seconds
            return True
        return False

    async def incr(self, key: str, amount: int = 1):
        if key not in self._counters:
            self._counters[key] = 0
        self._counters[key] += amount
        return self._counters[key]

    async def incrbyfloat(self, key: str, amount: float):
        if key not in self._counters:
            self._counters[key] = 0
        self._counters[key] = float(self._counters[key]) + amount
        return self._counters[key]

    async def hset(self, key: str, mapping: Dict[str, Any]):
        if key not in self._data:
            self._data[key] = {}
        str_mapping = {}
        for k, v in mapping.items():
            if isinstance(v, (dict, list)):
                str_mapping[k] = json.dumps(v, ensure_ascii=False)
            else:
                str_mapping[k] = str(v)
        self._data[key].update(str_mapping)
        return len(str_mapping)

    async def hget(self, key: str, field: str):
        if key in self._data and isinstance(self._data[key], dict):
            return self._data[key].get(field)
        return None

    async def hgetall(self, key: str):
        if key in self._data and isinstance(self._data[key], dict):
            return self._data[key].copy()
        return {}

    async def hincrby(self, key: str, field: str, amount: int = 1):
        if key not in self._data:
            self._data[key] = {}
        if not isinstance(self._data[key], dict):
            self._data[key] = {}
        current = int(self._data[key].get(field, 0))
        new_val = current + amount
        self._data[key][field] = str(new_val)
        return new_val

    async def hincrbyfloat(self, key: str, field: str, amount: float):
        if key not in self._data:
            self._data[key] = {}
        if not isinstance(self._data[key], dict):
            self._data[key] = {}
        current = float(self._data[key].get(field, 0.0))
        new_val = current + amount
        self._data[key][field] = str(new_val)
        return new_val

    async def lpush(self, key: str, *values):
        if key not in self._data:
            self._data[key] = []
        str_values = []
        for v in values:
            if isinstance(v, (dict, list)):
                str_values.append(json.dumps(v, ensure_ascii=False))
            else:
                str_values.append(str(v))
        self._data[key] = list(reversed(str_values)) + self._data[key]
        return len(self._data[key])

    async def rpush(self, key: str, *values):
        if key not in self._data:
            self._data[key] = []
        str_values = []
        for v in values:
            if isinstance(v, (dict, list)):
                str_values.append(json.dumps(v, ensure_ascii=False))
            else:
                str_values.append(str(v))
        self._data[key].extend(str_values)
        return len(self._data[key])

    async def lrange(self, key: str, start: int, end: int):
        if key not in self._data:
            return []
        data = self._data[key]
        if end == -1:
            end = len(data)
        return data[start:end]

    async def lpop(self, key: str, count: int = 1):
        if key not in self._data or not self._data[key]:
            return None
        result = self._data[key][:count]
        self._data[key] = self._data[key][count:]
        return result

    async def ltrim(self, key: str, start: int, end: int):
        if key not in self._data:
            return True
        data = self._data[key]
        if end == -1:
            end = len(data)
        self._data[key] = data[start:end]
        return True

    async def zadd(self, key: str, mapping: Dict[str, float], nx=False, xx=False):
        if key not in self._data:
            self._data[key] = {}
        added = 0
        for member, score in mapping.items():
            if nx and member in self._data[key]:
                continue
            if xx and member not in self._data[key]:
                continue
            self._data[key][member] = float(score)
            added += 1
        return added

    async def zrange(self, key: str, start: int, end: int, desc=False, withscores=False):
        if key not in self._data:
            return []
        items = sorted(self._data[key].items(), key=lambda x: x[1], reverse=desc)
        if end == -1:
            end = len(items)
        sliced = items[start:end]
        if withscores:
            return [item for tup in sliced for item in tup]
        return [item[0] for item in sliced]

    async def zrevrangebyscore(self, key, max_score=float("inf"), min_score=float("-inf"),
                               offset=0, count=10, withscores=False):
        if key not in self._data:
            return []
        items = sorted(
            [(m, s) for m, s in self._data[key].items() if min_score <= s <= max_score],
            key=lambda x: x[1],
            reverse=True
        )
        sliced = items[offset:offset + count]
        if withscores:
            return [item for tup in sliced for item in tup]
        return [item[0] for item in sliced]

    async def zscore(self, key: str, member: str):
        if key in self._data and member in self._data[key]:
            return self._data[key][member]
        return None

    async def zincrby(self, key: str, amount: float, member: str):
        if key not in self._data:
            self._data[key] = {}
        current = self._data[key].get(member, 0.0)
        new_score = float(current) + amount
        self._data[key][member] = new_score
        return new_score

    async def sadd(self, key: str, *members):
        if key not in self._data:
            self._data[key] = set()
        if not isinstance(self._data[key], set):
            self._data[key] = set()
        added = 0
        for m in members:
            if isinstance(m, (dict, list)):
                m_str = json.dumps(m, ensure_ascii=False)
            else:
                m_str = str(m)
            if m_str not in self._data[key]:
                self._data[key].add(m_str)
                added += 1
        return added

    async def sismember(self, key: str, member: Any):
        if key not in self._data:
            return False
        if isinstance(member, (dict, list)):
            m_str = json.dumps(member, ensure_ascii=False)
        else:
            m_str = str(member)
        return m_str in self._data[key]

    async def smembers(self, key: str):
        if key not in self._data:
            return set()
        return self._data[key].copy()

    def pipeline(self):
        return MockRedisPipeline(self)

    async def ping(self):
        return True

    async def mget(self, keys: List[str]):
        return [self._data.get(k) for k in keys]

    async def mset(self, mapping: Dict[str, Any], ttl_seconds=None):
        for k, v in mapping.items():
            await self.set(k, v, ttl_seconds=ttl_seconds)


class MockRedisPipeline:
    def __init__(self, redis_client: MockRedisClient):
        self._redis = redis_client
        self._commands = []

    def __getattr__(self, name):
        def _method(*args, **kwargs):
            self._commands.append((name, args, kwargs))
            return self
        return _method

    async def execute(self):
        results = []
        for cmd_name, args, kwargs in self._commands:
            method = getattr(self._redis, cmd_name)
            try:
                result = await method(*args, **kwargs)
                results.append(result)
            except Exception as e:
                results.append(e)
        self._commands.clear()
        return results


class MockPostgresClient:
    def __init__(self):
        self._tables: Dict[str, List[Dict[str, Any]]] = {}
        self._initialized = False

    async def initialize(self):
        pass

    async def close(self):
        self._tables.clear()

    async def health_check(self):
        return True

    async def execute(self, query: str, *args):
        return "OK"

    async def fetchrow(self, query: str, *args):
        table_name = self._extract_table_name(query)
        rows = self._tables.get(table_name, [])
        return rows[0] if rows else None

    async def fetch(self, query: str, *args):
        table_name = self._extract_table_name(query)
        rows = self._tables.get(table_name, []).copy()
        
        where_match = re.search(r'WHERE\s+(.+?)(?:ORDER|LIMIT|$)', query, re.IGNORECASE | re.DOTALL)
        if where_match and args:
            where_clause = where_match.group(1).strip()
            
            def _match_row(row):
                if 'user_id' in where_clause and '$1' in where_clause:
                    return row.get('user_id') == args[0]
                return True
            
            rows = [r for r in rows if _match_row(r)]
        
        order_match = re.search(r'ORDER\s+BY\s+([\w,\s]+?)(?:\s+LIMIT|\s+WHERE|$)', query, re.IGNORECASE)
        if not order_match:
            order_match = re.search(r'ORDER\s+BY\s+([\w,\s]+)', query, re.IGNORECASE)
        if order_match:
            order_clause = order_match.group(1).strip()
            order_cols = [c.strip().split()[0] for c in order_clause.split(',')]
            desc_match = re.search(r'ORDER\s+BY\s+[\w,\s]+?(DESC)', query, re.IGNORECASE)
            reverse = desc_match is not None
            try:
                def _sort_key(r):
                    return tuple(
                        (0, r.get(c, '')) if r.get(c) is not None else (1, '')
                        for c in order_cols
                    )
                rows.sort(key=_sort_key, reverse=reverse)
            except TypeError:
                pass
        
        limit_match = re.search(r'LIMIT\s+\$(\d+)', query, re.IGNORECASE)
        if limit_match and args:
            limit_idx = int(limit_match.group(1)) - 1
            if limit_idx < len(args):
                limit = args[limit_idx]
                rows = rows[:limit]
        
        return rows

    async def fetchval(self, query: str, *args):
        return 1

    async def executemany(self, query: str, args_list):
        return "OK"

    async def insert(self, table: str, data: Dict[str, Any], return_id=False):
        if table not in self._tables:
            self._tables[table] = []
        self._tables[table].append(data.copy())
        return None

    async def upsert(self, table: str, data: Dict[str, Any], conflict_columns, update_columns=None):
        if table not in self._tables:
            self._tables[table] = []
        rows = self._tables[table]
        for i, row in enumerate(rows):
            if all(row.get(col) == data.get(col) for col in conflict_columns):
                if update_columns:
                    row.update({col: data.get(col) for col in update_columns})
                else:
                    row.update(data)
                return
        self._tables[table].append(data.copy())

    async def transaction(self, queries):
        for query, args in queries:
            await self.execute(query, *args)

    async def init_tables(self):
        for table in ["abtest_experiments", "content_items", "user_offline_tags",
                      "user_profile_versions", "model_versions"]:
            if table not in self._tables:
                self._tables[table] = []
        self._initialized = True

    def _extract_table_name(self, query: str) -> str:
        import re
        match = re.search(r'FROM\s+(\w+)', query, re.IGNORECASE)
        if match:
            return match.group(1)
        match = re.search(r'INSERT INTO\s+(\w+)', query, re.IGNORECASE)
        if match:
            return match.group(1)
        return "default"


@pytest.fixture
def mock_redis():
    return MockRedisClient()


@pytest.fixture
def mock_postgres():
    return MockPostgresClient()


@pytest.fixture
async def mock_kafka_producer():
    producer = MagicMock()
    producer.send_batch = Mock(return_value=100)
    producer.send = Mock(return_value=True)
    producer.initialize = Mock(return_value=None)
    producer.close = Mock(return_value=None)
    return producer


@pytest.fixture
async def user_profile_service(mock_redis, mock_postgres):
    service = UserProfileService.__new__(UserProfileService)
    service._instance = None
    await service.initialize(mock_redis, mock_postgres)
    yield service
    service._instance = None


@pytest.fixture
async def content_index(mock_redis, mock_postgres):
    from recommendation_engine.content_embedding_index.content_embedding_index import ContentEmbeddingIndex
    import numpy as np

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
    service._embedding_dim = 768
    service._index_path = "./data/faiss_index"
    service._index_type = "IVF1024,Flat"
    service._nprobe = 64
    service._rebuild_batch_size = 10000
    service._hot_reload_task = None
    service._running = False
    service._vectors: Dict[str, np.ndarray] = {}

    async def _mock_search(query_vector, top_k=100):
        if not service._vectors:
            return [], []
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

    async def _mock_get_embedding(content_id):
        key = f"content:embedding:{content_id}"
        data = await mock_redis.get(key)
        if data:
            try:
                vec = json.loads(data) if isinstance(data, str) else data
                return np.array(vec, dtype=np.float32)
            except (json.JSONDecodeError, TypeError):
                pass
        return None

    async def _mock_get_content_info(content_id):
        items = mock_postgres._tables.get("content_items", [])
        for item in items:
            if item.get("content_id") == content_id:
                return item
        return None

    async def _mock_add_embedding(content_id, embedding, embedding_type="text"):
        if isinstance(embedding, list):
            embedding = np.array(embedding, dtype=np.float32)
        service._vectors[content_id] = embedding
        if content_id not in service._reverse_mapping:
            idx = service._next_id
            service._next_id += 1
            service._id_mapping[idx] = content_id
            service._reverse_mapping[content_id] = idx

    async def _mock_batch_search(query_vectors, top_k=100):
        all_results = []
        for qv in query_vectors:
            scores, indices = await _mock_search(qv, top_k)
            all_results.append((scores, indices))
        return all_results

    service.search = _mock_search
    service.get_content_embedding = _mock_get_embedding
    service.get_content_info = _mock_get_content_info
    service.add_embedding = _mock_add_embedding
    service.batch_search = _mock_batch_search

    yield service
    service._instance = None


@pytest.fixture
async def collaborative_filter(mock_redis):
    service = CollaborativeFilter.__new__(CollaborativeFilter)
    service._instance = None
    service._hot_reload_task = None
    service._running = False
    from recommendation_engine.collaborative_filter.als_trainer import ALSTrainer
    trainer = ALSTrainer()
    await service.initialize(mock_redis, trainer)
    yield service
    service._instance = None


@pytest.fixture
async def rank_pipeline(mock_redis, mock_postgres, user_profile_service, content_index, collaborative_filter):
    service = RealtimeRankPipeline.__new__(RealtimeRankPipeline)
    service._instance = None
    await service.initialize(
        mock_redis, mock_postgres,
        user_profile_service=user_profile_service,
        content_index=content_index,
        cf_service=collaborative_filter,
    )
    yield service
    service._instance = None


@pytest.fixture
async def abtest_router(mock_redis, mock_postgres):
    service = ABTestRouter.__new__(ABTestRouter)
    service._instance = None
    await service.initialize(mock_redis, mock_postgres)
    yield service
    service._instance = None


@pytest.fixture
async def feedback_collector():
    import tempfile
    tmp_dir = tempfile.mkdtemp()

    with patch('config.settings.feedback_collector_max_queue_size', 1000), \
         patch('config.settings.feedback_collector_worker_count', 2), \
         patch('config.settings.feedback_collector_batch_size', 50), \
         patch('config.settings.iceberg_warehouse', tmp_dir):

        collector = FeedbackCollector.__new__(FeedbackCollector)
        collector._iceberg_writer = MagicMock()
        collector._iceberg_writer.write_events = Mock(return_value=True)
        collector._iceberg_writer.close = Mock()
        collector._iceberg_writer.get_stats = Mock(return_value={"available": True})

        collector._producer = MagicMock()
        collector._producer.send_batch = Mock(return_value=50)
        collector._consumer = MagicMock()

        collector._stats = {
            "events_received": 0,
            "events_sent_to_kafka": 0,
            "events_written_to_iceberg": 0,
            "events_dropped": 0,
            "kafka_send_failures": 0,
            "iceberg_write_failures": 0,
        }
        collector._event_queue = asyncio.Queue(maxsize=1000)
        collector._running = False
        collector._producer_tasks = []
        collector._consumer_task = None
        collector._max_queue_size = 1000
        collector._batch_size = 50
        collector._topic = "test-feedback"

        yield collector


@pytest.fixture
def model_gateway(mock_postgres):
    from recommendation_engine.model_serving_gateway.model_serving_gateway import ModelServingGateway
    gateway = ModelServingGateway.__new__(ModelServingGateway)
    gateway._instance = None
    gateway._postgres = mock_postgres
    gateway._triton = MagicMock()
    gateway._onnx = MagicMock()
    gateway._model_registry = {}
    gateway._default_versions = {}
    gateway._running = False
    gateway._hot_reload_task = None
    gateway._stats = {
        "total_inferences": 0,
        "triton_inferences": 0,
        "onnx_inferences": 0,
        "inference_errors": 0,
        "avg_inference_time_ms": 0.0,
    }
    gateway._total_inference_time = 0.0
    return gateway


@pytest.fixture
def sample_user_id():
    return generate_user_id()


@pytest.fixture
def sample_content_ids():
    return [generate_content_id() for _ in range(10)]


@pytest.fixture
def sample_embedding():
    return generate_embedding(64)


@pytest.fixture
def sample_click_event(sample_user_id):
    return UserBehaviorEventFactory(
        user_id=sample_user_id,
        event_type="click",
    )


@pytest.fixture
def sample_content_items():
    return ContentItemFactory.build_batch(20)


@pytest.fixture
def sample_interactions():
    return create_interactions(100)


@pytest.fixture
def sample_experiment():
    return ABTestExperimentFactory(status="active", traffic_percentage=100)


@pytest.fixture
def sample_recommend_request(sample_user_id):
    return RecommendRequestFactory(user_id=sample_user_id, top_n=20)


@pytest.fixture
def mock_als_model():
    n_users = 100
    n_items = 200
    n_factors = 64
    np.random.seed(42)
    user_factors = np.random.randn(n_users, n_factors).astype(np.float32)
    item_factors = np.random.randn(n_items, n_factors).astype(np.float32)

    user_ids = [f"user_{i}" for i in range(n_users)]
    item_ids = [f"item_{i}" for i in range(n_items)]

    user_id_map = {uid: i for i, uid in enumerate(user_ids)}
    item_id_map = {iid: i for i, iid in enumerate(item_ids)}

    return {
        "user_factors": user_factors,
        "item_factors": item_factors,
        "user_id_map": user_id_map,
        "item_id_map": item_id_map,
        "user_ids": user_ids,
        "item_ids": item_ids,
        "n_factors": n_factors,
    }


@pytest.fixture
def mock_content_index_data(mock_redis, mock_postgres):
    n_items = 50
    dim = 64
    np.random.seed(42)

    items = []
    embeddings = []

    for i in range(n_items):
        cid = generate_content_id()
        category = random.choice(["tech", "sports", "finance"])
        tag = random.choice(["python", "ai", "basketball", "stocks"])

        vec = np.random.randn(dim).astype(np.float32)
        vec = vec / np.linalg.norm(vec)

        items.append({
            "content_id": cid,
            "title": f"Content {i} about {category}",
            "content_type": "article",
            "categories": [category],
            "tags": [tag],
            "author": "test_author",
            "popularity_score": random.uniform(0, 100),
            "embedding": json.dumps(vec.tolist()),
        })

        embeddings.append({
            "content_id": cid,
            "embedding": vec.tolist(),
            "embedding_type": "text",
        })

    mock_postgres._tables["content_items"] = items

    for emb in embeddings:
        key = f"content:embedding:{emb['content_id']}"
        mock_redis._data[key] = json.dumps(emb["embedding"])

    return {
        "items": items,
        "embeddings": embeddings,
        "dim": dim,
    }


def pytest_configure(config):
    config.addinivalue_line("markers", "unit: Unit tests")
    config.addinivalue_line("markers", "integration: Integration tests")
    config.addinivalue_line("markers", "concurrent: Concurrency tests")
    config.addinivalue_line("markers", "slow: Slow running tests")
