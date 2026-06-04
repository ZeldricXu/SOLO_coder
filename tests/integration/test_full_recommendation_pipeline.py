import pytest
import asyncio
import json
import os
import tempfile
import time
from unittest.mock import patch, MagicMock, Mock
from datetime import datetime, timezone
import numpy as np
import random

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from recommendation_engine.infrastructure import (
    RedisClient,
    PostgresClient,
    KafkaProducerClient,
    KafkaConsumerClient,
)
from recommendation_engine.user_profile_service import UserProfileService
from recommendation_engine.content_embedding_index import ContentEmbeddingIndex
from recommendation_engine.collaborative_filter import CollaborativeFilter, ALSTrainer
from recommendation_engine.realtime_rank_pipeline import RealtimeRankPipeline
from recommendation_engine.ab_test_router import ABTestRouter
from recommendation_engine.feedback_collector import FeedbackCollector
from recommendation_engine.models.schemas import (
    UserBehaviorEvent,
    ContentItem,
    RecommendRequest,
    RecommendResponse,
)
from tests.factories.data_factories import (
    UserBehaviorEventFactory,
    ContentItemFactory,
    ABTestExperimentFactory,
    RecommendRequestFactory,
    generate_user_id,
    generate_content_id,
    generate_embedding,
)

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def docker():
    try:
        from testcontainers.core.container import DockerContainer
        from testcontainers.postgres import PostgresContainer
        from testcontainers.redis import RedisContainer

        import docker as docker_sdk
        client = docker_sdk.from_env()
        client.ping()

        return {
            "DockerContainer": DockerContainer,
            "PostgresContainer": PostgresContainer,
            "RedisContainer": RedisContainer,
        }
    except ImportError:
        pytest.skip("testcontainers not installed, skipping integration tests")
    except Exception as e:
        pytest.skip(f"Docker not available ({e}), skipping integration tests")


@pytest.fixture(scope="module")
def tmp_module_dir():
    tmp_dir = tempfile.mkdtemp(prefix="reco_integration_")
    yield tmp_dir
    import shutil
    shutil.rmtree(tmp_dir, ignore_errors=True)


@pytest.fixture(scope="module")
def postgres_container(docker, tmp_module_dir):
    PostgresContainer = docker["PostgresContainer"]

    with PostgresContainer("postgres:16-alpine") as container:
        container.with_env("POSTGRES_DB", "recommendation")
        container.with_env("POSTGRES_USER", "testuser")
        container.with_env("POSTGRES_PASSWORD", "testpass")

        container.start()
        time.sleep(5)

        yield {
            "host": container.get_container_host_ip(),
            "port": container.get_exposed_port(5432),
            "user": "testuser",
            "password": "testpass",
            "database": "recommendation",
        }

        container.stop()


@pytest.fixture(scope="module")
def redis_container(docker, tmp_module_dir):
    RedisContainer = docker["RedisContainer"]

    with RedisContainer("redis:7-alpine") as container:
        container.start()
        time.sleep(3)

        yield {
            "host": container.get_container_host_ip(),
            "port": container.get_exposed_port(6379),
        }

        container.stop()


@pytest.fixture
async def postgres_client(postgres_container):
    with patch('config.settings.pg_host', postgres_container["host"]), \
         patch('config.settings.pg_port', int(postgres_container["port"])), \
         patch('config.settings.pg_user', postgres_container["user"]), \
         patch('config.settings.pg_password', postgres_container["password"]), \
         patch('config.settings.pg_database', postgres_container["database"]):

        PostgresClient._instance = None
        PostgresClient._pool = None

        client = PostgresClient()
        await client.initialize()

        await client.execute("CREATE EXTENSION IF NOT EXISTS vector;")
        await client.init_tables()

        yield client

        await client.close()
        PostgresClient._instance = None
        PostgresClient._pool = None


@pytest.fixture
async def redis_client(redis_container):
    with patch('config.settings.redis_host', redis_container["host"]), \
         patch('config.settings.redis_port', int(redis_container["port"])), \
         patch('config.settings.redis_db', 0), \
         patch('config.settings.redis_password', None):

        RedisClient._instance = None
        RedisClient._pool = None
        RedisClient._client = None

        client = RedisClient()
        await client.initialize()

        yield client

        await client.close()
        RedisClient._instance = None
        RedisClient._pool = None
        RedisClient._client = None


@pytest.fixture
async def setup_test_data(postgres_client, redis_client, tmp_module_dir):
    dim = 64
    n_items = 100

    with patch('config.settings.faiss_embedding_dim', dim), \
         patch('config.settings.faiss_index_type', 'Flat'), \
         patch('config.settings.faiss_index_path', os.path.join(tmp_module_dir, 'faiss')):

        items = []
        embeddings = []

        for i in range(n_items):
            cid = generate_content_id()
            category = random.choice(["tech", "sports", "finance", "entertainment", "health"])
            embedding = generate_embedding(dim)

            item = {
                "content_id": cid,
                "title": f"Test Content {i} - {category}",
                "content_type": "article",
                "categories": [category],
                "tags": [f"tag_{i}", category],
                "author": "test_author",
                "popularity_score": random.uniform(10, 90),
                "embedding": json.dumps(embedding),
            }
            items.append(item)
            embeddings.append({
                "content_id": cid,
                "embedding": embedding,
                "category": category,
            })

            await postgres_client.insert("content_items", item)

        n_users = 50
        user_ids = [generate_user_id() for _ in range(n_users)]
        interactions = []

        for uid in user_ids:
            n_interactions = random.randint(5, 20)
            for _ in range(n_interactions):
                item = random.choice(items)
                event_type = random.choice(["click", "view", "like"])
                interactions.append((uid, item["content_id"], event_type, 1.0))

        experiment = ABTestExperimentFactory(
            experiment_id="exp_integration_001",
            name="Integration Test Experiment",
            layer="recall_layer",
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=[{"group_id": "control", "weight": 50}, {"group_id": "exp_a", "weight": 50}],
        )

        exp_data = experiment.model_dump()
        exp_data["experiment_groups"] = json.dumps(exp_data["experiment_groups"], ensure_ascii=False)
        exp_data["config"] = json.dumps(exp_data.get("config", {}), ensure_ascii=False)

        await postgres_client.insert("abtest_experiments", exp_data)

        yield {
            "items": items,
            "embeddings": embeddings,
            "user_ids": user_ids,
            "interactions": interactions,
            "dim": dim,
        }


@pytest.mark.asyncio
@pytest.mark.slow
async def test_full_recommendation_pipeline(
    postgres_client,
    redis_client,
    setup_test_data,
    tmp_module_dir,
):
    dim = setup_test_data["dim"]
    test_user_id = setup_test_data["user_ids"][0]

    with patch('config.settings.faiss_embedding_dim', dim), \
         patch('config.settings.faiss_index_type', 'Flat'), \
         patch('config.settings.faiss_index_path', os.path.join(tmp_module_dir, 'faiss')), \
         patch('config.settings.als_factors', 32), \
         patch('config.settings.pipeline_recall_top_k', 50), \
         patch('config.settings.pipeline_rank_top_k', 20), \
         patch('config.settings.pipeline_rerank_top_k', 10), \
         patch('config.settings.pipeline_mmr_lambda', 0.7), \
         patch('config.settings.hot_reload_enabled', False):

        als_trainer = ALSTrainer()
        als_trainer._factors = 32
        als_trainer._n_users = len(setup_test_data["user_ids"])
        als_trainer._n_items = len(setup_test_data["items"])

        user_id_map = {uid: i for i, uid in enumerate(setup_test_data["user_ids"])}
        item_id_map = {item["content_id"]: i for i, item in enumerate(setup_test_data["items"])}

        als_trainer._user_id_map = user_id_map
        als_trainer._item_id_map = item_id_map
        als_trainer._user_ids = setup_test_data["user_ids"]
        als_trainer._item_ids = [item["content_id"] for item in setup_test_data["items"]]

        np.random.seed(42)
        als_trainer._user_factors = np.random.randn(als_trainer._n_users, als_trainer._factors).astype(np.float32)
        als_trainer._item_factors = np.random.randn(als_trainer._n_items, als_trainer._factors).astype(np.float32)
        als_trainer._trained = True

        ContentEmbeddingIndex._instance = None
        content_index = ContentEmbeddingIndex()
        await content_index.initialize(redis_client, postgres_client)

        CollaborativeFilter._instance = None
        cf_service = CollaborativeFilter()
        await cf_service.initialize(redis_client, als_trainer)

        UserProfileService._instance = None
        profile_service = UserProfileService()
        await profile_service.initialize(redis_client, postgres_client)

        RealtimeRankPipeline._instance = None
        rank_pipeline = RealtimeRankPipeline()
        await rank_pipeline.initialize(
            redis_client, postgres_client,
            user_profile_service=profile_service,
            content_index=content_index,
            cf_service=cf_service,
        )

        ABTestRouter._instance = None
        abtest_router = ABTestRouter()
        await abtest_router.initialize(redis_client, postgres_client)

        await abtest_router.force_reload()

    click_event = UserBehaviorEventFactory(
        user_id=test_user_id,
        event_type="click",
        content_id=setup_test_data["items"][0]["content_id"],
        categories=["tech"],
        tags=["python", "ai"],
        duration_ms=5000,
    )

    await profile_service.ingest_behavior_event(click_event)

    await asyncio.sleep(0.5)

    profile = await profile_service.get_user_profile(test_user_id)
    assert profile is not None
    assert profile.user_id == test_user_id

    stats = await profile_service.get_user_statistics(test_user_id)
    assert stats is not None
    assert stats["user_id"] == test_user_id

    request = RecommendRequestFactory(
        user_id=test_user_id,
        top_n=10,
    )

    assignment = await abtest_router.get_user_assignment(test_user_id, "recall_layer")
    assert assignment is not None

    recall_results = await rank_pipeline._recall_layer.recall(
        user_id=test_user_id,
        top_k=50,
    )
    assert recall_results is not None
    assert len(recall_results) > 0
    assert len(recall_results) <= 50

    ranked_results = await rank_pipeline._rank_layer.rank(
        user_id=test_user_id,
        recall_items=recall_results,
        top_k=20,
    )
    assert ranked_results is not None
    assert len(ranked_results) > 0
    assert len(ranked_results) <= 20

    reranked_results = await rank_pipeline._rerank_layer.rerank(
        user_id=test_user_id,
        ranked_items=ranked_results,
        top_k=10,
    )
    assert reranked_results is not None
    assert len(reranked_results) > 0
    assert len(reranked_results) <= 10

    pipeline_result = await rank_pipeline.recommend(request)
    assert pipeline_result is not None
    assert isinstance(pipeline_result, RecommendResponse)
    assert len(pipeline_result.results) > 0
    assert len(pipeline_result.results) <= request.top_n

    result_ids = [r.content_id for r in pipeline_result.results]
    assert len(result_ids) == len(set(result_ids))

    print(f"Full pipeline test passed!")
    print(f"  User: {test_user_id}")
    print(f"  AB assignment: {assignment}")
    print(f"  Results: {len(pipeline_result.results)} items")

    await content_index.close()
    ContentEmbeddingIndex._instance = None

    await cf_service.close()
    CollaborativeFilter._instance = None

    await profile_service.close()
    UserProfileService._instance = None

    await rank_pipeline.close()
    RealtimeRankPipeline._instance = None

    await abtest_router.close()
    ABTestRouter._instance = None


@pytest.mark.asyncio
@pytest.mark.slow
async def test_redis_failure_downgrade_to_postgres(
    postgres_client,
    redis_client,
    setup_test_data,
    tmp_module_dir,
):
    dim = setup_test_data["dim"]
    test_user_id = setup_test_data["user_ids"][1]

    with patch('config.settings.faiss_embedding_dim', dim), \
         patch('config.settings.faiss_index_type', 'Flat'), \
         patch('config.settings.faiss_index_path', os.path.join(tmp_module_dir, 'faiss')), \
         patch('config.settings.hot_reload_enabled', False):

        UserProfileService._instance = None
        profile_service = UserProfileService()
        await profile_service.initialize(redis_client, postgres_client)

        click_event = UserBehaviorEventFactory(
            user_id=test_user_id,
            event_type="click",
            content_id=setup_test_data["items"][5]["content_id"],
            categories=["sports"],
            tags=["basketball"],
        )

        await profile_service.ingest_behavior_event(click_event)
        await asyncio.sleep(0.3)

        profile_from_redis = await profile_service.get_user_profile(test_user_id)
        assert profile_from_redis is not None
        version_from_redis = profile_from_redis.profile_version

        cache_key = f"user:profile:{test_user_id}:latest"
        await redis_client.delete(cache_key)

        cached = await redis_client.get(cache_key)
        assert cached is None

        profile_from_pg = await profile_service.get_user_profile(test_user_id)
        assert profile_from_pg is not None
        assert profile_from_pg.user_id == test_user_id

        print(f"Redis failure downgrade test passed!")
        print(f"  User: {test_user_id}")
        print(f"  Version: {version_from_redis}")

        await profile_service.close()
        UserProfileService._instance = None


@pytest.mark.asyncio
@pytest.mark.slow
async def test_abtest_router_with_real_postgres(
    postgres_client,
    redis_client,
    setup_test_data,
):
    with patch('config.settings.abtest_hash_bucket', 1000), \
         patch('config.settings.hot_reload_enabled', False):
        ABTestRouter._instance = None
        abtest_router = ABTestRouter()
        await abtest_router.initialize(redis_client, postgres_client)

        await abtest_router.force_reload()

        user_id = setup_test_data["user_ids"][3]

        assignment1 = await abtest_router.get_user_assignment(user_id, "recall_layer")
        assignment2 = await abtest_router.get_user_assignment(user_id, "recall_layer")

        if assignment1 is not None:
            assert assignment1.group == assignment2.group

        config = await abtest_router.get_experiment_config(user_id)
        assert config is not None

        print(f"ABTest router with Postgres test passed!")
        print(f"  User: {user_id}")
        print(f"  Assignment: {assignment1}")

        await abtest_router.close()
        ABTestRouter._instance = None
