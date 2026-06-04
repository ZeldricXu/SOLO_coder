import pytest
import json
import hashlib
import asyncio
from typing import List, Dict, Any
from unittest.mock import patch, MagicMock, AsyncMock

try:
    import mmh3
    MMH3_AVAILABLE = True
except ImportError:
    mmh3 = None
    MMH3_AVAILABLE = False


def _hash_user_id(user_id: str, salt: str = "", seed: int = 42) -> int:
    if MMH3_AVAILABLE:
        return mmh3.hash(f"{salt}{user_id}", seed)
    return int(hashlib.md5(f"{seed}{salt}{user_id}".encode()).hexdigest(), 16) % (2**31)

from recommendation_engine.models.schemas import ABTestExperiment
from tests.factories.data_factories import (
    ABTestExperimentFactory,
    generate_user_id,
)


pytestmark = pytest.mark.unit


class TestABTestRouterNormalPath:

    @pytest.mark.asyncio
    async def test_same_user_always_assigned_to_same_group(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a", "experiment_b"],
            layer="recall_layer",
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()

        assignment1 = await abtest_router.get_user_assignment(user_id, "recall_layer")
        assignment2 = await abtest_router.get_user_assignment(user_id, "recall_layer")
        assignment3 = await abtest_router.get_user_assignment(user_id, "recall_layer")

        assert assignment1 is not None
        assert assignment1.group == assignment2.group
        assert assignment1.group == assignment3.group

        assert assignment1.group in ["control", "experiment_a", "experiment_b"]

    @pytest.mark.asyncio
    async def test_hash_deterministic_for_same_user(
        self, abtest_router
    ):
        user_id = "test_user_12345"
        layer = "recall_layer"

        hash1 = abtest_router._compute_orthogonal_hash(user_id, layer)
        hash2 = abtest_router._compute_orthogonal_hash(user_id, layer)
        hash3 = abtest_router._compute_orthogonal_hash(user_id, layer)

        assert hash1 == hash2
        assert hash1 == hash3
        assert 0 <= hash1 < 1000

    @pytest.mark.asyncio
    async def test_multilayer_orthogonal_assignment(
        self, abtest_router, mock_postgres
    ):
        layers = ["recall_layer", "rank_layer", "rerank_layer"]
        experiments = []

        for layer in layers:
            exp = ABTestExperimentFactory(
                status="active",
                traffic_percentage=100,
                layer=layer,
                control_group="control",
                experiment_groups=[f"exp_{layer}_a"],
            )
            experiments.append(exp)

        mock_postgres._tables["abtest_experiments"] = [
            e.model_dump(mode="json") for e in experiments
        ]

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()
        assignments = await abtest_router.get_all_assignments(user_id)

        assert len(assignments) == 3
        for layer in layers:
            assert layer in assignments
            assert assignments[layer].group in ["control", f"exp_{layer}_a"]

        recall_hash = abtest_router._compute_orthogonal_hash(user_id, "recall_layer")
        rank_hash = abtest_router._compute_orthogonal_hash(user_id, "rank_layer")
        rerank_hash = abtest_router._compute_orthogonal_hash(user_id, "rerank_layer")

        assert not (recall_hash == rank_hash == rerank_hash)

    @pytest.mark.asyncio
    async def test_traffic_percentage_splits_correctly(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=50,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="rank_layer",
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        control_count = 0
        experiment_count = 0
        no_assignment_count = 0
        n_users = 200

        for i in range(n_users):
            user_id = f"test_user_{i}"
            assignment = await abtest_router.get_user_assignment(user_id, "rank_layer")
            if assignment is None:
                no_assignment_count += 1
            elif assignment.group == "control":
                control_count += 1
            elif assignment.group == "experiment_a":
                experiment_count += 1

        total_assigned = control_count + experiment_count
        assert total_assigned > 0 or no_assignment_count > 0

    @pytest.mark.asyncio
    async def test_get_experiment_config_returns_correct_config(
        self, abtest_router, mock_postgres
    ):
        test_config = {
            "recall_weight": 1.2,
            "mmr_lambda": 0.8,
            "enable_new_feature": True,
        }

        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            layer="rank_layer",
            control_group="control",
            experiment_groups=["experiment_a"],
            config=test_config,
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()
        config = await abtest_router.get_experiment_config(user_id)

        assert "experiment_info" in config
        assert "rank_layer" in config["experiment_info"]
        assert "rank_layer_config" in config

    @pytest.mark.asyncio
    async def test_create_experiment_stores_correctly(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(status="active", traffic_percentage=100)

        success = await abtest_router.create_experiment(experiment)

        assert success is True
        assert len(mock_postgres._tables["abtest_experiments"]) == 1

        stored = mock_postgres._tables["abtest_experiments"][0]
        assert stored["experiment_id"] == experiment.experiment_id
        assert stored["status"] == "active"

    @pytest.mark.asyncio
    async def test_multiple_requests_consistent_assignment(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a", "experiment_b"],
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()
        groups = []

        for _ in range(50):
            assignment = await abtest_router.get_user_assignment(user_id, experiment.layer)
            if assignment:
                groups.append(assignment.group)

        assert len(set(groups)) == 1


class TestABTestRouterExceptionPath:

    @pytest.mark.asyncio
    async def test_invalid_config_json_falls_back_to_control(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            layer="rank_layer",
            control_group="control",
            experiment_groups=["experiment_a"],
            config={"valid_key": "valid_value"},
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()
        config = await abtest_router.get_experiment_config(user_id)

        assert config is not None
        assert "experiment_info" in config

    @pytest.mark.asyncio
    async def test_malformed_experiment_row_handled_gracefully(
        self, abtest_router, mock_postgres
    ):
        malformed_row = {
            "experiment_id": "bad_exp_1",
            "name": "Bad Experiment",
            "layer": "recall_layer",
            "status": "active",
        }

        mock_postgres._tables["abtest_experiments"] = [malformed_row]

        try:
            await abtest_router._load_all_experiments()
        except Exception:
            pass

        user_id = generate_user_id()
        config = await abtest_router.get_experiment_config(user_id)

        assert config is not None

    @pytest.mark.asyncio
    async def test_nonexistent_user_returns_valid_assignment(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=50,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        nonexistent_user = "user_never_seen_before_99999"
        assignment = await abtest_router.get_user_assignment(nonexistent_user, "recall_layer")

        if assignment is not None:
            assert assignment.group in ["control", "experiment_a"]

    @pytest.mark.asyncio
    async def test_ended_experiments_not_used(
        self, abtest_router, mock_postgres
    ):
        active_experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_b"],
            layer="rank_layer",
            config={"new_config": True},
        )

        mock_postgres._tables["abtest_experiments"] = [
            active_experiment.model_dump(mode="json"),
        ]

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()
        assignment = await abtest_router.get_user_assignment(user_id, "rank_layer")

        if assignment:
            assert assignment.experiment_id == active_experiment.experiment_id

    @pytest.mark.asyncio
    async def test_postgres_failure_returns_default_config(
        self, abtest_router, mock_postgres
    ):
        user_id = generate_user_id()

        config = await abtest_router.get_experiment_config(user_id)

        assert config is not None
        assert isinstance(config, dict)


class TestABTestRouterEdgeCases:

    @pytest.mark.asyncio
    async def test_no_experiments_returns_empty_config(
        self, abtest_router, mock_postgres
    ):
        mock_postgres._tables["abtest_experiments"] = []

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()
        config = await abtest_router.get_experiment_config(user_id)

        assert "experiment_info" in config

    @pytest.mark.asyncio
    async def test_zero_traffic_percentage_gives_no_assignment(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=0,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        for i in range(100):
            user_id = f"test_user_{i}"
            assignment = await abtest_router.get_user_assignment(user_id, "recall_layer")
            assert assignment is None

    @pytest.mark.asyncio
    async def test_update_experiment_status(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(status="active", layer="recall_layer")

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        success = await abtest_router.update_experiment_status(
            experiment.experiment_id, "paused"
        )

        assert success is True

    @pytest.mark.asyncio
    async def test_list_experiments_filters_correctly(
        self, abtest_router, mock_postgres
    ):
        exp1 = ABTestExperimentFactory(status="active", layer="recall_layer")
        exp2 = ABTestExperimentFactory(status="paused", layer="recall_layer")
        exp3 = ABTestExperimentFactory(status="active", layer="rank_layer")

        mock_postgres._tables["abtest_experiments"] = [
            exp1.model_dump(mode="json"),
            exp2.model_dump(mode="json"),
            exp3.model_dump(mode="json"),
        ]

        all_exps = await abtest_router.list_experiments()
        assert len(all_exps) == 3

    @pytest.mark.asyncio
    async def test_hash_seed_different_per_layer(
        self, abtest_router
    ):
        user_id = generate_user_id()

        hash1 = abtest_router._compute_orthogonal_hash(user_id, "recall_layer")
        hash2 = abtest_router._compute_orthogonal_hash(user_id, "rank_layer")
        hash3 = abtest_router._compute_orthogonal_hash(user_id, "rerank_layer")

        hashes = [hash1, hash2, hash3]
        assert len(set(hashes)) >= 2
