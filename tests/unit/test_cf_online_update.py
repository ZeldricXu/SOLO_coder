import pytest
import asyncio
import json
from typing import List, Tuple, Dict, Any
import numpy as np
from datetime import datetime, timedelta, timezone
from unittest.mock import patch, MagicMock, Mock, AsyncMock

from recommendation_engine.collaborative_filter.als_trainer import ALSTrainer
from recommendation_engine.collaborative_filter.collaborative_filter import CollaborativeFilter
from recommendation_engine.models.schemas import OnlineCFUpdateEvent
from tests.factories.data_factories import (
    generate_user_id,
    generate_content_id,
    generate_embedding,
    create_interactions,
)


pytestmark = pytest.mark.unit


@pytest.fixture(autouse=True)
def _patch_settings_and_cleanup_tasks():
    with patch('config.settings.hot_reload_enabled', False), \
         patch('config.settings.cf_online_update_enabled', False):
        yield
    try:
        loop = asyncio.get_event_loop()
        for task in asyncio.all_tasks(loop):
            if not task.done():
                task.cancel()
                try:
                    loop.run_until_complete(task)
                except (asyncio.CancelledError, RuntimeError):
                    pass
    except RuntimeError:
        pass


class TestALSTrainerColdStartInit:

    def test_initialize_cold_start_item_projects_embedding_to_factor_space(self):
        trainer = ALSTrainer()
        trainer._factors = 5

        n_users = 5
        n_items = 10
        interactions = []
        for i in range(n_users):
            for j in range(n_items):
                interactions.append((f"user_{i}", f"item_{j}", np.random.uniform(0.5, 5.0)))

        trainer.train(interactions)
        original_item_count = len(trainer._item_factors)

        new_content_id = generate_content_id()
        embedding = generate_embedding(20)

        result = trainer.initialize_cold_start_item(new_content_id, embedding)

        assert result is not None
        assert isinstance(result, np.ndarray)
        assert result.shape == (5,)
        assert np.linalg.norm(result) == pytest.approx(1.0, abs=1e-6)
        assert new_content_id in trainer._item_id_map
        assert trainer._item_id_map[new_content_id] == original_item_count
        assert original_item_count in trainer._reverse_item_map
        assert trainer._reverse_item_map[original_item_count] == new_content_id
        assert len(trainer._item_factors) == original_item_count + 1

    def test_initialize_existing_item_returns_none(self):
        trainer = ALSTrainer()

        interactions = [
            ("u1", "i1", 5.0),
            ("u1", "i2", 3.0),
            ("u2", "i1", 4.0),
        ]
        trainer.train(interactions)
        original_factors = trainer._item_factors.copy()

        embedding = generate_embedding(10)
        result = trainer.initialize_cold_start_item("i1", embedding)

        assert result is None
        assert np.array_equal(trainer._item_factors, original_factors)

    def test_initialize_with_untrained_model_returns_none(self):
        trainer = ALSTrainer()

        content_id = generate_content_id()
        embedding = generate_embedding(20)

        result = trainer.initialize_cold_start_item(content_id, embedding)

        assert result is None
        assert content_id not in trainer._item_id_map

    def test_projection_matrix_created_for_high_dim_embeddings(self):
        trainer = ALSTrainer()
        trainer._factors = 8

        interactions = create_interactions(100)
        trainer.train(interactions)

        embedding_dim = 50
        trainer._ensure_projection_matrix(embedding_dim)

        assert trainer._projection_matrix is not None
        assert trainer._projection_matrix.shape == (embedding_dim, trainer._factors)

        old_matrix = trainer._projection_matrix.copy()
        trainer._ensure_projection_matrix(embedding_dim)

        assert trainer._projection_matrix is not None
        assert np.array_equal(trainer._projection_matrix, old_matrix)

    def test_pca_projection_when_sklearn_available(self):
        trainer = ALSTrainer()
        trainer._factors = 4

        interactions = create_interactions(50)
        trainer.train(interactions)

        mock_pca_instance = MagicMock()
        expected_transformed = np.array([0.5, 0.5, 0.5, 0.5], dtype=np.float32)
        mock_pca_instance.transform.return_value = expected_transformed.reshape(1, -1)

        with patch('recommendation_engine.collaborative_filter.als_trainer.SKLEARN_AVAILABLE', True), \
             patch('recommendation_engine.collaborative_filter.als_trainer.PCA', return_value=mock_pca_instance):

            content_id = generate_content_id()
            embedding = generate_embedding(30)
            result = trainer.initialize_cold_start_item(content_id, embedding)

            mock_pca_instance.fit.assert_called_once()
            mock_pca_instance.transform.assert_called_once()
            assert result is not None
            assert np.linalg.norm(result) == pytest.approx(1.0, abs=1e-6)


class TestALSTrainerOnlineUpdate:

    def test_update_item_factor_online_updates_factor(self):
        trainer = ALSTrainer()

        interactions = []
        for i in range(10):
            for j in range(10):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(0.5, 5.0)))
        trainer.train(interactions)

        original_item_factor = trainer._item_factors[trainer._item_id_map["i1"]].copy()
        original_user_factor = trainer._user_factors[trainer._user_id_map["u1"]].copy()

        update_interactions = [("u1", "i1", 5.0)]
        result = trainer.update_item_factor_online("i1", update_interactions)

        assert result is not None
        assert not np.array_equal(trainer._item_factors[trainer._item_id_map["i1"]], original_item_factor)
        assert not np.array_equal(trainer._user_factors[trainer._user_id_map["u1"]], original_user_factor)
        assert np.linalg.norm(result) == pytest.approx(1.0, abs=1e-6)

    def test_update_unknown_item_returns_none(self):
        trainer = ALSTrainer()

        interactions = [("u1", "i1", 5.0)]
        trainer.train(interactions)

        update_interactions = [("u1", "unknown_item", 5.0)]
        result = trainer.update_item_factor_online("unknown_item", update_interactions)

        assert result is None

    def test_update_with_unknown_user_skips_user(self):
        trainer = ALSTrainer()

        interactions = [("u1", "i1", 5.0)]
        trainer.train(interactions)

        original_item_factor = trainer._item_factors[trainer._item_id_map["i1"]].copy()

        update_interactions = [
            ("unknown_user", "i1", 5.0),
            ("u1", "i1", 3.0),
        ]
        result = trainer.update_item_factor_online("i1", update_interactions)

        assert result is not None
        assert not np.array_equal(trainer._item_factors[trainer._item_id_map["i1"]], original_item_factor)

    def test_multiple_interactions_accumulate_updates(self):
        trainer = ALSTrainer()
        trainer._factors = 8

        interactions = []
        for i in range(5):
            for j in range(5):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(1.0, 5.0)))
        trainer.train(interactions)

        original_factor = trainer._item_factors[trainer._item_id_map["i0"]].copy()

        single_interaction = [("u0", "i0", 5.0)]
        result_single = trainer.update_item_factor_online("i0", single_interaction, learning_rate=0.1)
        change_single = np.linalg.norm(result_single - original_factor)

        trainer._item_factors[trainer._item_id_map["i0"]] = original_factor.copy()

        multiple_interactions = [
            ("u0", "i0", 5.0),
            ("u1", "i0", 4.0),
            ("u2", "i0", 3.0),
        ]
        result_multiple = trainer.update_item_factor_online("i0", multiple_interactions, learning_rate=0.1)
        change_multiple = np.linalg.norm(result_multiple - original_factor)

        assert change_multiple > change_single

    def test_untrained_model_update_returns_none(self):
        trainer = ALSTrainer()

        interactions = [("u1", "i1", 5.0)]
        result = trainer.update_item_factor_online("i1", interactions)

        assert result is None


class TestCollaborativeFilterOnlineUpdate:

    @pytest.mark.asyncio
    async def test_initialize_new_item_adds_to_online_cache(self, collaborative_filter, mock_redis):
        n_users = 10
        n_items = 20
        interactions = []
        for i in range(n_users):
            for j in range(n_items):
                interactions.append((f"user_{i}", f"item_{j}", np.random.uniform(0.5, 5.0)))
        collaborative_filter._trainer.train(interactions)

        content_id = generate_content_id()
        embedding = generate_embedding(50)

        with patch('recommendation_engine.collaborative_filter.collaborative_filter.settings.cf_online_update_enabled', False):
            result = await collaborative_filter.initialize_new_item(content_id, embedding)

        assert result is True
        assert content_id in collaborative_filter._online_item_factors
        assert content_id in collaborative_filter._cold_start_item_set

        redis_key = f"cf:online:{content_id}"
        stored = await mock_redis.get(redis_key)
        assert stored is not None
        stored_factor = json.loads(stored)
        assert len(stored_factor) == collaborative_filter._trainer._factors

    @pytest.mark.asyncio
    async def test_initialize_new_item_with_seed_users_does_initial_training(self, collaborative_filter):
        interactions = []
        for i in range(5):
            for j in range(10):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(0.5, 5.0)))
        collaborative_filter._trainer.train(interactions)

        content_id = generate_content_id()
        embedding = generate_embedding(30)
        seed_users = ["u1", "u2"]

        original_sync_method = collaborative_filter._trainer.update_item_factor_online
        call_tracker = {'call_count': 0, 'call_args': None}

        async def async_wrapper(*args, **kwargs):
            call_tracker['call_count'] += 1
            call_tracker['call_args'] = (args, kwargs)
            return original_sync_method(*args, **kwargs)

        with patch.object(collaborative_filter._trainer, 'update_item_factor_online', side_effect=async_wrapper):
            result = await collaborative_filter.initialize_new_item(content_id, embedding, seed_users=seed_users)

            assert result is True
            assert call_tracker['call_count'] == 1
            call_args = call_tracker['call_args']
            assert call_args[0][0] == content_id
            assert len(call_args[0][1]) == 2
            assert call_args[0][1][0][0] == "u1"
            assert call_args[0][1][0][1] == content_id
            assert call_args[0][1][0][2] == 1.0
            assert call_args[0][1][1][0] == "u2"

    @pytest.mark.asyncio
    async def test_process_online_interaction_updates_factor(self, collaborative_filter, mock_redis):
        interactions = []
        for i in range(5):
            for j in range(10):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(0.5, 5.0)))
        collaborative_filter._trainer.train(interactions)

        content_id = generate_content_id()
        user_id = "u0"
        embedding = generate_embedding(20)

        with patch('recommendation_engine.collaborative_filter.collaborative_filter.settings.cf_online_update_enabled', False):
            await collaborative_filter.initialize_new_item(content_id, embedding)

        original_factor = collaborative_filter._online_item_factors[content_id].copy()

        cache_key = f"{user_id}:10:"
        collaborative_filter._recall_cache[cache_key] = [("some_item", 0.5)]

        event = OnlineCFUpdateEvent(
            user_id=user_id,
            content_id=content_id,
            event_type="click",
            weight=5.0,
        )

        await collaborative_filter.process_online_interaction(event)

        updated_factor = collaborative_filter._online_item_factors[content_id]
        assert not np.array_equal(updated_factor, original_factor)

        redis_key = f"cf:online:{content_id}"
        stored = await mock_redis.get(redis_key)
        assert stored is not None

        assert cache_key not in collaborative_filter._recall_cache

    @pytest.mark.asyncio
    async def test_get_online_item_factor_prefers_online_over_offline(self, collaborative_filter):
        interactions = [("u1", "i1", 5.0), ("u2", "i1", 4.0)]
        collaborative_filter._trainer.train(interactions)

        offline_factor = collaborative_filter._trainer.get_item_factor("i1")
        assert offline_factor is not None

        online_factor = np.ones_like(offline_factor) * 0.1
        online_factor = online_factor / np.linalg.norm(online_factor)
        collaborative_filter._online_item_factors["i1"] = online_factor

        result = collaborative_filter.get_online_item_factor("i1")

        assert result is not None
        assert np.allclose(np.array(result), online_factor)
        assert not np.allclose(np.array(result), offline_factor)

    @pytest.mark.asyncio
    async def test_is_cold_start_item_detects_recent_items(self, collaborative_filter):
        interactions = [("u1", "i1", 5.0)]
        collaborative_filter._trainer.train(interactions)

        recent_time = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
        old_time = (datetime.now(timezone.utc) - timedelta(hours=48)).isoformat()

        recent_content = {
            "content_id": generate_content_id(),
            "publish_time": recent_time,
        }
        old_content = {
            "content_id": generate_content_id(),
            "publish_time": old_time,
        }

        assert collaborative_filter.is_cold_start_item(recent_content["content_id"], recent_content) is True
        assert collaborative_filter.is_cold_start_item(old_content["content_id"], old_content) is False

    @pytest.mark.asyncio
    async def test_recommend_uses_online_factors(self, collaborative_filter):
        user_id = "u0"
        interactions = []
        for i in range(3):
            for j in range(5):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(0.5, 5.0)))
        collaborative_filter._trainer.train(interactions)

        new_content_id = generate_content_id()
        embedding = generate_embedding(20)

        with patch('recommendation_engine.collaborative_filter.collaborative_filter.settings.cf_online_update_enabled', False):
            await collaborative_filter.initialize_new_item(new_content_id, embedding)

        user_factor = collaborative_filter._trainer.get_user_factor(user_id)
        online_factor = collaborative_filter._online_item_factors[new_content_id]
        expected_score = float(np.dot(user_factor, online_factor))

        results = await collaborative_filter.recommend(user_id, top_k=10, use_cache=False)

        result_ids = [cid for cid, score in results]
        assert new_content_id in result_ids

        for cid, score in results:
            if cid == new_content_id:
                assert score == pytest.approx(expected_score, abs=1e-5)
                break


class TestOnlineUpdateIntegration:

    @pytest.mark.asyncio
    async def test_cold_start_item_enter_recall_within_30_minutes(self, collaborative_filter):
        user_id = "u0"
        interactions = []
        for i in range(5):
            for j in range(15):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(0.5, 5.0)))
        collaborative_filter._trainer.train(interactions)

        new_content_id = generate_content_id()
        embedding = generate_embedding(30)
        seed_users = ["u0", "u1", "u2"]

        original_sync_method = collaborative_filter._trainer.update_item_factor_online
        async def async_wrapper(*args, **kwargs):
            return original_sync_method(*args, **kwargs)

        with patch.object(collaborative_filter._trainer, 'update_item_factor_online', side_effect=async_wrapper):
            init_result = await collaborative_filter.initialize_new_item(
                new_content_id, embedding, seed_users=seed_users
            )
        assert init_result is True

        for i in range(10):
            event = OnlineCFUpdateEvent(
                user_id=f"u{i % 5}",
                content_id=new_content_id,
                event_type="click",
                weight=np.random.uniform(2.0, 5.0),
            )
            await collaborative_filter.process_online_interaction(event)

        results = await collaborative_filter.recommend(user_id, top_k=20, use_cache=False)
        result_ids = [cid for cid, _ in results]

        assert new_content_id in result_ids

        position = result_ids.index(new_content_id)
        assert position < 20

    @pytest.mark.asyncio
    async def test_online_factor_converges_with_more_interactions(self, collaborative_filter):
        interactions = []
        for i in range(5):
            for j in range(10):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(1.0, 5.0)))
        collaborative_filter._trainer.train(interactions)

        content_id = generate_content_id()
        embedding = generate_embedding(20)

        with patch('recommendation_engine.collaborative_filter.collaborative_filter.settings.cf_online_update_enabled', False):
            await collaborative_filter.initialize_new_item(content_id, embedding)

        previous_factor = collaborative_filter._online_item_factors[content_id].copy()
        changes = []

        for step in range(20):
            for i in range(3):
                event = OnlineCFUpdateEvent(
                    user_id=f"u{i}",
                    content_id=content_id,
                    event_type="click",
                    weight=4.0 + np.random.uniform(-0.5, 0.5),
                )
                await collaborative_filter.process_online_interaction(event)

            current_factor = collaborative_filter._online_item_factors[content_id]
            change = np.linalg.norm(current_factor - previous_factor)
            changes.append(change)
            previous_factor = current_factor.copy()

        first_half_avg = np.mean(changes[:10])
        second_half_avg = np.mean(changes[10:])

        assert second_half_avg < first_half_avg

    @pytest.mark.asyncio
    async def test_consumer_task_processes_kafka_messages(self, collaborative_filter):
        interactions = []
        for i in range(3):
            for j in range(5):
                interactions.append((f"u{i}", f"i{j}", np.random.uniform(0.5, 5.0)))
        collaborative_filter._trainer.train(interactions)

        content_id = generate_content_id()
        embedding = generate_embedding(20)

        with patch('recommendation_engine.collaborative_filter.collaborative_filter.settings.cf_online_update_enabled', False):
            await collaborative_filter.initialize_new_item(content_id, embedding)

        mock_consumer_instance = MagicMock()
        mock_consumer_instance.initialize = AsyncMock()

        messages = []
        for i in range(5):
            event = OnlineCFUpdateEvent(
                user_id=f"u{i % 3}",
                content_id=content_id,
                event_type="click",
                weight=4.0,
            )
            event_dict = event.model_dump()
            event_dict["timestamp"] = event_dict["timestamp"].isoformat()
            messages.append((
                event.event_id.encode(),
                json.dumps(event_dict).encode(),
                0,
                i,
            ))

        async def mock_consume(handler):
            for key, value, partition, offset in messages:
                await handler(key, json.loads(value), partition, offset)

        mock_consumer_instance.consume = mock_consume

        original_factor = collaborative_filter._online_item_factors[content_id].copy()

        with patch('recommendation_engine.collaborative_filter.collaborative_filter.KafkaConsumerClient', return_value=mock_consumer_instance):
            task = asyncio.create_task(collaborative_filter._online_update_consumer())
            try:
                await asyncio.wait_for(asyncio.sleep(0.1), timeout=1.0)
            except asyncio.TimeoutError:
                pass
            finally:
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass

        updated_factor = collaborative_filter._online_item_factors[content_id]
        assert not np.array_equal(updated_factor, original_factor)
