"""
代码分析器组件 - 多语言静态分析 + 并发安全分析
"""

from __future__ import annotations

import os
import re
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from src.domain.contracts.quality import CodeAnalyzerProtocol, IsolationLevel
from src.domain.errors.quality import QualityCheckError
from src.domain.models.quality import QualityIssue, ConcurrencyIssue
from src.modules.quality.rule import RuleSet


class BaseAnalyzer(CodeAnalyzerProtocol, ABC):
    def __init__(self, rule_set: RuleSet, language: str) -> None:
        self._rule_set = rule_set
        self._language = language

    def supports_language(self, language: str) -> bool:
        return language.lower() == self._language.lower()

    def get_available_rules(self) -> List[Dict[str, Any]]:
        rules = self._rule_set.get_rules_for_language(self._language)
        return [
            {"id": r.id, "name": r.name, "description": r.description, "severity": r.severity}
            for r in rules
        ]

    @abstractmethod
    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]: ...

    def _match_patterns(
        self, content: str, file_path: str, rules: List[QualityRule]
    ) -> List[QualityIssue]:
        issues = []
        lines = content.split("\n")
        for line_num, line in enumerate(lines, 1):
            for rule in rules:
                if not rule.enabled:
                    continue
                try:
                    if re.search(rule.pattern, line):
                        issues.append(
                            QualityIssue(
                                file=file_path,
                                line=line_num,
                                column=line.find(line.strip()) + 1,
                                severity=rule.severity,
                                rule_id=rule.id,
                                message=f"{rule.name}: {rule.description}",
                                language=self._language,
                            )
                        )
                except re.error:
                    continue
        return issues


class PythonAnalyzer(BaseAnalyzer):
    def __init__(self, rule_set: RuleSet) -> None:
        super().__init__(rule_set, "python")

    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            raise QualityCheckError(f"Failed to read file: {e}") from e
        applicable = [
            r for r in self._rule_set.get_rules_for_language("python")
            if not rules or r.id in rules
        ]
        return self._match_patterns(content, file_path, applicable)


class JavaScriptAnalyzer(BaseAnalyzer):
    def __init__(self, rule_set: RuleSet) -> None:
        super().__init__(rule_set, "javascript")

    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            raise QualityCheckError(f"Failed to read file: {e}") from e
        applicable = [
            r for r in self._rule_set.get_rules_for_language("javascript")
            if not rules or r.id in rules
        ]
        return self._match_patterns(content, file_path, applicable)


class JavaAnalyzer(BaseAnalyzer):
    def __init__(self, rule_set: RuleSet) -> None:
        super().__init__(rule_set, "java")

    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            raise QualityCheckError(f"Failed to read file: {e}") from e
        applicable = [
            r for r in self._rule_set.get_rules_for_language("java")
            if not rules or r.id in rules
        ]
        return self._match_patterns(content, file_path, applicable)


class ConcurrencyAnalyzer:
    """
    并发安全分析器 - 检测共享可变状态、竞态条件、死锁风险
    按隔离级别评估并发安全性
    """

    MUTABLE_GLOBAL_PATTERN = re.compile(
        r"^\s*(\w+)\s*=\s*(\[\]|\{\}|dict\(|set\(|list\(|\[|{)"
    )
    SHARED_STATE_PATTERN = re.compile(
        r"(global\s+\w+|nonlocal\s+\w+)"
    )
    LOCK_USAGE_PATTERN = re.compile(
        r"(Lock\(|RLock\(|Semaphore\(|with\s+\w+\.lock|threading\.Lock|asyncio\.Lock|synchronized)"
    )
    RACE_CONDITION_PATTERN = re.compile(
        r"(\w+)\[.*\]\s*=\s*.*|\.\w+\.(append|extend|remove|pop|update|add|discard)\("
    )

    def __init__(
        self,
        isolation_level: IsolationLevel = IsolationLevel.MODULE,
    ) -> None:
        self._isolation_level = isolation_level

    def analyze_file(
        self,
        file_path: str,
        language: str = "python",
    ) -> List[ConcurrencyIssue]:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception:
            return []

        issues: List[ConcurrencyIssue] = []
        lines = content.split("\n")
        has_lock = bool(self.LOCK_USAGE_PATTERN.search(content))

        for line_num, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("#") or stripped.startswith('"""') or stripped.startswith("'''"):
                continue

            if self.MUTABLE_GLOBAL_PATTERN.match(stripped):
                var_match = self.MUTABLE_GLOBAL_PATTERN.match(stripped)
                var_name = var_match.group(1) if var_match else "unknown"
                severity = "minor" if has_lock else "critical"
                issues.append(
                    ConcurrencyIssue(
                        file=file_path,
                        line=line_num,
                        issue_type="shared_mutable_state",
                        severity=severity,
                        description=f"Mutable global variable '{var_name}' may cause race conditions",
                        isolation_level=self._isolation_level.value,
                        shared_resource=var_name,
                    )
                )

            if self.SHARED_STATE_PATTERN.search(stripped):
                issues.append(
                    ConcurrencyIssue(
                        file=file_path,
                        line=line_num,
                        issue_type="shared_state_access",
                        severity="major",
                        description="Global/nonlocal variable access detected",
                        isolation_level=self._isolation_level.value,
                    )
                )

        return issues

    def evaluate_isolation(
        self,
        issues: List[ConcurrencyIssue],
    ) -> IsolationLevel:
        if not issues:
            return IsolationLevel.PROJECT

        critical_count = sum(1 for i in issues if i.severity == "critical")
        major_count = sum(1 for i in issues if i.severity == "major")

        if critical_count > 0:
            return IsolationLevel.NONE
        if major_count > 2:
            return IsolationLevel.FILE
        if major_count > 0:
            return IsolationLevel.MODULE
        return IsolationLevel.PROJECT


class AnalyzerDispatcher:
    def __init__(self) -> None:
        self._analyzers: Dict[str, CodeAnalyzerProtocol] = {}
        self._extensions: Dict[str, str] = {
            ".py": "python", ".js": "javascript", ".jsx": "javascript",
            ".ts": "javascript", ".tsx": "javascript", ".java": "java",
        }

    def register_analyzer(self, language: str, analyzer: CodeAnalyzerProtocol) -> None:
        self._analyzers[language.lower()] = analyzer

    def get_language_for_file(self, file_path: str) -> Optional[str]:
        _, ext = os.path.splitext(file_path)
        return self._extensions.get(ext.lower())

    def get_analyzer(self, language: str) -> Optional[CodeAnalyzerProtocol]:
        return self._analyzers.get(language.lower())

    def analyze_file(self, file_path: str, rules: Optional[List[str]] = None) -> List[QualityIssue]:
        language = self.get_language_for_file(file_path)
        if not language:
            return []
        analyzer = self.get_analyzer(language)
        if not analyzer:
            return []
        return analyzer.analyze(file_path, rules or [])
