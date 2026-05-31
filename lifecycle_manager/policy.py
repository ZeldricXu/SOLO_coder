from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Union

from .storage_tier import StorageTierType


class RuleConditionOperator(Enum):
    EQUALS = "equals"
    NOT_EQUALS = "not_equals"
    GREATER_THAN = "greater_than"
    LESS_THAN = "less_than"
    GREATER_THAN_OR_EQUAL = "greater_than_or_equal"
    LESS_THAN_OR_EQUAL = "less_than_or_equal"
    CONTAINS = "contains"
    NOT_CONTAINS = "not_contains"
    IN = "in"
    NOT_IN = "not_in"
    MATCHES = "matches"


class RuleCondition:
    def __init__(
        self,
        field: str,
        operator: Union[RuleConditionOperator, str],
        value: Any,
    ):
        self.field = field
        self.operator = operator if isinstance(operator, RuleConditionOperator) else RuleConditionOperator(operator)
        self.value = value

    def evaluate(self, data: Dict[str, Any]) -> bool:
        field_value = self._get_nested_value(data, self.field)

        try:
            if self.operator == RuleConditionOperator.EQUALS:
                return field_value == self.value
            elif self.operator == RuleConditionOperator.NOT_EQUALS:
                return field_value != self.value
            elif self.operator == RuleConditionOperator.GREATER_THAN:
                return field_value > self.value
            elif self.operator == RuleConditionOperator.LESS_THAN:
                return field_value < self.value
            elif self.operator == RuleConditionOperator.GREATER_THAN_OR_EQUAL:
                return field_value >= self.value
            elif self.operator == RuleConditionOperator.LESS_THAN_OR_EQUAL:
                return field_value <= self.value
            elif self.operator == RuleConditionOperator.CONTAINS:
                return self.value in str(field_value) if field_value else False
            elif self.operator == RuleConditionOperator.NOT_CONTAINS:
                return self.value not in str(field_value) if field_value else True
            elif self.operator == RuleConditionOperator.IN:
                return field_value in self.value
            elif self.operator == RuleConditionOperator.NOT_IN:
                return field_value not in self.value
            elif self.operator == RuleConditionOperator.MATCHES:
                import re
                return bool(re.match(self.value, str(field_value))) if field_value else False
        except (TypeError, ValueError):
            return False

        return False

    def _get_nested_value(self, data: Dict[str, Any], field_path: str) -> Any:
        keys = field_path.split(".")
        value = data
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                return None
        return value

    def to_dict(self) -> Dict[str, Any]:
        return {
            "field": self.field,
            "operator": self.operator.value,
            "value": self.value,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "RuleCondition":
        return cls(
            field=data["field"],
            operator=data["operator"],
            value=data["value"],
        )


class TieringRule:
    def __init__(
        self,
        name: str,
        source_tier: StorageTierType,
        target_tier: StorageTierType,
        conditions: List[RuleCondition],
        description: Optional[str] = None,
        enabled: bool = True,
        priority: int = 0,
    ):
        self.name = name
        self.source_tier = source_tier if isinstance(source_tier, StorageTierType) else StorageTierType(source_tier)
        self.target_tier = target_tier if isinstance(target_tier, StorageTierType) else StorageTierType(target_tier)
        self.conditions = conditions
        self.description = description
        self.enabled = enabled
        self.priority = priority

    def matches(self, metadata: Dict[str, Any]) -> bool:
        if not self.enabled:
            return False

        for condition in self.conditions:
            if not condition.evaluate(metadata):
                return False
        return True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "source_tier": self.source_tier.value,
            "target_tier": self.target_tier.value,
            "conditions": [c.to_dict() for c in self.conditions],
            "description": self.description,
            "enabled": self.enabled,
            "priority": self.priority,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TieringRule":
        conditions = [RuleCondition.from_dict(c) for c in data.get("conditions", [])]
        return cls(
            name=data["name"],
            source_tier=data["source_tier"],
            target_tier=data["target_tier"],
            conditions=conditions,
            description=data.get("description"),
            enabled=data.get("enabled", True),
            priority=data.get("priority", 0),
        )

    @classmethod
    def create_age_based_rule(
        cls,
        name: str,
        source_tier: StorageTierType,
        target_tier: StorageTierType,
        min_age_days: int,
        priority: int = 0,
    ) -> "TieringRule":
        conditions = [
            RuleCondition("age_days", RuleConditionOperator.GREATER_THAN_OR_EQUAL, min_age_days),
        ]
        return cls(
            name=name,
            source_tier=source_tier,
            target_tier=target_tier,
            conditions=conditions,
            description=f"Move data older than {min_age_days} days from {source_tier.value} to {target_tier.value}",
            priority=priority,
        )

    @classmethod
    def create_access_based_rule(
        cls,
        name: str,
        source_tier: StorageTierType,
        target_tier: StorageTierType,
        days_since_last_access: int,
        priority: int = 0,
    ) -> "TieringRule":
        conditions = [
            RuleCondition("days_since_last_access", RuleConditionOperator.GREATER_THAN_OR_EQUAL, days_since_last_access),
        ]
        return cls(
            name=name,
            source_tier=source_tier,
            target_tier=target_tier,
            conditions=conditions,
            description=f"Move data not accessed for {days_since_last_access} days from {source_tier.value} to {target_tier.value}",
            priority=priority,
        )


class ArchiveRule:
    def __init__(
        self,
        name: str,
        conditions: List[RuleCondition],
        compression: bool = True,
        encryption: bool = False,
        retention_days: Optional[int] = None,
        description: Optional[str] = None,
        enabled: bool = True,
        priority: int = 0,
    ):
        self.name = name
        self.conditions = conditions
        self.compression = compression
        self.encryption = encryption
        self.retention_days = retention_days
        self.description = description
        self.enabled = enabled
        self.priority = priority

    def matches(self, metadata: Dict[str, Any]) -> bool:
        if not self.enabled:
            return False

        for condition in self.conditions:
            if not condition.evaluate(metadata):
                return False
        return True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "conditions": [c.to_dict() for c in self.conditions],
            "compression": self.compression,
            "encryption": self.encryption,
            "retention_days": self.retention_days,
            "description": self.description,
            "enabled": self.enabled,
            "priority": self.priority,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ArchiveRule":
        conditions = [RuleCondition.from_dict(c) for c in data.get("conditions", [])]
        return cls(
            name=data["name"],
            conditions=conditions,
            compression=data.get("compression", True),
            encryption=data.get("encryption", False),
            retention_days=data.get("retention_days"),
            description=data.get("description"),
            enabled=data.get("enabled", True),
            priority=data.get("priority", 0),
        )

    @classmethod
    def create_age_based_archive(
        cls,
        name: str,
        min_age_days: int,
        compression: bool = True,
        encryption: bool = False,
        retention_days: Optional[int] = None,
        priority: int = 0,
    ) -> "ArchiveRule":
        conditions = [
            RuleCondition("age_days", RuleConditionOperator.GREATER_THAN_OR_EQUAL, min_age_days),
        ]
        return cls(
            name=name,
            conditions=conditions,
            compression=compression,
            encryption=encryption,
            retention_days=retention_days,
            description=f"Archive data older than {min_age_days} days",
            priority=priority,
        )


class CleanupRule:
    def __init__(
        self,
        name: str,
        conditions: List[RuleCondition],
        secure_delete: bool = False,
        description: Optional[str] = None,
        enabled: bool = True,
        priority: int = 0,
    ):
        self.name = name
        self.conditions = conditions
        self.secure_delete = secure_delete
        self.description = description
        self.enabled = enabled
        self.priority = priority

    def matches(self, metadata: Dict[str, Any]) -> bool:
        if not self.enabled:
            return False

        for condition in self.conditions:
            if not condition.evaluate(metadata):
                return False
        return True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "conditions": [c.to_dict() for c in self.conditions],
            "secure_delete": self.secure_delete,
            "description": self.description,
            "enabled": self.enabled,
            "priority": self.priority,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "CleanupRule":
        conditions = [RuleCondition.from_dict(c) for c in data.get("conditions", [])]
        return cls(
            name=data["name"],
            conditions=conditions,
            secure_delete=data.get("secure_delete", False),
            description=data.get("description"),
            enabled=data.get("enabled", True),
            priority=data.get("priority", 0),
        )

    @classmethod
    def create_expiration_rule(
        cls,
        name: str,
        max_age_days: int,
        secure_delete: bool = False,
        priority: int = 0,
    ) -> "CleanupRule":
        conditions = [
            RuleCondition("age_days", RuleConditionOperator.GREATER_THAN_OR_EQUAL, max_age_days),
        ]
        return cls(
            name=name,
            conditions=conditions,
            secure_delete=secure_delete,
            description=f"Delete data older than {max_age_days} days",
            priority=priority,
        )

    @classmethod
    def create_tag_based_cleanup(
        cls,
        name: str,
        tag_value: str,
        tag_field: str = "tags.expired",
        secure_delete: bool = False,
        priority: int = 0,
    ) -> "CleanupRule":
        conditions = [
            RuleCondition(tag_field, RuleConditionOperator.EQUALS, tag_value),
        ]
        return cls(
            name=name,
            conditions=conditions,
            secure_delete=secure_delete,
            description=f"Delete data with {tag_field} = {tag_value}",
            priority=priority,
        )


class PolicyConfig:
    def __init__(
        self,
        name: str,
        version: str = "1.0",
        tiering_rules: Optional[List[TieringRule]] = None,
        archive_rules: Optional[List[ArchiveRule]] = None,
        cleanup_rules: Optional[List[CleanupRule]] = None,
        default_retention_days: Optional[int] = None,
        auto_execute: bool = True,
        check_interval_seconds: int = 3600,
        max_concurrent_operations: int = 5,
    ):
        self.name = name
        self.version = version
        self.tiering_rules = sorted(tiering_rules or [], key=lambda r: r.priority, reverse=True)
        self.archive_rules = sorted(archive_rules or [], key=lambda r: r.priority, reverse=True)
        self.cleanup_rules = sorted(cleanup_rules or [], key=lambda r: r.priority, reverse=True)
        self.default_retention_days = default_retention_days
        self.auto_execute = auto_execute
        self.check_interval_seconds = check_interval_seconds
        self.max_concurrent_operations = max_concurrent_operations

    def add_tiering_rule(self, rule: TieringRule) -> None:
        self.tiering_rules.append(rule)
        self.tiering_rules.sort(key=lambda r: r.priority, reverse=True)

    def add_archive_rule(self, rule: ArchiveRule) -> None:
        self.archive_rules.append(rule)
        self.archive_rules.sort(key=lambda r: r.priority, reverse=True)

    def add_cleanup_rule(self, rule: CleanupRule) -> None:
        self.cleanup_rules.append(rule)
        self.cleanup_rules.sort(key=lambda r: r.priority, reverse=True)

    def remove_tiering_rule(self, rule_name: str) -> bool:
        for i, rule in enumerate(self.tiering_rules):
            if rule.name == rule_name:
                del self.tiering_rules[i]
                return True
        return False

    def remove_archive_rule(self, rule_name: str) -> bool:
        for i, rule in enumerate(self.archive_rules):
            if rule.name == rule_name:
                del self.archive_rules[i]
                return True
        return False

    def remove_cleanup_rule(self, rule_name: str) -> bool:
        for i, rule in enumerate(self.cleanup_rules):
            if rule.name == rule_name:
                del self.cleanup_rules[i]
                return True
        return False

    def evaluate_tiering(self, metadata: Dict[str, Any], current_tier: StorageTierType) -> Optional[StorageTierType]:
        for rule in self.tiering_rules:
            if rule.source_tier == current_tier and rule.matches(metadata):
                return rule.target_tier
        return None

    def evaluate_archive(self, metadata: Dict[str, Any]) -> Optional[ArchiveRule]:
        for rule in self.archive_rules:
            if rule.matches(metadata):
                return rule
        return None

    def evaluate_cleanup(self, metadata: Dict[str, Any]) -> Optional[CleanupRule]:
        for rule in self.cleanup_rules:
            if rule.matches(metadata):
                return rule
        return None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "tiering_rules": [r.to_dict() for r in self.tiering_rules],
            "archive_rules": [r.to_dict() for r in self.archive_rules],
            "cleanup_rules": [r.to_dict() for r in self.cleanup_rules],
            "default_retention_days": self.default_retention_days,
            "auto_execute": self.auto_execute,
            "check_interval_seconds": self.check_interval_seconds,
            "max_concurrent_operations": self.max_concurrent_operations,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "PolicyConfig":
        tiering_rules = [TieringRule.from_dict(r) for r in data.get("tiering_rules", [])]
        archive_rules = [ArchiveRule.from_dict(r) for r in data.get("archive_rules", [])]
        cleanup_rules = [CleanupRule.from_dict(r) for r in data.get("cleanup_rules", [])]
        return cls(
            name=data["name"],
            version=data.get("version", "1.0"),
            tiering_rules=tiering_rules,
            archive_rules=archive_rules,
            cleanup_rules=cleanup_rules,
            default_retention_days=data.get("default_retention_days"),
            auto_execute=data.get("auto_execute", True),
            check_interval_seconds=data.get("check_interval_seconds", 3600),
            max_concurrent_operations=data.get("max_concurrent_operations", 5),
        )

    @classmethod
    def create_default_policy(cls, name: str = "default_policy") -> "PolicyConfig":
        tiering_rules = [
            TieringRule.create_age_based_rule(
                name="hot_to_cold_30days",
                source_tier=StorageTierType.HOT,
                target_tier=StorageTierType.COLD,
                min_age_days=30,
                priority=10,
            ),
            TieringRule.create_access_based_rule(
                name="hot_to_cold_no_access_7days",
                source_tier=StorageTierType.HOT,
                target_tier=StorageTierType.COLD,
                days_since_last_access=7,
                priority=20,
            ),
            TieringRule.create_age_based_rule(
                name="cold_to_archive_90days",
                source_tier=StorageTierType.COLD,
                target_tier=StorageTierType.ARCHIVE,
                min_age_days=90,
                priority=10,
            ),
        ]

        archive_rules = [
            ArchiveRule.create_age_based_archive(
                name="archive_180days",
                min_age_days=180,
                compression=True,
                encryption=False,
                retention_days=365,
                priority=10,
            ),
        ]

        cleanup_rules = [
            CleanupRule.create_expiration_rule(
                name="cleanup_365days",
                max_age_days=365,
                secure_delete=False,
                priority=10,
            ),
        ]

        return cls(
            name=name,
            tiering_rules=tiering_rules,
            archive_rules=archive_rules,
            cleanup_rules=cleanup_rules,
            default_retention_days=365,
        )


class LifecyclePolicy:
    def __init__(self, config: PolicyConfig):
        self.config = config
        self.created_at = datetime.now()
        self.updated_at = self.created_at

    def evaluate(self, metadata: Dict[str, Any], current_tier: StorageTierType) -> Dict[str, Any]:
        result = {
            "tier_action": None,
            "archive_action": None,
            "cleanup_action": None,
            "matched_rules": [],
        }

        tier_target = self.config.evaluate_tiering(metadata, current_tier)
        if tier_target:
            result["tier_action"] = {
                "source_tier": current_tier.value,
                "target_tier": tier_target.value,
            }
            result["matched_rules"].append({"type": "tiering", "target": tier_target.value})

        archive_rule = self.config.evaluate_archive(metadata)
        if archive_rule:
            result["archive_action"] = {
                "rule_name": archive_rule.name,
                "compression": archive_rule.compression,
                "encryption": archive_rule.encryption,
                "retention_days": archive_rule.retention_days,
            }
            result["matched_rules"].append({"type": "archive", "rule": archive_rule.name})

        cleanup_rule = self.config.evaluate_cleanup(metadata)
        if cleanup_rule:
            result["cleanup_action"] = {
                "rule_name": cleanup_rule.name,
                "secure_delete": cleanup_rule.secure_delete,
            }
            result["matched_rules"].append({"type": "cleanup", "rule": cleanup_rule.name})

        return result

    def to_dict(self) -> Dict[str, Any]:
        return {
            "config": self.config.to_dict(),
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
        }
