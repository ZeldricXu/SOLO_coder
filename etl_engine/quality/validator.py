from collections import Counter

import pandas as pd
from scipy.stats import ks_2samp

import great_expectations as ge

from etl_engine.quality.rules import QualityRule
from etl_engine.quality.result import RuleResult, ValidationResult


class QualityValidator:
    def __init__(self, rules: list[QualityRule]) -> None:
        self.rules = rules

    def validate(
        self,
        df: pd.DataFrame,
        reference_df: pd.DataFrame | None = None,
    ) -> ValidationResult:
        rule_results: list[RuleResult] = []

        for rule in self.rules:
            match rule.rule_type:
                case "null_rate":
                    result = self._check_null_rate(df, rule)
                case "uniqueness":
                    result = self._check_uniqueness(df, rule)
                case "value_range":
                    result = self._check_value_range(df, rule)
                case "distribution_drift":
                    result = self._check_distribution_drift(df, reference_df, rule)
                case "custom":
                    result = self._check_custom(df, rule)
                case _:
                    result = RuleResult(
                        rule_type=rule.rule_type,
                        column=rule.column,
                        passed=False,
                        details={"error": f"unknown rule_type: {rule.rule_type}"},
                        strategy=rule.strategy,
                    )
            rule_results.append(result)

        passed_count = sum(1 for r in rule_results if r.passed)
        failed_count = len(rule_results) - passed_count
        blocked = any(not r.passed and r.strategy == "block" for r in rule_results)

        type_counts: Counter = Counter()
        type_pass: Counter = Counter()
        for r in rule_results:
            type_counts[r.rule_type] += 1
            if r.passed:
                type_pass[r.rule_type] += 1

        summary = {
            "by_type": {
                rt: {
                    "total": type_counts[rt],
                    "passed": type_pass[rt],
                    "failed": type_counts[rt] - type_pass[rt],
                }
                for rt in type_counts
            },
            "pass_rate": passed_count / len(rule_results) if rule_results else 1.0,
        }

        return ValidationResult(
            passed=failed_count == 0,
            total_rules=len(rule_results),
            passed_rules=passed_count,
            failed_rules=failed_count,
            blocked=blocked,
            rule_results=rule_results,
            summary=summary,
        )

    def _check_null_rate(self, df: pd.DataFrame, rule: QualityRule) -> RuleResult:
        max_null_rate = rule.params.get("max_null_rate", 0.05)
        col = rule.column

        if col is None:
            actual = float(df.isnull().sum().sum() / df.size)
        else:
            if col not in df.columns:
                return RuleResult(
                    rule_type=rule.rule_type,
                    column=col,
                    passed=False,
                    details={"error": f"column '{col}' not found"},
                    strategy=rule.strategy,
                )
            actual = float(df[col].isnull().sum() / len(df))

        passed = actual <= max_null_rate
        return RuleResult(
            rule_type=rule.rule_type,
            column=col,
            passed=passed,
            actual_value=actual,
            expected_threshold=max_null_rate,
            details={"max_null_rate": max_null_rate, "actual_null_rate": actual},
            strategy=rule.strategy,
        )

    def _check_uniqueness(self, df: pd.DataFrame, rule: QualityRule) -> RuleResult:
        expect_unique = rule.params.get("expect_unique", True)
        col = rule.column

        if col is None:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": "uniqueness rule requires a column"},
                strategy=rule.strategy,
            )

        if col not in df.columns:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": f"column '{col}' not found"},
                strategy=rule.strategy,
            )

        total = len(df[col])
        n_unique = df[col].nunique()
        uniqueness_rate = float(n_unique / total) if total > 0 else 1.0

        passed = uniqueness_rate >= rule.threshold if expect_unique else uniqueness_rate < rule.threshold
        return RuleResult(
            rule_type=rule.rule_type,
            column=col,
            passed=passed,
            actual_value=uniqueness_rate,
            expected_threshold=rule.threshold,
            details={
                "expect_unique": expect_unique,
                "total_rows": total,
                "unique_values": n_unique,
                "uniqueness_rate": uniqueness_rate,
            },
            strategy=rule.strategy,
        )

    def _check_value_range(self, df: pd.DataFrame, rule: QualityRule) -> RuleResult:
        min_value = rule.params["min_value"]
        max_value = rule.params["max_value"]
        col = rule.column

        if col is None:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": "value_range rule requires a column"},
                strategy=rule.strategy,
            )

        if col not in df.columns:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": f"column '{col}' not found"},
                strategy=rule.strategy,
            )

        series = df[col].dropna()
        if len(series) == 0:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=True,
                actual_value=None,
                expected_threshold=rule.threshold,
                details={"message": "no non-null values to check"},
                strategy=rule.strategy,
            )

        in_range = float(((series >= min_value) & (series <= max_value)).sum() / len(series))
        passed = in_range >= rule.threshold

        return RuleResult(
            rule_type=rule.rule_type,
            column=col,
            passed=passed,
            actual_value=in_range,
            expected_threshold=rule.threshold,
            details={
                "min_value": min_value,
                "max_value": max_value,
                "in_range_rate": in_range,
                "actual_min": float(series.min()),
                "actual_max": float(series.max()),
            },
            strategy=rule.strategy,
        )

    def _check_distribution_drift(
        self,
        df: pd.DataFrame,
        reference_df: pd.DataFrame | None,
        rule: QualityRule,
    ) -> RuleResult:
        col = rule.column
        reference_stats = rule.params.get("reference_stats", {})
        drift_threshold = rule.params.get("drift_threshold", 0.1)

        if col is None:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": "distribution_drift rule requires a column"},
                strategy=rule.strategy,
            )

        if col not in df.columns:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": f"column '{col}' not found"},
                strategy=rule.strategy,
            )

        current = df[col].dropna()

        if reference_df is not None:
            if col not in reference_df.columns:
                return RuleResult(
                    rule_type=rule.rule_type,
                    column=col,
                    passed=False,
                    details={"error": f"column '{col}' not found in reference_df"},
                    strategy=rule.strategy,
                )
            reference = reference_df[col].dropna()
        elif reference_stats:
            import numpy as np

            mean = reference_stats.get("mean", 0)
            std = reference_stats.get("std", 1)
            quantiles = reference_stats.get("quantiles", {})
            n = len(current) if len(current) > 0 else 1000
            reference = pd.Series(np.random.normal(mean, std, n))
        else:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": "no reference data or reference_stats provided"},
                strategy=rule.strategy,
            )

        if len(current) == 0 or len(reference) == 0:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=True,
                actual_value=None,
                expected_threshold=drift_threshold,
                details={"message": "insufficient data for drift check"},
                strategy=rule.strategy,
            )

        ks_stat, p_value = ks_2samp(reference, current)
        passed = ks_stat <= drift_threshold

        return RuleResult(
            rule_type=rule.rule_type,
            column=col,
            passed=passed,
            actual_value=float(ks_stat),
            expected_threshold=drift_threshold,
            details={
                "ks_statistic": float(ks_stat),
                "p_value": float(p_value),
                "drift_threshold": drift_threshold,
            },
            strategy=rule.strategy,
        )

    def _check_custom(self, df: pd.DataFrame, rule: QualityRule) -> RuleResult:
        expectation_type = rule.params["expectation_type"]
        kwargs = rule.params.get("kwargs", {})
        col = rule.column

        ge_df = ge.dataset.PandasDataset(df)

        if col is not None:
            kwargs["column"] = col

        expectation_method = getattr(ge_df, expectation_type, None)
        if expectation_method is None:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": f"unknown GE expectation: {expectation_type}"},
                strategy=rule.strategy,
            )

        try:
            ge_result = expectation_method(**kwargs)
        except Exception as exc:
            return RuleResult(
                rule_type=rule.rule_type,
                column=col,
                passed=False,
                details={"error": str(exc)},
                strategy=rule.strategy,
            )

        passed = ge_result.success
        return RuleResult(
            rule_type=rule.rule_type,
            column=col,
            passed=passed,
            actual_value=None,
            expected_threshold=rule.threshold,
            details={
                "expectation_type": expectation_type,
                "kwargs": kwargs,
                "ge_result": ge_result.result if hasattr(ge_result, "result") else {},
            },
            strategy=rule.strategy,
        )
