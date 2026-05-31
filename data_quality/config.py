from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Union
from datetime import datetime
import json
import os
import yaml
from .rules import Rule, RuleFactory, RuleType


class ConfigFormat(Enum):
    YAML = "yaml"
    JSON = "json"


@dataclass
class RuleGroup:
    name: str
    rules: List[Rule] = field(default_factory=list)
    description: str = ""
    priority: int = 5
    enabled: bool = True

    def add_rule(self, rule: Rule) -> None:
        self.rules.append(rule)

    def remove_rule(self, rule_name: str) -> None:
        self.rules = [r for r in self.rules if r.name != rule_name]

    def get_rule(self, rule_name: str) -> Optional[Rule]:
        for rule in self.rules:
            if rule.name == rule_name:
                return rule
        return None

    def get_enabled_rules(self) -> List[Rule]:
        return [r for r in self.rules if r.enabled]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "priority": self.priority,
            "enabled": self.enabled,
            "rules": [r.to_dict() for r in self.rules],
        }


@dataclass
class RuleConfig:
    name: str
    type: RuleType
    column: Optional[str] = None
    columns: Optional[List[str]] = None
    description: str = ""
    severity: str = "error"
    priority: int = 5
    enabled: bool = True
    params: Dict[str, Any] = field(default_factory=dict)

    def to_rule(self) -> Rule:
        kwargs = {
            "name": self.name,
            "description": self.description,
            "severity": self.severity,
            "priority": self.priority,
            "enabled": self.enabled,
        }

        if self.column:
            kwargs["column"] = self.column
        if self.columns:
            kwargs["columns"] = self.columns

        kwargs.update(self.params)

        return RuleFactory.create(self.type, **kwargs)

    @staticmethod
    def from_dict(data: Dict[str, Any]) -> "RuleConfig":
        rule_type = data.get("type")
        if isinstance(rule_type, str):
            rule_type = RuleType(rule_type)

        return RuleConfig(
            name=data["name"],
            type=rule_type,
            column=data.get("column"),
            columns=data.get("columns"),
            description=data.get("description", ""),
            severity=data.get("severity", "error"),
            priority=data.get("priority", 5),
            enabled=data.get("enabled", True),
            params=data.get("params", {}),
        )


class ConfigManager:
    def __init__(self, config_path: Optional[str] = None):
        self.config_path = config_path
        self.rule_groups: Dict[str, RuleGroup] = {}
        self.global_settings: Dict[str, Any] = {
            "default_severity": "error",
            "default_priority": 5,
            "fail_on_error": True,
            "max_failed_records": 1000,
            "output_format": "json",
            "alert_threshold": 80,
        }

    def add_rule_group(self, group: RuleGroup) -> None:
        self.rule_groups[group.name] = group

    def remove_rule_group(self, group_name: str) -> None:
        if group_name in self.rule_groups:
            del self.rule_groups[group_name]

    def get_rule_group(self, group_name: str) -> Optional[RuleGroup]:
        return self.rule_groups.get(group_name)

    def add_rule(self, rule: Rule, group_name: str = "default") -> None:
        if group_name not in self.rule_groups:
            self.rule_groups[group_name] = RuleGroup(name=group_name)
        self.rule_groups[group_name].add_rule(rule)

    def get_rule(self, rule_name: str) -> Optional[Rule]:
        for group in self.rule_groups.values():
            rule = group.get_rule(rule_name)
            if rule:
                return rule
        return None

    def get_all_rules(self) -> List[Rule]:
        rules = []
        for group in self.rule_groups.values():
            rules.extend(group.rules)
        return rules

    def get_enabled_rules(self) -> List[Rule]:
        rules = []
        for group in self.rule_groups.values():
            if group.enabled:
                rules.extend(group.get_enabled_rules())
        return sorted(rules, key=lambda r: r.priority)

    def get_rules_by_type(self, rule_type: RuleType) -> List[Rule]:
        return [r for r in self.get_enabled_rules() if r.rule_type == rule_type]

    def get_rules_by_column(self, column: str) -> List[Rule]:
        return [r for r in self.get_enabled_rules() if r.column == column]

    def load(self, config_path: Optional[str] = None, format: Optional[ConfigFormat] = None) -> None:
        path = config_path or self.config_path
        if not path:
            raise ValueError("配置文件路径未指定")

        if not os.path.exists(path):
            raise FileNotFoundError(f"配置文件不存在: {path}")

        if not format:
            if path.endswith((".yaml", ".yml")):
                format = ConfigFormat.YAML
            elif path.endswith(".json"):
                format = ConfigFormat.JSON
            else:
                raise ValueError(f"无法确定配置文件格式: {path}")

        with open(path, "r", encoding="utf-8") as f:
            if format == ConfigFormat.YAML:
                data = yaml.safe_load(f)
            else:
                data = json.load(f)

        self._parse_config(data)
        self.config_path = path

    def _parse_config(self, data: Dict[str, Any]) -> None:
        if "global_settings" in data:
            self.global_settings.update(data["global_settings"])

        if "rule_groups" in data:
            for group_data in data["rule_groups"]:
                group = RuleGroup(
                    name=group_data["name"],
                    description=group_data.get("description", ""),
                    priority=group_data.get("priority", 5),
                    enabled=group_data.get("enabled", True),
                )

                if "rules" in group_data:
                    for rule_data in group_data["rules"]:
                        rule_config = RuleConfig.from_dict(rule_data)
                        group.add_rule(rule_config.to_rule())

                self.add_rule_group(group)

        if "rules" in data:
            if "default" not in self.rule_groups:
                self.add_rule_group(RuleGroup(name="default"))

            for rule_data in data["rules"]:
                rule_config = RuleConfig.from_dict(rule_data)
                self.rule_groups["default"].add_rule(rule_config.to_rule())

    def save(
        self,
        config_path: Optional[str] = None,
        format: ConfigFormat = ConfigFormat.YAML,
    ) -> None:
        path = config_path or self.config_path
        if not path:
            raise ValueError("保存路径未指定")

        data = {
            "global_settings": self.global_settings,
            "rule_groups": [group.to_dict() for group in self.rule_groups.values()],
            "metadata": {
                "saved_at": datetime.now().isoformat(),
                "version": "1.0.0",
            },
        }

        with open(path, "w", encoding="utf-8") as f:
            if format == ConfigFormat.YAML:
                yaml.dump(data, f, allow_unicode=True, default_flow_style=False, sort_keys=False)
            else:
                json.dump(data, f, ensure_ascii=False, indent=2)

        self.config_path = path

    def to_dict(self) -> Dict[str, Any]:
        return {
            "global_settings": self.global_settings,
            "rule_groups": [group.to_dict() for group in self.rule_groups.values()],
        }

    def validate_config(self) -> List[str]:
        errors = []

        rule_names = set()
        for group in self.rule_groups.values():
            for rule in group.rules:
                if rule.name in rule_names:
                    errors.append(f"规则名称重复: {rule.name}")
                rule_names.add(rule.name)

                if rule.rule_type == RuleType.REFERENTIAL_INTEGRITY:
                    if not hasattr(rule, "reference_df"):
                        errors.append(
                            f"引用完整性规则 {rule.name} 需要配置 reference_df"
                        )

                if rule.rule_type == RuleType.BUSINESS:
                    if not hasattr(rule, "check_func"):
                        errors.append(
                            f"业务规则 {rule.name} 需要配置 check_func"
                        )

        return errors

    def export_rule_template(self, format: ConfigFormat = ConfigFormat.YAML) -> str:
        template = {
            "global_settings": {
                "default_severity": "error",
                "default_priority": 5,
                "fail_on_error": True,
                "max_failed_records": 1000,
                "output_format": "json",
                "alert_threshold": 80,
            },
            "rule_groups": [
                {
                    "name": "数据完整性",
                    "description": "数据完整性检查规则组",
                    "priority": 1,
                    "enabled": True,
                    "rules": [
                        {
                            "name": "用户ID非空检查",
                            "type": "null_check",
                            "column": "user_id",
                            "description": "确保用户ID不为空",
                            "severity": "error",
                            "priority": 1,
                            "enabled": True,
                            "params": {
                                "allow_empty_string": False,
                            },
                        },
                        {
                            "name": "用户ID唯一检查",
                            "type": "uniqueness",
                            "column": "user_id",
                            "description": "确保用户ID唯一",
                            "severity": "error",
                            "priority": 1,
                            "enabled": True,
                            "params": {
                                "ignore_nulls": True,
                            },
                        },
                    ],
                },
                {
                    "name": "数据有效性",
                    "description": "数据有效性检查规则组",
                    "priority": 2,
                    "enabled": True,
                    "rules": [
                        {
                            "name": "年龄范围检查",
                            "type": "range",
                            "column": "age",
                            "description": "年龄应在0-120之间",
                            "severity": "warning",
                            "priority": 3,
                            "enabled": True,
                            "params": {
                                "min_value": 0,
                                "max_value": 120,
                                "inclusive_min": True,
                                "inclusive_max": True,
                            },
                        },
                        {
                            "name": "邮箱格式检查",
                            "type": "format",
                            "column": "email",
                            "description": "确保邮箱格式正确",
                            "severity": "warning",
                            "priority": 3,
                            "enabled": True,
                            "params": {
                                "data_type": "email",
                            },
                        },
                    ],
                },
            ],
        }

        if format == ConfigFormat.YAML:
            return yaml.dump(template, allow_unicode=True, default_flow_style=False, sort_keys=False)
        else:
            return json.dumps(template, ensure_ascii=False, indent=2)
