import json
import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from src.infrastructure.config.settings import QualityConfig

logger = logging.getLogger(__name__)


class RuleType(Enum):
    NOT_NULL = "NOT_NULL"
    UNIQUE = "UNIQUE"
    RANGE = "RANGE"
    LENGTH = "LENGTH"
    REGEX = "REGEX"
    ENUM = "ENUM"
    FRESHNESS = "FRESHNESS"
    COMPLETENESS = "COMPLETENESS"
    CUSTOM = "CUSTOM"


class Strictness(Enum):
    ERROR = "error"
    WARNING = "warning"
    INFO = "info"


@dataclass
class QualityRule:
    rule_id: str
    rule_name: str
    rule_type: RuleType
    target_database: str
    target_table: str
    target_column: Optional[str] = None
    strictness: Strictness = Strictness.WARNING
    enabled: bool = True
    params: Dict[str, Any] = field(default_factory=dict)
    description: Optional[str] = None
    custom_check: Optional[Callable] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "rule_id": self.rule_id,
            "rule_name": self.rule_name,
            "rule_type": self.rule_type.value,
            "target_database": self.target_database,
            "target_table": self.target_table,
            "target_column": self.target_column,
            "strictness": self.strictness.value,
            "enabled": self.enabled,
            "params": self.params,
            "description": self.description,
        }


@dataclass
class RuleViolation:
    rule_id: str
    rule_name: str
    rule_type: str
    target_database: str
    target_table: str
    target_column: Optional[str]
    strictness: str
    violation_count: int = 0
    total_count: int = 0
    violation_ratio: float = 0.0
    details: List[Dict[str, Any]] = field(default_factory=list)
    timestamp: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "rule_id": self.rule_id,
            "rule_name": self.rule_name,
            "rule_type": self.rule_type,
            "target_database": self.target_database,
            "target_table": self.target_table,
            "target_column": self.target_column,
            "strictness": self.strictness,
            "violation_count": self.violation_count,
            "total_count": self.total_count,
            "violation_ratio": round(self.violation_ratio, 4),
            "details": self.details[:10],
            "timestamp": self.timestamp,
        }


class QualityRuleEngine:
    def __init__(self, config: Optional[QualityConfig] = None):
        self._config = config or QualityConfig()
        self._rules: Dict[str, QualityRule] = {}
        self._builtin_checks: Dict[RuleType, Callable] = {
            RuleType.NOT_NULL: self._check_not_null,
            RuleType.UNIQUE: self._check_unique,
            RuleType.RANGE: self._check_range,
            RuleType.LENGTH: self._check_length,
            RuleType.REGEX: self._check_regex,
            RuleType.ENUM: self._check_enum,
            RuleType.FRESHNESS: self._check_freshness,
            RuleType.COMPLETENESS: self._check_completeness,
        }

    def add_rule(self, rule: QualityRule) -> None:
        self._rules[rule.rule_id] = rule

    def add_rules(self, rules: List[QualityRule]) -> None:
        for rule in rules:
            self.add_rule(rule)

    def remove_rule(self, rule_id: str) -> None:
        self._rules.pop(rule_id, None)

    def update_rule(self, rule_id: str, updates: Dict[str, Any]) -> None:
        rule = self._rules.get(rule_id)
        if rule is None:
            return
        for key, value in updates.items():
            if hasattr(rule, key):
                setattr(rule, key, value)

    def get_rules(
        self,
        database: Optional[str] = None,
        table: Optional[str] = None,
        enabled_only: bool = False,
    ) -> List[QualityRule]:
        rules = list(self._rules.values())
        if database:
            rules = [r for r in rules if r.target_database == database]
        if table:
            rules = [r for r in rules if r.target_table == table]
        if enabled_only:
            rules = [r for r in rules if r.enabled]
        return rules

    def evaluate_rule(
        self,
        rule: QualityRule,
        data: List[Dict[str, Any]],
    ) -> Optional[RuleViolation]:
        if not rule.enabled:
            return None

        check_fn = self._builtin_checks.get(rule.rule_type)
        if rule.rule_type == RuleType.CUSTOM and rule.custom_check:
            check_fn = rule.custom_check

        if check_fn is None:
            logger.warning(f"No check function for rule type: {rule.rule_type}")
            return None

        try:
            violation = check_fn(rule, data)
            return violation
        except Exception as e:
            logger.error(f"Rule evaluation failed for {rule.rule_id}: {e}")
            return RuleViolation(
                rule_id=rule.rule_id,
                rule_name=rule.rule_name,
                rule_type=rule.rule_type.value,
                target_database=rule.target_database,
                target_table=rule.target_table,
                target_column=rule.target_column,
                strictness=rule.strictness.value,
                violation_count=-1,
                total_count=len(data),
                details=[{"error": str(e)}],
            )

    def evaluate_all(
        self,
        database: str,
        table: str,
        data: List[Dict[str, Any]],
    ) -> List[RuleViolation]:
        violations = []
        rules = self.get_rules(database=database, table=table, enabled_only=True)
        for rule in rules:
            violation = self.evaluate_rule(rule, data)
            if violation and violation.violation_count != 0:
                violations.append(violation)
        return violations

    def _check_not_null(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        column = rule.target_column
        if not column:
            return self._make_violation(rule, 0, len(data))

        null_count = sum(1 for row in data if row.get(column) is None)
        return self._make_violation(rule, null_count, len(data))

    def _check_unique(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        column = rule.target_column
        if not column:
            return self._make_violation(rule, 0, len(data))

        values = [row.get(column) for row in data if row.get(column) is not None]
        unique_count = len(set(values))
        duplicates = len(values) - unique_count
        return self._make_violation(rule, duplicates, len(data))

    def _check_range(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        column = rule.target_column
        if not column:
            return self._make_violation(rule, 0, len(data))

        min_val = rule.params.get("min")
        max_val = rule.params.get("max")
        violations = []

        for row in data:
            val = row.get(column)
            if val is None:
                continue
            try:
                if min_val is not None and val < min_val:
                    violations.append({"value": val, "reason": f"below minimum {min_val}"})
                if max_val is not None and val > max_val:
                    violations.append({"value": val, "reason": f"above maximum {max_val}"})
            except TypeError:
                pass

        return self._make_violation(rule, len(violations), len(data), violations)

    def _check_length(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        column = rule.target_column
        if not column:
            return self._make_violation(rule, 0, len(data))

        min_len = rule.params.get("min_length", 0)
        max_len = rule.params.get("max_length", float("inf"))
        violations = []

        for row in data:
            val = row.get(column)
            if val is None:
                continue
            val_len = len(str(val))
            if val_len < min_len or val_len > max_len:
                violations.append({"value": str(val)[:50], "length": val_len})

        return self._make_violation(rule, len(violations), len(data), violations)

    def _check_regex(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        import re
        column = rule.target_column
        if not column:
            return self._make_violation(rule, 0, len(data))

        pattern = rule.params.get("pattern", "")
        if not pattern:
            return self._make_violation(rule, 0, len(data))

        try:
            compiled = re.compile(pattern)
        except re.error:
            return self._make_violation(rule, -1, len(data))

        violations = []
        for row in data:
            val = row.get(column)
            if val is None:
                continue
            if not compiled.match(str(val)):
                violations.append({"value": str(val)[:50]})

        return self._make_violation(rule, len(violations), len(data), violations)

    def _check_enum(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        column = rule.target_column
        if not column:
            return self._make_violation(rule, 0, len(data))

        allowed = set(rule.params.get("values", []))
        if not allowed:
            return self._make_violation(rule, 0, len(data))

        violations = []
        for row in data:
            val = row.get(column)
            if val is not None and val not in allowed:
                violations.append({"value": val})

        return self._make_violation(rule, len(violations), len(data), violations)

    def _check_freshness(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        import time
        column = rule.target_column or rule.params.get("timestamp_column", "updated_at")
        max_age_seconds = rule.params.get("max_age_seconds", 86400)

        if not data:
            return self._make_violation(rule, 1, 1)

        latest = None
        for row in data:
            val = row.get(column)
            if val is not None:
                try:
                    from datetime import datetime
                    if isinstance(val, str):
                        ts = datetime.fromisoformat(val).timestamp()
                    elif isinstance(val, (int, float)):
                        ts = val
                    else:
                        continue
                    if latest is None or ts > latest:
                        latest = ts
                except (ValueError, TypeError):
                    continue

        if latest is None:
            return self._make_violation(rule, 1, 1, [{"reason": "No valid timestamps found"}])

        age = time.time() - latest
        if age > max_age_seconds:
            return self._make_violation(rule, 1, 1, [{"age_seconds": age, "max_age_seconds": max_age_seconds}])

        return self._make_violation(rule, 0, 1)

    def _check_completeness(self, rule: QualityRule, data: List[Dict[str, Any]]) -> RuleViolation:
        columns = rule.params.get("columns", [])
        if not columns:
            columns = [rule.target_column] if rule.target_column else []

        if not data or not columns:
            return self._make_violation(rule, 0, len(data))

        min_completeness = rule.params.get("min_completeness", 1.0)
        total_cells = len(data) * len(columns)
        filled_cells = 0

        for row in data:
            for col in columns:
                if row.get(col) is not None:
                    filled_cells += 1

        actual_completeness = filled_cells / max(total_cells, 1)
        if actual_completeness < min_completeness:
            violation_count = total_cells - filled_cells
            return self._make_violation(
                rule, violation_count, total_cells,
                [{"completeness": round(actual_completeness, 4), "min_required": min_completeness}],
            )

        return self._make_violation(rule, 0, len(data))

    def _make_violation(
        self,
        rule: QualityRule,
        violation_count: int,
        total_count: int,
        details: Optional[List[Dict[str, Any]]] = None,
    ) -> RuleViolation:
        from datetime import datetime
        return RuleViolation(
            rule_id=rule.rule_id,
            rule_name=rule.rule_name,
            rule_type=rule.rule_type.value,
            target_database=rule.target_database,
            target_table=rule.target_table,
            target_column=rule.target_column,
            strictness=rule.strictness.value,
            violation_count=violation_count,
            total_count=total_count,
            violation_ratio=violation_count / max(total_count, 1),
            details=details or [],
            timestamp=datetime.utcnow().isoformat(),
        )
