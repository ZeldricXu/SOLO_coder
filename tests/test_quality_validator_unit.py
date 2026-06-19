import pytest
import pandas as pd

from etl_engine.quality import QualityValidator, ValidationResult, QualityRule
from etl_engine.quality.result import RuleResult


def _convert_expectations_to_rules(expectation_suite: dict) -> list[QualityRule]:
    rules = []
    for exp in expectation_suite.get("expectations", []):
        exp_type = exp["expectation_type"]
        kwargs = exp.get("kwargs", {})
        column = kwargs.get("column")

        if exp_type == "expect_column_values_to_not_be_null":
            rules.append(QualityRule(
                rule_type="null_rate",
                column=column,
                params={"max_null_rate": 0.05},
                threshold=1.0,
                strategy="alert",
            ))
        elif exp_type == "expect_column_values_to_be_unique":
            rules.append(QualityRule(
                rule_type="uniqueness",
                column=column,
                params={"expect_unique": True},
                threshold=1.0,
                strategy="alert",
            ))
        elif exp_type == "expect_column_values_to_be_between":
            rules.append(QualityRule(
                rule_type="value_range",
                column=column,
                params={
                    "min_value": kwargs.get("min_value"),
                    "max_value": kwargs.get("max_value"),
                },
                threshold=1.0,
                strategy="alert",
            ))
    return rules


@pytest.mark.unit
class TestExpectationSuiteParsing:
    def test_convert_to_quality_rules(self, expectation_suite_json):
        rules = _convert_expectations_to_rules(expectation_suite_json)

        assert len(rules) == 3
        assert rules[0].rule_type == "null_rate"
        assert rules[0].column == "name"
        assert rules[1].rule_type == "uniqueness"
        assert rules[1].column == "id"
        assert rules[2].rule_type == "value_range"
        assert rules[2].column == "value"


@pytest.mark.unit
class TestValidationReportStructure:
    def test_validation_result_structure(self, expectation_suite_json, sample_df):
        rules = _convert_expectations_to_rules(expectation_suite_json)
        validator = QualityValidator(rules)
        result = validator.validate(sample_df)

        assert isinstance(result, ValidationResult)
        assert result.total_rules == 3
        assert result.passed_rules == 3
        assert len(result.rule_results) == 3
        assert all(isinstance(rr, RuleResult) for rr in result.rule_results)


@pytest.mark.unit
class TestEachRuleResult:
    def test_each_rule_has_correct_fields(self, expectation_suite_json, sample_df):
        rules = _convert_expectations_to_rules(expectation_suite_json)
        validator = QualityValidator(rules)
        result = validator.validate(sample_df)

        null_rate_result = next(
            rr for rr in result.rule_results if rr.rule_type == "null_rate"
        )
        assert null_rate_result.column == "name"
        assert null_rate_result.passed is True
        assert null_rate_result.actual_value is not None
        assert null_rate_result.actual_value == 0.0

        uniqueness_result = next(
            rr for rr in result.rule_results if rr.rule_type == "uniqueness"
        )
        assert uniqueness_result.column == "id"
        assert uniqueness_result.passed is True
        assert uniqueness_result.actual_value is not None
        assert uniqueness_result.actual_value == 1.0

        value_range_result = next(
            rr for rr in result.rule_results if rr.rule_type == "value_range"
        )
        assert value_range_result.column == "value"
        assert value_range_result.passed is True
        assert value_range_result.actual_value is not None
        assert value_range_result.actual_value == 1.0


@pytest.mark.unit
class TestOverallPassedFlag:
    def test_passed_and_blocked_flags(self, expectation_suite_json, sample_df):
        rules = _convert_expectations_to_rules(expectation_suite_json)
        validator = QualityValidator(rules)
        result = validator.validate(sample_df)

        assert result.passed is True
        assert result.blocked is False
        assert result.failed_rules == 0


@pytest.mark.unit
class TestValidationReportSummary:
    def test_summary_pass_rate(self, expectation_suite_json, sample_df):
        rules = _convert_expectations_to_rules(expectation_suite_json)
        validator = QualityValidator(rules)
        result = validator.validate(sample_df)

        assert "summary" in result.model_dump() or result.summary is not None
        assert "pass_rate" in result.summary
        assert result.summary["pass_rate"] == 1.0
