"""
代码质量门禁领域模型 - 含并发隔离
"""

from __future__ import annotations

import enum
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class QualityIssue:
    file: str
    line: int
    column: int
    severity: str
    rule_id: str
    message: str
    language: str


@dataclass
class ConcurrencyIssue:
    file: str
    line: int
    issue_type: str
    severity: str
    description: str
    isolation_level: str = "none"
    shared_resource: str = ""


@dataclass
class QualityRule:
    id: str
    name: str
    description: str
    severity: str
    language: str
    pattern: str
    enabled: bool = True


@dataclass
class QualityReport:
    project_name: str
    total_files: int = 0
    issues: List[QualityIssue] = field(default_factory=list)
    concurrency_issues: List[ConcurrencyIssue] = field(default_factory=list)
    score: int = 100
    passed: bool = True
    generated_at: float = field(default_factory=time.time)

    def add_issue(self, issue: QualityIssue) -> None:
        self.issues.append(issue)
        self._recalculate_score()

    def add_concurrency_issue(self, issue: ConcurrencyIssue) -> None:
        self.concurrency_issues.append(issue)
        self._recalculate_score()

    def _recalculate_score(self) -> None:
        critical = sum(1 for i in self.issues if i.severity == "critical")
        major = sum(1 for i in self.issues if i.severity == "major")
        minor = sum(1 for i in self.issues if i.severity == "minor")
        conc_critical = sum(1 for i in self.concurrency_issues if i.severity == "critical")
        conc_major = sum(1 for i in self.concurrency_issues if i.severity == "major")
        score = 100 - ((critical + conc_critical) * 10 + (major + conc_major) * 5 + minor * 1)
        self.score = max(0, score)
        self.passed = self.score >= 80
