"""
规则管理组件 - 独立于分析器和报告
"""

from __future__ import annotations

from typing import Dict, List, Optional

from src.domain.models.quality import QualityRule


class RuleSet:
    def __init__(self) -> None:
        self._rules: Dict[str, QualityRule] = {}
        self._load_default_rules()

    def _load_default_rules(self) -> None:
        default_rules = [
            QualityRule(
                id="PY001", name="No print statements",
                description="Avoid using print() in production code",
                severity="minor", language="python", pattern=r"\bprint\s*\(",
            ),
            QualityRule(
                id="PY002", name="Line too long",
                description="Line exceeds 120 characters",
                severity="minor", language="python", pattern=r".{121,}",
            ),
            QualityRule(
                id="PY003", name="No TODO comments",
                description="TODO comments should be resolved",
                severity="minor", language="python", pattern=r"#\s*(TODO|FIXME|XXX)",
            ),
            QualityRule(
                id="PY004", name="Hardcoded secrets",
                description="Potential hardcoded secret detected",
                severity="critical", language="python",
                pattern=r"(password|secret|key|token)\s*=\s*[\"'][^\"']{8,}[\"']",
            ),
            QualityRule(
                id="PY005", name="Shared mutable state without lock",
                description="Mutable global/shared variable without synchronization",
                severity="critical", language="python",
                pattern=r"^\s*(\w+)\s*=\s*\[(.*?)\]",
            ),
            QualityRule(
                id="JS001", name="No console.log",
                description="Avoid using console.log in production code",
                severity="minor", language="javascript", pattern=r"\bconsole\.log\s*\(",
            ),
            QualityRule(
                id="JS002", name="Hardcoded secrets",
                description="Potential hardcoded secret detected",
                severity="critical", language="javascript",
                pattern=r"(password|secret|key|token)\s*[:=]\s*[\"'][^\"']{8,}[\"']",
            ),
            QualityRule(
                id="JAVA001", name="No System.out.println",
                description="Avoid using System.out.println in production code",
                severity="minor", language="java",
                pattern=r"\bSystem\.out\.println\s*\(",
            ),
            QualityRule(
                id="GEN001", name="No hardcoded IP addresses",
                description="Avoid hardcoded IP addresses",
                severity="major", language="all",
                pattern=r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b",
            ),
        ]
        for rule in default_rules:
            self._rules[rule.id] = rule

    def add_rule(self, rule: QualityRule) -> None:
        self._rules[rule.id] = rule

    def get_rule(self, rule_id: str) -> Optional[QualityRule]:
        return self._rules.get(rule_id)

    def enable_rule(self, rule_id: str) -> None:
        if rule_id in self._rules:
            self._rules[rule_id].enabled = True

    def disable_rule(self, rule_id: str) -> None:
        if rule_id in self._rules:
            self._rules[rule_id].enabled = False

    def get_rules_for_language(self, language: str) -> List[QualityRule]:
        return [
            rule for rule in self._rules.values()
            if rule.enabled and (rule.language == language or rule.language == "all")
        ]

    def get_all_rules(self) -> List[QualityRule]:
        return list(self._rules.values())
