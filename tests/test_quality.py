import pandas as pd
import pytest

from etl_engine.quality.result import RuleResult, ValidationResult
from etl_engine.quality.rules import QualityRule
from etl_engine.quality.validator import QualityValidator


def test_null_rate_check_pass(sample_df, sample_quality_rules):
    rule = QualityRule(**sample_quality_rules[0])
    validator = QualityValidator([rule])
    result = validator.validate(sample_df)
    assert result.passed is True
    assert result.failed_rules == 0


def test_null_rate_check_fail():
    df = pd.DataFrame({
        "id": [1, 2, 3],
        "name": [None, None, None],
        "value": [1.0, 2.0, 3.0],
    })
    rule = QualityRule(rule_type="null_rate", column="name", params={"max_null_rate": 0.05}, threshold=1.0)
    validator = QualityValidator([rule])
    result = validator.validate(df)
    assert result.passed is False
    assert result.failed_rules == 1


def test_uniqueness_check(sample_df, sample_quality_rules):
    rule = QualityRule(**sample_quality_rules[1])
    validator = QualityValidator([rule])
    result = validator.validate(sample_df)
    assert result.passed is True
    rr = result.rule_results[0]
    assert rr.actual_value == 1.0


def test_value_range_check(sample_df, sample_quality_rules):
    rule = QualityRule(**sample_quality_rules[2])
    validator = QualityValidator([rule])
    result = validator.validate(sample_df)
    assert result.passed is True
    rr = result.rule_results[0]
    assert rr.passed is True


def test_validation_result_structure(sample_df, sample_quality_rules):
    rules = [QualityRule(**r) for r in sample_quality_rules]
    validator = QualityValidator(rules)
    result = validator.validate(sample_df)

    assert hasattr(result, "passed")
    assert hasattr(result, "total_rules")
    assert hasattr(result, "passed_rules")
    assert hasattr(result, "failed_rules")
    assert hasattr(result, "blocked")
    assert hasattr(result, "rule_results")
    assert hasattr(result, "summary")

    assert result.total_rules == 3
    assert result.passed_rules + result.failed_rules == result.total_rules
    assert isinstance(result.rule_results, list)
    assert len(result.rule_results) == 3
    assert all(isinstance(rr, RuleResult) for rr in result.rule_results)
    assert "by_type" in result.summary
    assert "pass_rate" in result.summary


def test_block_strategy():
    df = pd.DataFrame({"id": [1, 2, 2], "value": [1.0, 2.0, 3.0]})
    rule = QualityRule(
        rule_type="uniqueness",
        column="id",
        params={"expect_unique": True},
        threshold=1.0,
        strategy="block",
    )
    validator = QualityValidator([rule])
    result = validator.validate(df)
    assert result.passed is False
    assert result.blocked is True
