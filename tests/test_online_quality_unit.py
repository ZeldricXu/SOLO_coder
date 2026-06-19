import pytest
import asyncio
import pandas as pd
from unittest.mock import MagicMock, patch

from etl_engine.quality.online_checkpoint import (
    CheckpointConfig,
    CheckpointResult,
    OnlineQualityChecker,
    LIGHTWEIGHT_RULE_TYPES,
)
from etl_engine.quality.pipeline_injector import CheckpointInjector
from etl_engine.quality.rules import QualityRule
from etl_engine.quality.result import ValidationResult, RuleResult
from etl_engine.transform.engine import TransformEngine
from etl_engine.exceptions import OnlineValidationError, QualityCheckTimeoutError


@pytest.mark.unit
@pytest.mark.online_quality
class TestCheckpointConfigModel:
    def test_checkpoint_config_default_values(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
            )
        ]
        config = CheckpointConfig(
            checkpoint_id="cp_001",
            position="post_transform",
            rules=rules,
        )
        assert config.checkpoint_id == "cp_001"
        assert config.position == "post_transform"
        assert len(config.rules) == 1
        assert config.timeout_seconds == 3.0
        assert config.on_failure == "alert_only"
        assert config.sample_fraction == 0.1
        assert config.max_sample_rows == 10000

    def test_checkpoint_config_custom_values(self):
        rules = [
            QualityRule(
                rule_type="value_range",
                column="age",
                params={"min_value": 0, "max_value": 120},
            )
        ]
        config = CheckpointConfig(
            checkpoint_id="cp_002",
            position="pre_load",
            rules=rules,
            timeout_seconds=5.0,
            on_failure="abort",
            sample_fraction=0.2,
            max_sample_rows=5000,
        )
        assert config.timeout_seconds == 5.0
        assert config.on_failure == "abort"
        assert config.sample_fraction == 0.2
        assert config.max_sample_rows == 5000

    def test_checkpoint_config_invalid_position(self):
        with pytest.raises(Exception):
            CheckpointConfig(
                checkpoint_id="cp_003",
                position="invalid_position",
                rules=[],
            )


@pytest.mark.unit
@pytest.mark.online_quality
class TestCheckpointResultModel:
    def test_checkpoint_result_full(self):
        rule_result = RuleResult(
            rule_type="null_rate",
            column="name",
            passed=True,
            actual_value=0.0,
            expected_threshold=0.05,
        )
        validation_result = ValidationResult(
            passed=True,
            total_rules=1,
            passed_rules=1,
            failed_rules=0,
            blocked=False,
            rule_results=[rule_result],
            summary={"pass_rate": 1.0},
        )
        result = CheckpointResult(
            checkpoint_id="cp_001",
            passed=True,
            duration_seconds=0.123,
            sample_rows_checked=100,
            validation_result=validation_result,
            action_taken="continued",
            error=None,
        )
        assert result.checkpoint_id == "cp_001"
        assert result.passed is True
        assert result.duration_seconds == 0.123
        assert result.sample_rows_checked == 100
        assert result.validation_result is not None
        assert result.action_taken == "continued"
        assert result.error is None

    def test_checkpoint_result_failed_with_error(self):
        result = CheckpointResult(
            checkpoint_id="cp_002",
            passed=False,
            duration_seconds=0.5,
            sample_rows_checked=50,
            action_taken="aborted",
            error="Validation failed",
        )
        assert result.passed is False
        assert result.action_taken == "aborted"
        assert result.error == "Validation failed"


@pytest.mark.unit
@pytest.mark.online_quality
class TestOnlineQualityCheckerInit:
    def test_checker_init(self):
        rules1 = [QualityRule(rule_type="null_rate", column="name")]
        rules2 = [QualityRule(rule_type="value_range", column="age", params={"min_value": 0, "max_value": 100})]

        checkpoints = [
            CheckpointConfig(checkpoint_id="cp1", position="pre_transform", rules=rules1),
            CheckpointConfig(checkpoint_id="cp2", position="post_transform", rules=rules2),
        ]

        checker = OnlineQualityChecker(checkpoints)
        assert len(checker._checkpoints) == 2
        assert "cp1" in checker._checkpoints
        assert "cp2" in checker._checkpoints


@pytest.mark.unit
@pytest.mark.online_quality
class TestOnlineQualityCheckerGetConfig:
    def test_get_valid_checkpoint_config(self):
        rules = [QualityRule(rule_type="null_rate", column="name")]
        config = CheckpointConfig(checkpoint_id="cp1", position="post_transform", rules=rules)
        checker = OnlineQualityChecker([config])

        result = checker.get_checkpoint_config("cp1")
        assert result is not None
        assert result.checkpoint_id == "cp1"
        assert result.position == "post_transform"

    def test_get_invalid_checkpoint_config_returns_none(self):
        rules = [QualityRule(rule_type="null_rate", column="name")]
        config = CheckpointConfig(checkpoint_id="cp1", position="post_transform", rules=rules)
        checker = OnlineQualityChecker([config])

        result = checker.get_checkpoint_config("invalid_cp")
        assert result is None


@pytest.mark.unit
@pytest.mark.online_quality
class TestTakeSample:
    def test_take_sample_with_fraction(self):
        df = pd.DataFrame({"id": range(100000), "value": range(100000)})
        config = CheckpointConfig(
            checkpoint_id="cp1",
            position="post_transform",
            rules=[QualityRule(rule_type="null_rate", column="value")],
            sample_fraction=0.01,
            max_sample_rows=10000,
        )
        checker = OnlineQualityChecker([config])
        sample = checker._take_sample(df, config)
        assert len(sample) == 1000

    def test_take_sample_capped_at_max(self):
        df = pd.DataFrame({"id": range(100000), "value": range(100000)})
        config = CheckpointConfig(
            checkpoint_id="cp1",
            position="post_transform",
            rules=[QualityRule(rule_type="null_rate", column="value")],
            sample_fraction=0.1,
            max_sample_rows=500,
        )
        checker = OnlineQualityChecker([config])
        sample = checker._take_sample(df, config)
        assert len(sample) == 500

    def test_take_sample_small_dataframe(self):
        df = pd.DataFrame({"id": range(50), "value": range(50)})
        config = CheckpointConfig(
            checkpoint_id="cp1",
            position="post_transform",
            rules=[QualityRule(rule_type="null_rate", column="value")],
            sample_fraction=0.1,
            max_sample_rows=10000,
        )
        checker = OnlineQualityChecker([config])
        sample = checker._take_sample(df, config)
        assert len(sample) == 50


@pytest.mark.unit
@pytest.mark.online_quality
class TestOnlineCheckPassedNullRate:
    @pytest.mark.asyncio
    async def test_check_passed_clean_data(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
            )
        ]
        config = CheckpointConfig(
            checkpoint_id="cp_null_check",
            position="post_transform",
            rules=rules,
            on_failure="alert_only",
        )
        checker = OnlineQualityChecker([config])

        df = pd.DataFrame({"name": ["Alice", "Bob", "Charlie", "David", "Eve"]})
        result = await checker.run_checkpoint("cp_null_check", df)

        assert result.passed is True
        assert result.action_taken == "continued"
        assert result.sample_rows_checked == 5
        assert result.error is None


@pytest.mark.unit
@pytest.mark.online_quality
class TestOnlineCheckFailAlertOnly:
    @pytest.mark.asyncio
    async def test_check_fail_alert_only_no_exception(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
            )
        ]
        config = CheckpointConfig(
            checkpoint_id="cp_null_check",
            position="post_transform",
            rules=rules,
            on_failure="alert_only",
        )
        checker = OnlineQualityChecker([config])

        df = pd.DataFrame({"name": ["Alice", None, None, None, None, None, None, None, None, None]})
        result = await checker.run_checkpoint("cp_null_check", df)

        assert result.passed is False
        assert result.action_taken == "alert_sent"


@pytest.mark.unit
@pytest.mark.online_quality
class TestOnlineCheckFailAbort:
    @pytest.mark.asyncio
    async def test_check_fail_abort_raises_exception(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
            )
        ]
        config = CheckpointConfig(
            checkpoint_id="cp_null_check",
            position="post_transform",
            rules=rules,
            on_failure="abort",
        )
        checker = OnlineQualityChecker([config])

        df = pd.DataFrame({"name": ["Alice", None, None, None, None, None, None, None, None, None]})

        with pytest.raises(OnlineValidationError) as exc_info:
            await checker.run_checkpoint("cp_null_check", df)

        assert "cp_null_check" in str(exc_info.value)


@pytest.mark.unit
@pytest.mark.online_quality
class TestOnlineCheckTimeout:
    @pytest.mark.asyncio
    async def test_check_timeout_raises_or_returns_error(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
            )
        ]
        config = CheckpointConfig(
            checkpoint_id="cp_timeout",
            position="post_transform",
            rules=rules,
            timeout_seconds=0.1,
            on_failure="abort",
        )
        checker = OnlineQualityChecker([config])

        df = pd.DataFrame({"name": ["Alice", "Bob"]})

        async def slow_validate(*args, **kwargs):
            await asyncio.sleep(2)
            return ValidationResult(
                passed=True,
                total_rules=1,
                passed_rules=1,
                failed_rules=0,
                blocked=False,
                rule_results=[],
                summary={},
            )

        async def mock_wait_for(coro, timeout):
            raise asyncio.TimeoutError()

        with patch.object(checker, "get_checkpoint_config", return_value=config):
            with patch("asyncio.wait_for") as mock_wait_for_patch:
                mock_wait_for_patch.side_effect = mock_wait_for

                with pytest.raises((QualityCheckTimeoutError, OnlineValidationError)):
                    await checker.run_checkpoint("cp_timeout", df)


@pytest.mark.unit
@pytest.mark.online_quality
class TestOnlineCheckLightweightRulesOnly:
    def test_lightweight_rules_filtered(self):
        all_rules = [
            QualityRule(rule_type="null_rate", column="name"),
            QualityRule(rule_type="value_range", column="age", params={"min_value": 0, "max_value": 100}),
            QualityRule(rule_type="uniqueness", column="id"),
            QualityRule(rule_type="distribution_drift", column="score", params={"reference_stats": {"mean": 0}}),
            QualityRule(rule_type="custom", column="value", params={"expectation_type": "expect_column_to_exist"}),
        ]

        lightweight = [r for r in all_rules if r.rule_type in LIGHTWEIGHT_RULE_TYPES]
        assert len(lightweight) == 2
        assert lightweight[0].rule_type == "null_rate"
        assert lightweight[1].rule_type == "value_range"

        non_lightweight = [r for r in all_rules if r.rule_type not in LIGHTWEIGHT_RULE_TYPES]
        assert len(non_lightweight) == 3
        rule_types = {r.rule_type for r in non_lightweight}
        assert "uniqueness" in rule_types
        assert "distribution_drift" in rule_types
        assert "custom" in rule_types


@pytest.mark.unit
@pytest.mark.online_quality
class TestCheckpointInjectorTransformations:
    def test_inject_into_transformations_post(self):
        rules = [QualityRule(rule_type="null_rate", column="name")]
        checkpoints = [
            CheckpointConfig(checkpoint_id="cp_post", position="post_transform", rules=rules),
        ]
        injector = CheckpointInjector(checkpoints)

        transformations = [
            {"type": "sql", "expression": "SELECT * FROM df"},
            {"type": "udf", "expression": "lambda x: x"},
            {"type": "sql", "expression": "SELECT id, name FROM df"},
        ]

        result = injector.inject_into_transformations(transformations)
        assert len(result) == 4
        assert result[0]["type"] == "sql"
        assert result[1]["type"] == "udf"
        assert result[2]["type"] == "sql"
        assert result[3]["type"] == "quality_checkpoint"
        assert result[3]["config"]["checkpoint_id"] == "cp_post"

    def test_inject_into_transformations_pre_and_post(self):
        rules = [QualityRule(rule_type="null_rate", column="name")]
        checkpoints = [
            CheckpointConfig(checkpoint_id="cp_pre", position="pre_transform", rules=rules),
            CheckpointConfig(checkpoint_id="cp_post", position="post_transform", rules=rules),
        ]
        injector = CheckpointInjector(checkpoints)

        transformations = [
            {"type": "sql", "expression": "SELECT * FROM df"},
            {"type": "udf", "expression": "lambda x: x"},
            {"type": "sql", "expression": "SELECT id, name FROM df"},
        ]

        result = injector.inject_into_transformations(transformations)
        assert len(result) == 5
        assert result[0]["type"] == "quality_checkpoint"
        assert result[0]["config"]["checkpoint_id"] == "cp_pre"
        assert result[1]["type"] == "sql"
        assert result[2]["type"] == "udf"
        assert result[3]["type"] == "sql"
        assert result[4]["type"] == "quality_checkpoint"
        assert result[4]["config"]["checkpoint_id"] == "cp_post"


@pytest.mark.unit
@pytest.mark.online_quality
class TestCheckpointInjectorDAG:
    def test_inject_into_dag(self):
        rules = [QualityRule(rule_type="null_rate", column="name")]
        checkpoints = [
            CheckpointConfig(checkpoint_id="cp_pre", position="pre_transform", rules=rules),
            CheckpointConfig(checkpoint_id="cp_post", position="post_transform", rules=rules),
            CheckpointConfig(checkpoint_id="cp_load", position="pre_load", rules=rules),
        ]
        injector = CheckpointInjector(checkpoints)

        dag = {
            "nodes": [
                {"id": "extract", "type": "extract", "name": "Extract"},
                {"id": "transform1", "type": "transform", "name": "Transform 1"},
                {"id": "transform2", "type": "sql", "name": "Transform 2"},
                {"id": "load", "type": "load", "name": "Load"},
            ],
            "edges": [
                {"from": "extract", "to": "transform1"},
                {"from": "transform1", "to": "transform2"},
                {"from": "transform2", "to": "load"},
            ],
        }

        result = injector.inject_into_dag(dag)

        node_ids = [n["id"] for n in result["nodes"]]
        assert "checkpoint_cp_pre" in node_ids
        assert "checkpoint_cp_post" in node_ids
        assert "checkpoint_cp_load" in node_ids

        edge_pairs = [(e["from"], e["to"]) for e in result["edges"]]
        assert ("extract", "checkpoint_cp_pre") in edge_pairs
        assert ("checkpoint_cp_pre", "transform1") in edge_pairs
        assert ("transform2", "checkpoint_cp_post") in edge_pairs
        assert ("checkpoint_cp_post", "checkpoint_cp_load") in edge_pairs
        assert ("checkpoint_cp_load", "load") in edge_pairs


@pytest.mark.unit
@pytest.mark.online_quality
class TestTransformEngineWithCheckpoint:
    def test_transform_engine_checkpoint_passes(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
            )
        ]
        checkpoint_config = CheckpointConfig(
            checkpoint_id="cp_name_check",
            position="post_transform",
            rules=rules,
            on_failure="abort",
        )
        checker = OnlineQualityChecker([checkpoint_config])

        engine = TransformEngine()

        transformations = [
            {"type": "sql", "expression": "SELECT id, UPPER(name) as name FROM df"},
            {"type": "quality_checkpoint", "config": {"checkpoint_id": "cp_name_check"}},
        ]

        df = pd.DataFrame({"id": [1, 2, 3], "name": ["alice", "bob", "charlie"]})

        result = engine.apply(df, transformations, online_checker=checker)

        assert len(result) == 3
        assert list(result["name"]) == ["ALICE", "BOB", "CHARLIE"]


@pytest.mark.unit
@pytest.mark.online_quality
class TestTransformEngineCheckpointAbort:
    def test_transform_engine_checkpoint_abort_raises(self):
        rules = [
            QualityRule(
                rule_type="null_rate",
                column="name",
                params={"max_null_rate": 0.05},
            )
        ]
        checkpoint_config = CheckpointConfig(
            checkpoint_id="cp_name_check",
            position="post_transform",
            rules=rules,
            on_failure="abort",
        )
        checker = OnlineQualityChecker([checkpoint_config])

        engine = TransformEngine()

        transformations = [
            {"type": "sql", "expression": "SELECT id, UPPER(name) as name FROM df"},
            {"type": "quality_checkpoint", "config": {"checkpoint_id": "cp_name_check"}},
        ]

        df = pd.DataFrame({"id": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], "name": [
            "alice", None, None, None, None, None, None, None, None, None
        ]})

        with pytest.raises(Exception) as exc_info:
            engine.apply(df, transformations, online_checker=checker)

        assert "cp_name_check" in str(exc_info.value)
