import pytest
import json
from unittest.mock import patch, MagicMock, AsyncMock

from recommendation_engine.models.schemas import (
    ABTestExperiment,
    ExclusionPolicy,
)
from tests.factories.data_factories import (
    ABTestExperimentFactory,
    generate_user_id,
)

pytestmark = pytest.mark.unit


class TestExclusionPolicyModel:

    def test_user_tags_whitelist_blocks_missing_tag(self):
        policy = ExclusionPolicy(user_tags_whitelist=["employee"])
        result = policy.is_excluded("user_1", ["customer"])
        assert result is True

    def test_user_tags_whitelist_allows_matching_tag(self):
        policy = ExclusionPolicy(user_tags_whitelist=["employee"])
        result = policy.is_excluded("user_1", ["employee"])
        assert result is False

    def test_user_tags_whitelist_empty_allows_all(self):
        policy = ExclusionPolicy(user_tags_whitelist=[])
        result1 = policy.is_excluded("user_1", ["employee"])
        result2 = policy.is_excluded("user_2", ["customer"])
        result3 = policy.is_excluded("user_3", [])
        assert result1 is False
        assert result2 is False
        assert result3 is False

    def test_user_tags_blacklist_blocks_matching_tag(self):
        policy = ExclusionPolicy(user_tags_blacklist=["employee"])
        result = policy.is_excluded("user_1", ["employee"])
        assert result is True

    def test_user_tags_blacklist_allows_non_matching_tag(self):
        policy = ExclusionPolicy(user_tags_blacklist=["employee"])
        result = policy.is_excluded("user_1", ["customer"])
        assert result is False

    def test_user_tags_blacklist_empty_allows_all(self):
        policy = ExclusionPolicy(user_tags_blacklist=[])
        result1 = policy.is_excluded("user_1", ["employee"])
        result2 = policy.is_excluded("user_2", ["customer"])
        assert result1 is False
        assert result2 is False

    def test_user_id_pattern_blocks_matching_id(self):
        policy = ExclusionPolicy(user_id_pattern="^internal_.*")
        result = policy.is_excluded("internal_123", [])
        assert result is True

    def test_user_id_pattern_allows_non_matching_id(self):
        policy = ExclusionPolicy(user_id_pattern="^internal_.*")
        result = policy.is_excluded("user_123", [])
        assert result is False

    def test_user_id_whitelist_allows_listed_id(self):
        policy = ExclusionPolicy(user_id_whitelist=["user_1", "user_2"])
        result = policy.is_excluded("user_1", [])
        assert result is False

    def test_user_id_whitelist_blocks_unlisted_id(self):
        policy = ExclusionPolicy(user_id_whitelist=["user_1"])
        result = policy.is_excluded("user_99", [])
        assert result is True

    def test_user_id_blacklist_blocks_listed_id(self):
        policy = ExclusionPolicy(user_id_blacklist=["user_1"])
        result = policy.is_excluded("user_1", [])
        assert result is True

    def test_user_id_blacklist_allows_unlisted_id(self):
        policy = ExclusionPolicy(user_id_blacklist=["user_1"])
        result = policy.is_excluded("user_2", [])
        assert result is False

    def test_combined_policies_all_must_pass(self):
        policy = ExclusionPolicy(
            user_tags_whitelist=["employee"],
            user_id_pattern="^internal_.*"
        )
        result1 = policy.is_excluded("user_1", ["employee"])
        result2 = policy.is_excluded("user_2", ["contractor"])
        result3 = policy.is_excluded("internal_001", ["employee"])
        assert result1 is False
        assert result2 is True
        assert result3 is True


class TestExclusionPolicyInRouter:

    @pytest.mark.asyncio
    async def test_excluded_user_routed_to_control_group(
        self, abtest_router, mock_postgres, mock_redis
    ):
        exclusion_policy = ExclusionPolicy(user_id_blacklist=["internal_001"])
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
            exclusion_policy=exclusion_policy,
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        assignment = await abtest_router.get_user_assignment("internal_001", "recall_layer")

        assert assignment is not None
        assert assignment.group == "control"
        assert assignment.hash_value == -1
        assert assignment.group == experiment.control_group

    @pytest.mark.asyncio
    async def test_non_excluded_user_goes_through_normal_hashing(
        self, abtest_router, mock_postgres
    ):
        exclusion_policy = ExclusionPolicy(user_id_blacklist=["internal_001"])
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
            exclusion_policy=exclusion_policy,
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        assignment = await abtest_router.get_user_assignment("normal_user", "recall_layer")

        assert assignment is not None
        assert assignment.hash_value != -1
        assert assignment.group in ["control", "experiment_a"]

    @pytest.mark.asyncio
    async def test_exclusion_policy_runs_before_hashing(
        self, abtest_router, mock_postgres
    ):
        exclusion_policy = ExclusionPolicy(user_id_blacklist=["internal_001"])
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
            exclusion_policy=exclusion_policy,
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        with patch.object(abtest_router, '_compute_hash', side_effect=Exception("Hash should not be called")):
            assignment = await abtest_router.get_user_assignment("internal_001", "recall_layer")
            assert assignment is not None
            assert assignment.group == "control"
            assert assignment.hash_value == -1

    @pytest.mark.asyncio
    async def test_assign_user_respects_exclusion_policy(
        self, abtest_router, mock_postgres, mock_redis
    ):
        exclusion_policy = ExclusionPolicy(user_id_blacklist=["internal_001"])
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
            exclusion_policy=exclusion_policy,
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        assignment = await abtest_router.assign_user(
            "internal_001", experiment.experiment_id, group="experiment_a"
        )

        assert assignment is not None
        assert assignment.group == "control"
        assert assignment.hash_value == -1

    @pytest.mark.asyncio
    async def test_experiment_without_exclusion_policy_uses_normal_routing(
        self, abtest_router, mock_postgres
    ):
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
            exclusion_policy=None,
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment.model_dump(mode="json")
        ]

        await abtest_router._load_all_experiments()

        user_id = generate_user_id()
        assignment = await abtest_router.get_user_assignment(user_id, "recall_layer")

        assert assignment is not None
        assert assignment.hash_value != -1

    @pytest.mark.asyncio
    async def test_get_router_stats_includes_exclusion_flag(
        self, abtest_router, mock_postgres
    ):
        exclusion_policy = ExclusionPolicy(user_tags_blacklist=["employee"])
        experiment1 = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            layer="recall_layer",
            exclusion_policy=exclusion_policy,
        )
        experiment2 = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            layer="rank_layer",
            exclusion_policy=None,
        )

        mock_postgres._tables["abtest_experiments"] = [
            experiment1.model_dump(mode="json"),
            experiment2.model_dump(mode="json"),
        ]

        await abtest_router._load_all_experiments()

        stats = await abtest_router.get_router_stats()

        assert "active_experiments" in stats
        recall_exps = stats["active_experiments"]["recall_layer"]
        rank_exps = stats["active_experiments"]["rank_layer"]

        assert len(recall_exps) == 1
        assert recall_exps[0]["has_exclusion_policy"] is True
        assert "exclusion_policy" in recall_exps[0]

        assert len(rank_exps) == 1
        assert rank_exps[0]["has_exclusion_policy"] is False
        assert "exclusion_policy" not in rank_exps[0]

    @pytest.mark.asyncio
    async def test_exclusion_policy_stored_in_db(
        self, abtest_router, mock_postgres, mock_redis
    ):
        exclusion_policy = ExclusionPolicy(
            user_tags_whitelist=["employee"],
            user_id_blacklist=["internal_001", "internal_002"]
        )
        experiment = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control",
            experiment_groups=["experiment_a"],
            layer="recall_layer",
            exclusion_policy=exclusion_policy,
        )

        success = await abtest_router.create_experiment(experiment)
        assert success is True

        stored = mock_postgres._tables["abtest_experiments"][0]
        stored_policy = json.loads(stored["exclusion_policy"])

        assert stored_policy["user_tags_whitelist"] == ["employee"]
        assert stored_policy["user_id_blacklist"] == ["internal_001", "internal_002"]

        exp_data = stored.copy()
        exp_data["exclusion_policy"] = stored_policy
        exp_data["config"] = json.loads(exp_data["config"])
        if isinstance(exp_data["experiment_groups"], str):
            exp_data["experiment_groups"] = json.loads(exp_data["experiment_groups"])
        if "created_at" not in exp_data:
            exp_data["created_at"] = experiment.created_at
        if "updated_at" not in exp_data:
            exp_data["updated_at"] = experiment.updated_at
        mock_postgres._tables["abtest_experiments"] = [exp_data]

        assignment = await abtest_router.assign_user(
            "internal_001", experiment.experiment_id
        )
        assert assignment is not None
        assert assignment.group == "control"
        assert assignment.hash_value == -1

    @pytest.mark.asyncio
    async def test_user_tags_fetched_from_redis(
        self, abtest_router, mock_redis
    ):
        user_id = generate_user_id()
        profile_data = {
            "user_id": user_id,
            "interest_tags": [
                {"tag_id": "tag_employee", "tag_name": "Employee", "weight": 1.0}
            ],
            "offline_tags": [
                {"tag_id": "tag_vip", "tag_name": "VIP", "weight": 0.8}
            ]
        }
        await mock_redis.set(f"user:profile:{user_id}", profile_data)

        tags = await abtest_router._get_user_tags(user_id)

        assert "tag_employee" in tags
        assert "tag_vip" in tags
        assert len(tags) == 2

    @pytest.mark.asyncio
    async def test_multiple_experiments_each_with_own_exclusion_policy(
        self, abtest_router, mock_postgres, mock_redis
    ):
        policy1 = ExclusionPolicy(user_id_blacklist=["user_blocked_1"])
        exp1 = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control_1",
            experiment_groups=["exp_1_a"],
            layer="recall_layer",
            exclusion_policy=policy1,
        )

        policy2 = ExclusionPolicy(user_id_blacklist=["user_blocked_2"])
        exp2 = ABTestExperimentFactory(
            status="active",
            traffic_percentage=100,
            control_group="control_2",
            experiment_groups=["exp_2_a"],
            layer="rank_layer",
            exclusion_policy=policy2,
        )

        mock_postgres._tables["abtest_experiments"] = [
            exp1.model_dump(mode="json"),
            exp2.model_dump(mode="json"),
        ]

        await abtest_router._load_all_experiments()

        assignment1 = await abtest_router.get_user_assignment("user_blocked_1", "recall_layer")
        assert assignment1 is not None
        assert assignment1.group == "control_1"
        assert assignment1.hash_value == -1

        assignment2 = await abtest_router.get_user_assignment("user_blocked_1", "rank_layer")
        assert assignment2 is not None
        assert assignment2.hash_value != -1

        assignment3 = await abtest_router.get_user_assignment("user_blocked_2", "rank_layer")
        assert assignment3 is not None
        assert assignment3.group == "control_2"
        assert assignment3.hash_value == -1

        assignment4 = await abtest_router.get_user_assignment("user_blocked_2", "recall_layer")
        assert assignment4 is not None
        assert assignment4.hash_value != -1


class TestExclusionPolicyEdgeCases:

    def test_invalid_regex_pattern_gracefully_handled(self):
        try:
            policy = ExclusionPolicy(user_id_pattern="[invalid(regex")
            result = policy.is_excluded("test_user", [])
            assert isinstance(result, bool)
        except Exception:
            pytest.fail("Invalid regex pattern should be handled gracefully")

    def test_no_user_tags_provided_blacklist_blocks_all(self):
        policy = ExclusionPolicy(user_tags_blacklist=["employee"])
        result1 = policy.is_excluded("user_1", None)
        result2 = policy.is_excluded("user_2", [])
        assert result1 is False
        assert result2 is False

    def test_no_user_tags_provided_whitelist_blocks_all(self):
        policy = ExclusionPolicy(user_tags_whitelist=["employee"])
        result1 = policy.is_excluded("user_1", None)
        result2 = policy.is_excluded("user_2", [])
        assert result1 is True
        assert result2 is True

    def test_empty_exclusion_policy_allows_all(self):
        policy = ExclusionPolicy()
        result1 = policy.is_excluded("user_1", ["employee", "customer"])
        result2 = policy.is_excluded("user_2", [])
        result3 = policy.is_excluded("internal_123", None)
        assert result1 is False
        assert result2 is False
        assert result3 is False

    def test_multiple_conditions_use_and_logic(self):
        policy = ExclusionPolicy(
            user_tags_whitelist=["employee"],
            user_id_whitelist=["user_1", "user_2"],
            user_id_pattern="^internal_.*"
        )

        result1 = policy.is_excluded("user_1", ["employee"])
        result2 = policy.is_excluded("user_3", ["employee"])
        result3 = policy.is_excluded("user_1", ["customer"])
        result4 = policy.is_excluded("internal_1", ["employee"])

        assert result1 is False
        assert result2 is True
        assert result3 is True
        assert result4 is True
