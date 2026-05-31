from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

from streamsql.core.models import generate_id
from streamsql.modules.data_quality.rules import DataQualityRule, SeverityLevel


@dataclass
class RuleExecutionResult:
    rule_id: str = ""
    rule_name: str = ""
    passed: bool = False
    errors: list[str] = field(default_factory=list)
    stats: dict[str, Any] = field(default_factory=dict)
    execution_time_ms: float = 0.0
    severity: str = "error"

    def to_dict(self) -> dict[str, Any]:
        return {
            "rule_id": self.rule_id,
            "rule_name": self.rule_name,
            "passed": self.passed,
            "errors": self.errors[:10],
            "total_errors": len(self.errors),
            "stats": self.stats,
            "execution_time_ms": self.execution_time_ms,
            "severity": self.severity,
        }


@dataclass
class ValidationResult:
    validation_id: str = field(default_factory=lambda: generate_id("val"))
    table: str = ""
    total_rows: int = 0
    total_rules: int = 0
    passed_rules: int = 0
    failed_rules: int = 0
    execution_time_ms: float = 0.0
    started_at: float = field(default_factory=time.time)
    completed_at: float = 0.0
    rule_results: list[RuleExecutionResult] = field(default_factory=list)
    anomaly_rows: list[int] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.failed_rules == 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "validation_id": self.validation_id,
            "table": self.table,
            "total_rows": self.total_rows,
            "total_rules": self.total_rules,
            "passed_rules": self.passed_rules,
            "failed_rules": self.failed_rules,
            "passed": self.passed,
            "execution_time_ms": self.execution_time_ms,
            "started_at": self.started_at,
            "completed_at": self.completed_at,
            "rule_results": [r.to_dict() for r in self.rule_results],
            "anomaly_rows": self.anomaly_rows,
        }

    def get_failed_rules(self) -> list[RuleExecutionResult]:
        return [r for r in self.rule_results if not r.passed]

    def get_error_summary(self) -> dict[str, Any]:
        failed = self.get_failed_rules()
        by_severity: dict[str, int] = {}
        for r in failed:
            by_severity[r.severity] = by_severity.get(r.severity, 0) + 1

        return {
            "total_failed": len(failed),
            "by_severity": by_severity,
            "total_errors": sum(len(r.errors) for r in failed),
            "anomaly_rows_count": len(set(self.anomaly_rows)),
        }


class ValidationExecutor:
    def __init__(self, fail_fast: bool = False, max_errors_per_rule: int = 100):
        self.fail_fast = fail_fast
        self.max_errors_per_rule = max_errors_per_rule

    def execute(
        self,
        data: list[dict[str, Any]],
        rules: list[DataQualityRule],
        table_name: str = "",
    ) -> ValidationResult:
        result = ValidationResult(
            table=table_name,
            total_rows=len(data),
            total_rules=len(rules),
        )
        anomaly_rows: set[int] = set()

        for rule in rules:
            if not rule.enabled:
                continue

            rule_result = self._execute_rule(data, rule)
            result.rule_results.append(rule_result)

            if rule_result.passed:
                result.passed_rules += 1
            else:
                result.failed_rules += 1

                for error in rule_result.errors:
                    row_match = self._extract_row_number(error)
                    if row_match is not None:
                        anomaly_rows.add(row_match)

                if self.fail_fast and rule.severity == SeverityLevel.CRITICAL:
                    break

        result.completed_at = time.time()
        result.execution_time_ms = (result.completed_at - result.started_at) * 1000
        result.anomaly_rows = sorted(anomaly_rows)

        return result

    def _execute_rule(self, data: list[dict[str, Any]], rule: DataQualityRule) -> RuleExecutionResult:
        start_time = time.time()

        try:
            passed, errors, stats = rule.validate(data)

            if len(errors) > self.max_errors_per_rule:
                errors = errors[: self.max_errors_per_rule]
                errors.append(f"... and {len(errors) - self.max_errors_per_rule} more errors")

        except Exception as e:
            passed = False
            errors = [f"Rule execution failed: {str(e)}"]
            stats = {"exception": str(e)}

        execution_time = (time.time() - start_time) * 1000

        return RuleExecutionResult(
            rule_id=rule.rule_id,
            rule_name=rule.name,
            passed=passed,
            errors=errors,
            stats=stats,
            execution_time_ms=execution_time,
            severity=rule.severity.value,
        )

    def _extract_row_number(self, error: str) -> int | None:
        import re

        match = re.search(r"Row\s+(\d+)", error)
        if match:
            try:
                return int(match.group(1))
            except ValueError:
                return None
        return None

    def execute_parallel(
        self,
        data: list[dict[str, Any]],
        rules: list[DataQualityRule],
        table_name: str = "",
        max_workers: int = 4,
    ) -> ValidationResult:
        import concurrent.futures

        result = ValidationResult(
            table=table_name,
            total_rows=len(data),
            total_rules=len(rules),
        )
        anomaly_rows: set[int] = set()

        enabled_rules = [r for r in rules if r.enabled]

        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = [executor.submit(self._execute_rule, data, rule) for rule in enabled_rules]

            for future in concurrent.futures.as_completed(futures):
                rule_result = future.result()
                result.rule_results.append(rule_result)

                if rule_result.passed:
                    result.passed_rules += 1
                else:
                    result.failed_rules += 1

                    for error in rule_result.errors:
                        row_match = self._extract_row_number(error)
                        if row_match is not None:
                            anomaly_rows.add(row_match)

        result.completed_at = time.time()
        result.execution_time_ms = (result.completed_at - result.started_at) * 1000
        result.anomaly_rows = sorted(anomaly_rows)

        return result

    def validate_row(
        self,
        row: dict[str, Any],
        rules: list[DataQualityRule],
    ) -> tuple[bool, list[str]]:
        all_errors: list[str] = []
        for rule in rules:
            if not rule.enabled:
                continue
            passed, errors, _ = rule.validate([row])
            if not passed:
                all_errors.extend(errors)
        return len(all_errors) == 0, all_errors

    def validate_batch(
        self,
        batches: list[tuple[str, list[dict[str, Any]], list[DataQualityRule]]],
    ) -> list[ValidationResult]:
        results: list[ValidationResult] = []
        for table_name, data, rules in batches:
            results.append(self.execute(data, rules, table_name))
        return results
