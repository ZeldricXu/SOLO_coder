"""Quality rules manager for data quality module."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Pattern
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager


class RuleSeverity(Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class RuleType(Enum):
    NULL_CHECK = "null_check"
    UNIQUE_CHECK = "unique_check"
    RANGE_CHECK = "range_check"
    REGEX_CHECK = "regex_check"
    FORMAT_CHECK = "format_check"
    REFERENTIAL_CHECK = "referential_check"
    CUSTOM_CHECK = "custom_check"
    DUPLICATE_CHECK = "duplicate_check"


@dataclass
class QualityRule:
    id: UUID = field(default_factory=uuid4)
    name: str
    rule_type: RuleType
    field_name: str
    table_name: str
    severity: RuleSeverity = RuleSeverity.MEDIUM
    parameters: Dict[str, Any] = field(default_factory=dict)
    description: str = ""
    enabled: bool = True
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)
    schedule: Optional[str] = None
    last_run_at: Optional[datetime] = None
    custom_validator: Optional[Callable] = None
    compiled_pattern: Optional[Pattern] = None

    def compile(self) -> None:
        if self.rule_type == RuleType.REGEX_CHECK and "pattern" in self.parameters:
            self.compiled_pattern = re.compile(self.parameters["pattern"])

    def validate(self, value: Any, context: Optional[Dict[str, Any]] = None) -> tuple[bool, Optional[str]]:
        if not self.enabled:
            return True, None

        try:
            if self.rule_type == RuleType.NULL_CHECK:
                return self._validate_null(value)
            elif self.rule_type == RuleType.UNIQUE_CHECK:
                return self._validate_unique(value, context)
            elif self.rule_type == RuleType.RANGE_CHECK:
                return self._validate_range(value)
            elif self.rule_type == RuleType.REGEX_CHECK:
                return self._validate_regex(value)
            elif self.rule_type == RuleType.FORMAT_CHECK:
                return self._validate_format(value)
            elif self.rule_type == RuleType.REFERENTIAL_CHECK:
                return self._validate_referential(value, context)
            elif self.rule_type == RuleType.DUPLICATE_CHECK:
                return self._validate_duplicate(value, context)
            elif self.rule_type == RuleType.CUSTOM_CHECK:
                return self._validate_custom(value, context)
            else:
                return True, None

        except Exception as e:
            return False, f"Validation error: {str(e)}"

    def _validate_null(self, value: Any) -> tuple[bool, Optional[str]]:
        allow_null = self.parameters.get("allow_null", False)
        if value is None or (isinstance(value, str) and value.strip() == ""):
            if not allow_null:
                return False, f"Field '{self.field_name}' cannot be null or empty"
        return True, None

    def _validate_unique(self, value: Any, context: Optional[Dict[str, Any]]) -> tuple[bool, Optional[str]]:
        if context is None:
            return True, None

        seen_values = context.get("seen_values", set())
        if value in seen_values:
            return False, f"Field '{self.field_name}' has duplicate value: {value}"
        seen_values.add(value)
        return True, None

    def _validate_range(self, value: Any) -> tuple[bool, Optional[str]]:
        if value is None:
            return True, None

        min_value = self.parameters.get("min")
        max_value = self.parameters.get("max")

        try:
            num_value = float(value)
            if min_value is not None and num_value < min_value:
                return False, f"Field '{self.field_name}' value {value} is less than minimum {min_value}"
            if max_value is not None and num_value > max_value:
                return False, f"Field '{self.field_name}' value {value} is greater than maximum {max_value}"
        except (ValueError, TypeError):
            return False, f"Field '{self.field_name}' value {value} is not a valid number"

        return True, None

    def _validate_regex(self, value: Any) -> tuple[bool, Optional[str]]:
        if value is None:
            return True, None

        if self.compiled_pattern is None:
            self.compile()

        if not isinstance(value, str):
            value = str(value)

        if not self.compiled_pattern.match(value):
            return False, f"Field '{self.field_name}' value '{value}' does not match pattern"
        return True, None

    def _validate_format(self, value: Any) -> tuple[bool, Optional[str]]:
        if value is None:
            return True, None

        expected_format = self.parameters.get("format", "")
        if not isinstance(value, str):
            value = str(value)

        if expected_format == "email":
            email_pattern = r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"
            if not re.match(email_pattern, value):
                return False, f"Field '{self.field_name}' value '{value}' is not a valid email"
        elif expected_format == "date":
            date_patterns = [
                r"^\d{4}-\d{2}-\d{2}$",
                r"^\d{4}/\d{2}/\d{2}$",
            ]
            if not any(re.match(p, value) for p in date_patterns):
                return False, f"Field '{self.field_name}' value '{value}' is not a valid date"
        elif expected_format == "phone":
            phone_pattern = r"^[\d\-\(\)\+\s]{7,20}$"
            if not re.match(phone_pattern, value):
                return False, f"Field '{self.field_name}' value '{value}' is not a valid phone number"
        elif expected_format == "url":
            url_pattern = r"^https?://[^\s/$.?#].[^\s]*$"
            if not re.match(url_pattern, value):
                return False, f"Field '{self.field_name}' value '{value}' is not a valid URL"

        return True, None

    def _validate_referential(self, value: Any, context: Optional[Dict[str, Any]]) -> tuple[bool, Optional[str]]:
        if value is None:
            return True, None

        reference_values = context.get("reference_values", set()) if context else set()
        if value not in reference_values:
            return False, f"Field '{self.field_name}' value '{value}' not found in reference data"
        return True, None

    def _validate_duplicate(self, value: Any, context: Optional[Dict[str, Any]]) -> tuple[bool, Optional[str]]:
        return self._validate_unique(value, context)

    def _validate_custom(self, value: Any, context: Optional[Dict[str, Any]]) -> tuple[bool, Optional[str]]:
        if self.custom_validator:
            try:
                result = self.custom_validator(value, context)
                if isinstance(result, tuple):
                    return result
                return result, None
            except Exception as e:
                return False, f"Custom validator error: {str(e)}"
        return True, None


class QualityRuleManager:
    def __init__(self) -> None:
        self._rules: Dict[UUID, QualityRule] = {}
        self._logger = LogManager().get_logger(__name__)

    def create_rule(
        self,
        name: str,
        rule_type: RuleType,
        field_name: str,
        table_name: str,
        severity: RuleSeverity = RuleSeverity.MEDIUM,
        parameters: Optional[Dict[str, Any]] = None,
        description: str = "",
        schedule: Optional[str] = None,
        custom_validator: Optional[Callable] = None,
    ) -> QualityRule:
        rule = QualityRule(
            name=name,
            rule_type=rule_type,
            field_name=field_name,
            table_name=table_name,
            severity=severity,
            parameters=parameters or {},
            description=description,
            schedule=schedule,
            custom_validator=custom_validator,
        )
        rule.compile()

        self._rules[rule.id] = rule
        self._logger.info(
            f"Created quality rule: {name}",
            rule_id=str(rule.id),
            rule_type=rule_type.value,
            table_name=table_name,
            field_name=field_name,
        )

        return rule

    def get_rule(self, rule_id: UUID) -> Optional[QualityRule]:
        return self._rules.get(rule_id)

    def list_rules(
        self,
        table_name: Optional[str] = None,
        field_name: Optional[str] = None,
        rule_type: Optional[RuleType] = None,
        severity: Optional[RuleSeverity] = None,
        enabled_only: bool = True,
    ) -> List[QualityRule]:
        rules = list(self._rules.values())

        if table_name:
            rules = [r for r in rules if r.table_name == table_name]
        if field_name:
            rules = [r for r in rules if r.field_name == field_name]
        if rule_type:
            rules = [r for r in rules if r.rule_type == rule_type]
        if severity:
            rules = [r for r in rules if r.severity == severity]
        if enabled_only:
            rules = [r for r in rules if r.enabled]

        return sorted(rules, key=lambda r: (r.table_name, r.field_name, r.severity.value))

    def update_rule(self, rule_id: UUID, **updates: Any) -> Optional[QualityRule]:
        rule = self._rules.get(rule_id)
        if not rule:
            return None

        for key, value in updates.items():
            if hasattr(rule, key):
                setattr(rule, key, value)

        rule.updated_at = datetime.utcnow()
        rule.compile()

        self._logger.info(f"Updated quality rule: {rule.name}")
        return rule

    def delete_rule(self, rule_id: UUID) -> bool:
        if rule_id in self._rules:
            rule = self._rules.pop(rule_id)
            self._logger.info(f"Deleted quality rule: {rule.name}")
            return True
        return False

    def enable_rule(self, rule_id: UUID) -> bool:
        rule = self._rules.get(rule_id)
        if not rule:
            return False
        rule.enabled = True
        rule.updated_at = datetime.utcnow()
        return True

    def disable_rule(self, rule_id: UUID) -> bool:
        rule = self._rules.get(rule_id)
        if not rule:
            return False
        rule.enabled = False
        rule.updated_at = datetime.utcnow()
        return True

    def get_rules_for_table(self, table_name: str) -> List[QualityRule]:
        return self.list_rules(table_name=table_name)

    def validate_row(
        self,
        row: Dict[str, Any],
        table_name: str,
        context: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        violations: List[Dict[str, Any]] = []
        rules = self.get_rules_for_table(table_name)

        validation_context = context or {}
        if "seen_values" not in validation_context:
            validation_context["seen_values"] = {}

        for rule in rules:
            if rule.field_name not in validation_context["seen_values"]:
                validation_context["seen_values"][rule.field_name] = set()

            field_context = {
                **validation_context,
                "seen_values": validation_context["seen_values"][rule.field_name],
            }

            value = row.get(rule.field_name)
            valid, message = rule.validate(value, field_context)

            if not valid:
                violations.append({
                    "rule_id": str(rule.id),
                    "rule_name": rule.name,
                    "rule_type": rule.rule_type.value,
                    "field_name": rule.field_name,
                    "severity": rule.severity.value,
                    "message": message,
                    "value": value,
                })

        return violations

    def validate_batch(
        self,
        rows: List[Dict[str, Any]],
        table_name: str,
        context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        all_violations: List[Dict[str, Any]] = []
        validation_context = context or {}
        validation_context["seen_values"] = {}

        for row in rows:
            violations = self.validate_row(row, table_name, validation_context)
            if violations:
                all_violations.extend(violations)

        severity_counts: Dict[str, int] = {}
        for v in all_violations:
            sev = v["severity"]
            severity_counts[sev] = severity_counts.get(sev, 0) + 1

        return {
            "total_rows": len(rows),
            "violations_count": len(all_violations),
            "violations_per_row": len(all_violations) / len(rows) if rows else 0,
            "severity_distribution": severity_counts,
            "violations": all_violations,
        }

    def create_null_check(
        self,
        table_name: str,
        field_name: str,
        allow_null: bool = False,
        severity: RuleSeverity = RuleSeverity.HIGH,
    ) -> QualityRule:
        return self.create_rule(
            name=f"{table_name}_{field_name}_null_check",
            rule_type=RuleType.NULL_CHECK,
            field_name=field_name,
            table_name=table_name,
            severity=severity,
            parameters={"allow_null": allow_null},
            description=f"Check if {field_name} is null",
        )

    def create_range_check(
        self,
        table_name: str,
        field_name: str,
        min_value: Optional[float] = None,
        max_value: Optional[float] = None,
        severity: RuleSeverity = RuleSeverity.MEDIUM,
    ) -> QualityRule:
        return self.create_rule(
            name=f"{table_name}_{field_name}_range_check",
            rule_type=RuleType.RANGE_CHECK,
            field_name=field_name,
            table_name=table_name,
            severity=severity,
            parameters={"min": min_value, "max": max_value},
            description=f"Check if {field_name} is within range [{min_value}, {max_value}]",
        )

    def create_regex_check(
        self,
        table_name: str,
        field_name: str,
        pattern: str,
        severity: RuleSeverity = RuleSeverity.MEDIUM,
    ) -> QualityRule:
        return self.create_rule(
            name=f"{table_name}_{field_name}_regex_check",
            rule_type=RuleType.REGEX_CHECK,
            field_name=field_name,
            table_name=table_name,
            severity=severity,
            parameters={"pattern": pattern},
            description=f"Check if {field_name} matches pattern {pattern}",
        )

    def create_format_check(
        self,
        table_name: str,
        field_name: str,
        format_type: str,
        severity: RuleSeverity = RuleSeverity.MEDIUM,
    ) -> QualityRule:
        return self.create_rule(
            name=f"{table_name}_{field_name}_format_check",
            rule_type=RuleType.FORMAT_CHECK,
            field_name=field_name,
            table_name=table_name,
            severity=severity,
            parameters={"format": format_type},
            description=f"Check if {field_name} has format {format_type}",
        )

    def create_unique_check(
        self,
        table_name: str,
        field_name: str,
        severity: RuleSeverity = RuleSeverity.HIGH,
    ) -> QualityRule:
        return self.create_rule(
            name=f"{table_name}_{field_name}_unique_check",
            rule_type=RuleType.UNIQUE_CHECK,
            field_name=field_name,
            table_name=table_name,
            severity=severity,
            description=f"Check if {field_name} is unique",
        )

    def export_rules(self) -> List[Dict[str, Any]]:
        return [
            {
                "id": str(rule.id),
                "name": rule.name,
                "rule_type": rule.rule_type.value,
                "field_name": rule.field_name,
                "table_name": rule.table_name,
                "severity": rule.severity.value,
                "parameters": rule.parameters,
                "description": rule.description,
                "enabled": rule.enabled,
                "schedule": rule.schedule,
                "created_at": rule.created_at.isoformat(),
                "updated_at": rule.updated_at.isoformat(),
                "last_run_at": rule.last_run_at.isoformat() if rule.last_run_at else None,
            }
            for rule in self._rules.values()
        ]

    def import_rules(self, rules_data: List[Dict[str, Any]]) -> int:
        imported = 0
        for rule_data in rules_data:
            try:
                self.create_rule(
                    name=rule_data["name"],
                    rule_type=RuleType(rule_data["rule_type"]),
                    field_name=rule_data["field_name"],
                    table_name=rule_data["table_name"],
                    severity=RuleSeverity(rule_data.get("severity", "medium")),
                    parameters=rule_data.get("parameters", {}),
                    description=rule_data.get("description", ""),
                    schedule=rule_data.get("schedule"),
                )
                imported += 1
            except Exception as e:
                self._logger.error(f"Failed to import rule: {e}")

        return imported
