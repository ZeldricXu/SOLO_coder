from __future__ import annotations

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Optional

from streamsql.core.models import generate_id


class RuleType(str, Enum):
    NULL_CHECK = "null_check"
    RANGE_CHECK = "range_check"
    REGEX_CHECK = "regex_check"
    UNIQUENESS_CHECK = "uniqueness_check"
    FORMAT_CHECK = "format_check"
    CUSTOM = "custom"


class SeverityLevel(str, Enum):
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


@dataclass
class DataQualityRule(ABC):
    rule_id: str = field(default_factory=lambda: generate_id("rule"))
    name: str = ""
    description: str = ""
    rule_type: RuleType = RuleType.CUSTOM
    severity: SeverityLevel = SeverityLevel.ERROR
    enabled: bool = True
    column: str = ""
    table: str = ""
    parameters: dict[str, Any] = field(default_factory=dict)
    created_at: float = field(default_factory=lambda: __import__("time").time())

    @abstractmethod
    def validate(self, data: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any]]: ...

    def to_dict(self) -> dict[str, Any]:
        return {
            "rule_id": self.rule_id,
            "name": self.name,
            "description": self.description,
            "rule_type": self.rule_type.value,
            "severity": self.severity.value,
            "enabled": self.enabled,
            "column": self.column,
            "table": self.table,
            "parameters": self.parameters,
            "created_at": self.created_at,
        }


@dataclass
class NullCheckRule(DataQualityRule):
    rule_type: RuleType = RuleType.NULL_CHECK
    allow_empty: bool = False

    def validate(self, data: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any]]:
        if not self.column:
            return True, [], {}

        errors: list[str] = []
        null_count = 0
        total = len(data)

        for i, row in enumerate(data):
            value = row.get(self.column)
            if value is None or (isinstance(value, str) and not self.allow_empty and value.strip() == ""):
                null_count += 1
                errors.append(f"Row {i}: column '{self.column}' is null or empty")

        stats = {
            "total_rows": total,
            "null_count": null_count,
            "null_rate": null_count / total if total > 0 else 0.0,
        }

        return null_count == 0, errors, stats


@dataclass
class RangeCheckRule(DataQualityRule):
    rule_type: RuleType = RuleType.RANGE_CHECK
    min_value: Optional[float] = None
    max_value: Optional[float] = None

    def __post_init__(self):
        if self.min_value is not None:
            self.parameters["min_value"] = self.min_value
        if self.max_value is not None:
            self.parameters["max_value"] = self.max_value

    def validate(self, data: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any]]:
        if not self.column:
            return True, [], {}

        errors: list[str] = []
        out_of_range_count = 0
        total = len(data)

        for i, row in enumerate(data):
            value = row.get(self.column)
            if value is None:
                continue

            try:
                num_value = float(value)
                if self.min_value is not None and num_value < self.min_value:
                    out_of_range_count += 1
                    errors.append(f"Row {i}: {value} < min {self.min_value}")
                if self.max_value is not None and num_value > self.max_value:
                    out_of_range_count += 1
                    errors.append(f"Row {i}: {value} > max {self.max_value}")
            except (ValueError, TypeError):
                errors.append(f"Row {i}: cannot convert '{value}' to number")

        stats = {
            "total_rows": total,
            "out_of_range_count": out_of_range_count,
            "out_of_range_rate": out_of_range_count / total if total > 0 else 0.0,
        }

        return out_of_range_count == 0, errors, stats


@dataclass
class RegexCheckRule(DataQualityRule):
    rule_type: RuleType = RuleType.REGEX_CHECK
    pattern: str = ""

    def __post_init__(self):
        self.parameters["pattern"] = self.pattern

    def validate(self, data: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any]]:
        if not self.column or not self.pattern:
            return True, [], {}

        regex = re.compile(self.pattern)
        errors: list[str] = []
        invalid_count = 0
        total = len(data)

        for i, row in enumerate(data):
            value = row.get(self.column)
            if value is None:
                continue

            str_value = str(value)
            if not regex.match(str_value):
                invalid_count += 1
                errors.append(f"Row {i}: '{str_value}' does not match pattern '{self.pattern}'")

        stats = {
            "total_rows": total,
            "invalid_count": invalid_count,
            "invalid_rate": invalid_count / total if total > 0 else 0.0,
        }

        return invalid_count == 0, errors, stats


@dataclass
class UniquenessCheckRule(DataQualityRule):
    rule_type: RuleType = RuleType.UNIQUENESS_CHECK
    columns: list[str] = field(default_factory=list)

    def __post_init__(self):
        if not self.columns and self.column:
            self.columns = [self.column]
        self.parameters["columns"] = self.columns

    def validate(self, data: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any]]:
        if not self.columns:
            return True, [], {}

        errors: list[str] = []
        seen: dict[str, list[int]] = {}
        total = len(data)

        for i, row in enumerate(data):
            key_parts = [str(row.get(col, "")) for col in self.columns]
            key = "|".join(key_parts)

            if key in seen:
                seen[key].append(i)
                for prev_row in seen[key][:-1]:
                    errors.append(f"Row {i} duplicates row {prev_row}: key='{key}'")
            else:
                seen[key] = [i]

        duplicate_count = sum(1 for rows in seen.values() if len(rows) > 1)

        stats = {
            "total_rows": total,
            "unique_keys": len(seen),
            "duplicate_keys": duplicate_count,
            "duplicate_rate": duplicate_count / total if total > 0 else 0.0,
        }

        return duplicate_count == 0, errors, stats


FORMAT_VALIDATORS: dict[str, Callable[[Any], bool]] = {
    "email": lambda v: isinstance(v, str) and bool(re.match(r"^[\w\.-]+@[\w\.-]+\.\w+$", v)),
    "phone": lambda v: isinstance(v, str) and bool(re.match(r"^[\d\-+()\s]{7,}$", v)),
    "url": lambda v: isinstance(v, str) and bool(re.match(r"^https?://", v)),
    "ip": lambda v: isinstance(v, str) and bool(re.match(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$", v)),
    "date_iso": lambda v: isinstance(v, str) and bool(re.match(r"^\d{4}-\d{2}-\d{2}", v)),
    "integer": lambda v: isinstance(v, int) or (isinstance(v, str) and v.isdigit()),
    "float": lambda v: isinstance(v, (int, float)) or (isinstance(v, str) and bool(re.match(r"^\d+\.?\d*$", v))),
    "string": lambda v: isinstance(v, str),
    "boolean": lambda v: isinstance(v, bool) or (isinstance(v, str) and v.lower() in ["true", "false", "1", "0"]),
}


@dataclass
class FormatCheckRule(DataQualityRule):
    rule_type: RuleType = RuleType.FORMAT_CHECK
    expected_type: str = "string"

    def __post_init__(self):
        self.parameters["expected_type"] = self.expected_type

    def validate(self, data: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any]]:
        if not self.column:
            return True, [], {}

        validator = FORMAT_VALIDATORS.get(self.expected_type)
        if not validator:
            return True, [], {"warning": f"Unknown format type: {self.expected_type}"}

        errors: list[str] = []
        invalid_count = 0
        total = len(data)

        for i, row in enumerate(data):
            value = row.get(self.column)
            if value is None:
                continue

            if not validator(value):
                invalid_count += 1
                errors.append(f"Row {i}: '{value}' is not a valid {self.expected_type}")

        stats = {
            "total_rows": total,
            "invalid_count": invalid_count,
            "invalid_rate": invalid_count / total if total > 0 else 0.0,
        }

        return invalid_count == 0, errors, stats


@dataclass
class CustomRule(DataQualityRule):
    rule_type: RuleType = RuleType.CUSTOM
    validation_func: Optional[Callable[[list[dict[str, Any]]], tuple[bool, list[str], dict[str, Any]]]] = None

    def validate(self, data: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any]]:
        if self.validation_func:
            return self.validation_func(data)
        return True, [], {}


class RuleFactory:
    @staticmethod
    def create(rule_type: RuleType, **kwargs: Any) -> DataQualityRule:
        rule_classes: dict[RuleType, type[DataQualityRule]] = {
            RuleType.NULL_CHECK: NullCheckRule,
            RuleType.RANGE_CHECK: RangeCheckRule,
            RuleType.REGEX_CHECK: RegexCheckRule,
            RuleType.UNIQUENESS_CHECK: UniquenessCheckRule,
            RuleType.FORMAT_CHECK: FormatCheckRule,
            RuleType.CUSTOM: CustomRule,
        }

        rule_cls = rule_classes.get(rule_type)
        if not rule_cls:
            raise ValueError(f"Unknown rule type: {rule_type}")

        return rule_cls(**kwargs)

    @staticmethod
    def from_dict(data: dict[str, Any]) -> DataQualityRule:
        rule_type = RuleType(data.get("rule_type", "custom"))
        return RuleFactory.create(rule_type, **data)
