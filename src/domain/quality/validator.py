import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional

from src.domain.quality.rule_engine import QualityRuleEngine, QualityRule, RuleViolation, Strictness, RuleType
from src.infrastructure.config.settings import QualityConfig

logger = logging.getLogger(__name__)


@dataclass
class ValidationResult:
    database_name: str
    table_name: str
    total_rules: int = 0
    passed_rules: int = 0
    failed_rules: int = 0
    error_rules: int = 0
    violations: List[RuleViolation] = field(default_factory=list)
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    row_count: int = 0

    @property
    def pass_rate(self) -> float:
        if self.total_rules == 0:
            return 1.0
        return self.passed_rules / self.total_rules

    @property
    def has_errors(self) -> bool:
        return any(v.strictness == Strictness.ERROR.value for v in self.violations)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "database_name": self.database_name,
            "table_name": self.table_name,
            "total_rules": self.total_rules,
            "passed_rules": self.passed_rules,
            "failed_rules": self.failed_rules,
            "error_rules": self.error_rules,
            "pass_rate": round(self.pass_rate, 4),
            "has_errors": self.has_errors,
            "violations": [v.to_dict() for v in self.violations],
            "started_at": self.started_at,
            "completed_at": self.completed_at,
            "row_count": self.row_count,
        }


@dataclass
class ScheduledCheck:
    check_id: str
    database_name: str
    table_name: str
    cron_expression: str
    enabled: bool = True
    last_run: Optional[str] = None
    last_result: Optional[ValidationResult] = None


class DataValidator:
    def __init__(
        self,
        rule_engine: Optional[QualityRuleEngine] = None,
        config: Optional[QualityConfig] = None,
    ):
        self._rule_engine = rule_engine or QualityRuleEngine(config)
        self._config = config or QualityConfig()
        self._scheduled_checks: Dict[str, ScheduledCheck] = {}
        self._data_providers: Dict[str, Any] = {}
        self._result_history: Dict[str, List[ValidationResult]] = {}

    def register_data_provider(self, name: str, provider: Any) -> None:
        self._data_providers[name] = provider

    def validate_table(
        self,
        database_name: str,
        table_name: str,
        data: List[Dict[str, Any]],
        rule_ids: Optional[List[str]] = None,
    ) -> ValidationResult:
        started = datetime.utcnow().isoformat()

        if rule_ids:
            rules = [self._rule_engine._rules[rid] for rid in rule_ids if rid in self._rule_engine._rules]
        else:
            rules = self._rule_engine.get_rules(database=database_name, table=table_name, enabled_only=True)

        violations = []
        passed = 0
        failed = 0
        errors = 0

        for rule in rules:
            violation = self._rule_engine.evaluate_rule(rule, data)
            if violation is None:
                passed += 1
            elif violation.violation_count == 0:
                passed += 1
            elif violation.violation_count < 0:
                errors += 1
                violations.append(violation)
            else:
                failed += 1
                violations.append(violation)

        result = ValidationResult(
            database_name=database_name,
            table_name=table_name,
            total_rules=len(rules),
            passed_rules=passed,
            failed_rules=failed,
            error_rules=errors,
            violations=violations,
            started_at=started,
            completed_at=datetime.utcnow().isoformat(),
            row_count=len(data),
        )

        key = f"{database_name}.{table_name}"
        if key not in self._result_history:
            self._result_history[key] = []
        self._result_history[key].append(result)
        if len(self._result_history[key]) > 100:
            self._result_history[key] = self._result_history[key][-100:]

        return result

    def validate_column(
        self,
        database_name: str,
        table_name: str,
        column_name: str,
        data: List[Dict[str, Any]],
    ) -> ValidationResult:
        rules = [
            r for r in self._rule_engine.get_rules(database=database_name, table=table_name, enabled_only=True)
            if r.target_column == column_name
        ]

        started = datetime.utcnow().isoformat()
        violations = []
        passed = 0
        failed = 0
        errors = 0

        for rule in rules:
            violation = self._rule_engine.evaluate_rule(rule, data)
            if violation is None or violation.violation_count == 0:
                passed += 1
            elif violation.violation_count < 0:
                errors += 1
                violations.append(violation)
            else:
                failed += 1
                violations.append(violation)

        return ValidationResult(
            database_name=database_name,
            table_name=table_name,
            total_rules=len(rules),
            passed_rules=passed,
            failed_rules=failed,
            error_rules=errors,
            violations=violations,
            started_at=started,
            completed_at=datetime.utcnow().isoformat(),
            row_count=len(data),
        )

    def add_scheduled_check(self, check: ScheduledCheck) -> None:
        self._scheduled_checks[check.check_id] = check

    def remove_scheduled_check(self, check_id: str) -> None:
        self._scheduled_checks.pop(check_id, None)

    def get_scheduled_checks(self) -> List[ScheduledCheck]:
        return list(self._scheduled_checks.values())

    def run_scheduled_checks(self) -> List[ValidationResult]:
        results = []
        for check in self._scheduled_checks.values():
            if not check.enabled:
                continue
            try:
                provider = self._data_providers.get(f"{check.database_name}.{check.table_name}")
                if provider:
                    data = provider(check.database_name, check.table_name)
                else:
                    data = []

                result = self.validate_table(check.database_name, check.table_name, data)
                check.last_run = datetime.utcnow().isoformat()
                check.last_result = result
                results.append(result)
            except Exception as e:
                logger.error(f"Scheduled check {check.check_id} failed: {e}")

        return results

    def get_validation_history(
        self,
        database_name: str,
        table_name: str,
        limit: int = 10,
    ) -> List[ValidationResult]:
        key = f"{database_name}.{table_name}"
        history = self._result_history.get(key, [])
        return history[-limit:]

    def get_quality_score(self, database_name: str, table_name: str) -> float:
        key = f"{database_name}.{table_name}"
        history = self._result_history.get(key, [])
        if not history:
            return 1.0
        latest = history[-1]
        return latest.pass_rate

    def get_quality_summary(self, database_name: Optional[str] = None) -> Dict[str, Any]:
        summary = {
            "total_tables": 0,
            "average_score": 0.0,
            "tables_with_errors": 0,
            "tables_with_warnings": 0,
        }

        scores = []
        for key, history in self._result_history.items():
            if database_name and not key.startswith(f"{database_name}."):
                continue
            if not history:
                continue
            latest = history[-1]
            scores.append(latest.pass_rate)
            summary["total_tables"] += 1
            if latest.has_errors:
                summary["tables_with_errors"] += 1
            elif latest.violations:
                summary["tables_with_warnings"] += 1

        if scores:
            summary["average_score"] = round(sum(scores) / len(scores), 4)

        return summary
