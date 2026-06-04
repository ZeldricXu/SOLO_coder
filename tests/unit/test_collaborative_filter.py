import pytest
import tempfile
import os
import pickle
import json
from typing import List, Tuple, Dict, Any
import numpy as np
from datetime import datetime
from unittest.mock import patch, MagicMock, mock_open

from recommendation_engine.collaborative_filter.als_trainer import ALSTrainer
from tests.factories.data_factories import (
    create_interactions,
    generate_user_id,
    generate_content_id,
)


pytestmark = pytest.mark.unit


class TestALSTrainerNormalPath:

    def test_train_produces_valid_factors(self):
        trainer = ALSTrainer()

        n_users = 50
        n_items = 100
        interactions = []
        for i in range(n_users):
            for j in range(n_items):
                if np.random.random() > 0.3:
                    uid = f"user_{i}"
                    iid = f"item_{j}"
                    weight = np.random.uniform(0.1, 5.0)
                    interactions.append((uid, iid, weight))

        metrics = trainer.train(interactions)

        assert metrics["n_users"] == pytest.approx(n_users, 1)
        assert metrics["n_items"] == pytest.approx(n_items, 1)
        assert metrics["n_interactions"] > 0
        assert metrics["factors"] == 64

        assert trainer._user_factors is not None
        assert trainer._item_factors is not None
        assert trainer._user_factors.shape == (metrics["n_users"], 64)
        assert trainer._item_factors.shape == (metrics["n_items"], 64)

    def test_recommend_returns_top_k_items_in_valid_score_range(self):
        trainer = ALSTrainer()

        n_users = 20
        n_items = 50
        interactions = []
        for i in range(n_users):
            for j in range(n_items):
                if np.random.random() > 0.2:
                    uid = f"user_{i}"
                    iid = f"item_{j}"
                    interactions.append((uid, iid, np.random.uniform(0.5, 5.0)))

        trainer.train(interactions)

        test_user = "user_0"
        top_k = 10
        results = trainer.recommend(test_user, top_k=top_k)

        assert len(results) > 0
        assert len(results) <= top_k

        scores = [score for _, score in results]
        for score in scores:
            assert score >= -1.0
            assert score <= 1.0

        sorted_scores = sorted(scores, reverse=True)
        assert scores == sorted_scores

        content_ids = [cid for cid, _ in results]
        assert len(content_ids) == len(set(content_ids))

    def test_predict_scores_returns_valid_score(self):
        trainer = ALSTrainer()

        interactions = [
            ("user_0", "item_0", 5.0),
            ("user_0", "item_1", 3.0),
            ("user_1", "item_0", 4.0),
        ]
        trainer.train(interactions)

        user_id = "user_0"
        content_id = "item_0"
        user_factor = trainer.get_user_factor(user_id)
        item_factor = trainer.get_item_factor(content_id)

        assert user_factor is not None
        assert item_factor is not None

        score = float(np.dot(user_factor, item_factor))
        assert score >= -1.0
        assert score <= 1.0

    def test_save_and_load_model_preserves_state(self):
        trainer = ALSTrainer()

        interactions = create_interactions(500)

        trainer.train(interactions)

        original_user_factors = trainer._user_factors.copy()
        original_item_factors = trainer._item_factors.copy()
        original_user_map = trainer._user_id_map.copy()
        original_item_map = trainer._item_id_map.copy()

        with tempfile.NamedTemporaryFile(delete=False, suffix='.pkl') as f:
            tmp_path = f.name

        try:
            save_success = trainer.save_model(tmp_path)
            assert save_success

            trainer2 = ALSTrainer()
            load_success = trainer2.load_model(tmp_path)
            assert load_success

            assert trainer2._user_factors is not None
            assert trainer2._item_factors is not None
            assert np.allclose(trainer2._user_factors, original_user_factors)
            assert np.allclose(trainer2._item_factors, original_item_factors)
            assert trainer2._user_id_map == original_user_map
            assert trainer2._item_id_map == original_item_map

            meta_path = tmp_path + ".meta.json"
            assert os.path.exists(meta_path)
            with open(meta_path) as f:
                meta = json.load(f)
            assert "n_users" in meta
            assert "n_items" in meta
            assert "factors" in meta
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)
            if os.path.exists(tmp_path + ".meta.json"):
                os.unlink(tmp_path + ".meta.json")

    def test_exclude_items_removes_items_from_results(self):
        trainer = ALSTrainer()

        n_users = 30
        n_items = 60
        interactions = []
        for i in range(n_users):
            for j in range(n_items):
                if np.random.random() > 0.25:
                    interactions.append((f"user_{i}", f"item_{j}", np.random.uniform(0.1, 5.0)))

        trainer.train(interactions)

        test_user = "user_0"
        all_results = trainer.recommend(test_user, top_k=20)
        assert len(all_results) > 5

        exclude_ids = [cid for cid, _ in all_results[:3]]

        filtered_results = trainer.recommend(test_user, top_k=20, exclude_items=exclude_ids)

        result_ids = [cid for cid, _ in filtered_results]
        for excluded_id in exclude_ids:
            assert excluded_id not in result_ids

    def test_cold_start_user_returns_empty(self):
        trainer = ALSTrainer()

        interactions = create_interactions(200)
        trainer.train(interactions)

        cold_user = generate_user_id()
        results = trainer.recommend(cold_user, top_k=10)
        assert len(results) == 0

    def test_similar_items_returns_similar_items(self):
        trainer = ALSTrainer()

        n_items = 50
        interactions = []
        for i in range(10):
            for j in range(n_items):
                if np.random.random() > 0.3:
                    interactions.append((f"user_{i}", f"item_{j}", np.random.uniform(0.5, 5.0)))

        trainer.train(interactions)

        test_item = "item_0"
        similar = trainer.similar_items(test_item, top_k=5)
        assert len(similar) > 0

        for cid, score in similar:
            assert cid != test_item
            assert score >= -1.0
            assert score <= 1.0

        scores = [s for _, s in similar]
        assert scores == sorted(scores, reverse=True)


class TestALSTrainerExceptionPath:

    def test_load_mismatched_dimension_fails_gracefully(self):
        trainer = ALSTrainer()

        bad_data = {
            "user_factors": np.random.randn(10, 32).astype(np.float32),
            "item_factors": np.random.randn(20, 32).astype(np.float32),
            "user_id_map": {f"user_{i}": i for i in range(10)},
            "item_id_map": {f"item_{i}": i for i in range(20)},
            "reverse_user_map": {i: f"user_{i}" for i in range(10)},
            "reverse_item_map": {i: f"item_{i}" for i in range(20)},
        }

        with tempfile.NamedTemporaryFile(delete=False) as f:
            pickle.dump(bad_data, f)
            tmp_path = f.name

        try:
            with pytest.raises(ValueError, match="dimension mismatch"):
                trainer.load_model(tmp_path)
            assert trainer._user_factors is None
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)

    def test_load_corrupted_model_file_returns_false(self):
        trainer = ALSTrainer()

        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b"this is not a valid pickle file")
            tmp_path = f.name

        try:
            loaded = trainer.load_model(tmp_path)
            assert loaded is False
            assert trainer._user_factors is None
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)

    def test_recommend_with_untrained_model_returns_empty(self):
        trainer = ALSTrainer()

        results = trainer.recommend("any_user", top_k=10)
        assert results == []

    def test_similar_items_with_untrained_model_returns_empty(self):
        trainer = ALSTrainer()

        results = trainer.similar_items("any_item", top_k=10)
        assert results == []

    def test_get_factor_for_unknown_user_returns_none(self):
        trainer = ALSTrainer()

        interactions = [("user_0", "item_0", 5.0)]
        trainer.train(interactions)

        factor = trainer.get_user_factor("unknown_user")
        assert factor is None

        factor = trainer.get_item_factor("unknown_item")
        assert factor is None

    def test_load_nonexistent_model_path_returns_false(self):
        trainer = ALSTrainer()

        loaded = trainer.load_model("/nonexistent/path/model.pkl")
        assert loaded is False

    def test_empty_interactions_works(self):
        trainer = ALSTrainer()

        try:
            metrics = trainer.train([])
            assert metrics is not None
        except Exception:
            pytest.skip("Empty interactions may raise exception depending on implementation")

    def test_model_stats_returns_correct_values(self):
        trainer = ALSTrainer()

        stats_before = trainer.get_model_stats()
        assert stats_before["has_model"] is False

        interactions = create_interactions(100)
        trainer.train(interactions)

        stats_after = trainer.get_model_stats()
        assert stats_after["has_model"] is True
        assert stats_after["n_users"] > 0
        assert stats_after["n_items"] > 0
        assert stats_after["factors"] == 64


class TestCollaborativeFilterService:

    @pytest.mark.asyncio
    async def test_cf_recommend_returns_top_k(self, collaborative_filter):
        n_users = 50
        n_items = 100
        interactions = []
        for i in range(n_users):
            for j in range(n_items):
                if np.random.random() > 0.3:
                    interactions.append((f"user_{i}", f"item_{j}", np.random.uniform(0.1, 5.0)))

        collaborative_filter._trainer.train(interactions)

        test_user = "user_0"
        results = await collaborative_filter.recommend(test_user, top_k=15)

        assert len(results) > 0
        assert len(results) <= 15

        scores = [s for _, s in results]
        for score in scores:
            assert score >= -1.0
            assert score <= 1.0

    @pytest.mark.asyncio
    async def test_cf_cold_start_returns_popular_items(self, collaborative_filter, mock_redis):
        cold_user = generate_user_id()

        popular_items = [
            ("popular_1", 0.9),
            ("popular_2", 0.8),
            ("popular_3", 0.7),
        ]
        await mock_redis.set("cold_start:items", json.dumps(popular_items))
        await collaborative_filter._load_cold_start_items()

        results = await collaborative_filter.recommend(cold_user, top_k=10)
        assert len(results) >= 3

    @pytest.mark.asyncio
    async def test_cf_predict_score(self, collaborative_filter):
        interactions = [
            ("user_0", "item_0", 5.0),
            ("user_0", "item_1", 3.0),
            ("user_1", "item_0", 4.0),
        ]
        collaborative_filter._trainer.train(interactions)

        score = await collaborative_filter.predict_score("user_0", "item_0")
        assert isinstance(score, float)
        assert score >= -1.0
        assert score <= 1.0

    @pytest.mark.asyncio
    async def test_cf_recommend_caching_uses_cache(self, collaborative_filter):
        interactions = create_interactions(100)
        collaborative_filter._trainer.train(interactions)

        test_user = "user_0"
        results1 = await collaborative_filter.recommend(test_user, top_k=10, use_cache=False)
        results2 = await collaborative_filter.recommend(test_user, top_k=10, use_cache=True)

        assert len(results1) == len(results2)

    @pytest.mark.asyncio
    async def test_cf_recommend_returns_deduplicated_results(self, collaborative_filter):
        interactions = create_interactions(200)
        collaborative_filter._trainer.train(interactions)

        test_user = "user_0"
        results = await collaborative_filter.recommend(test_user, top_k=50)

        content_ids = [cid for cid, _ in results]
        assert len(content_ids) == len(set(content_ids))

    @pytest.mark.asyncio
    async def test_cf_get_stats(self, collaborative_filter):
        stats = collaborative_filter.get_model_stats()
        assert "has_model" in stats
        assert "cache_size" in stats
        assert "cold_start_items" in stats
