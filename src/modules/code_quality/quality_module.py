"""
代码质量门禁实现
核心功能：
1. 多语言静态分析规则配置
2. 质量门禁检查
3. 报告生成
"""

from __future__ import annotations

import os
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.core import (
    CodeAnalyzerProtocol,
    QualityCheckError,
    QualityIssue,
    QualityReport,
    LoggerProtocol,
)


@dataclass
class QualityRule:
    id: str
    name: str
    description: str
    severity: str
    language: str
    pattern: str
    enabled: bool = True


class RuleSet:
    """规则集管理"""

    def __init__(self) -> None:
        self._rules: Dict[str, QualityRule] = {}
        self._load_default_rules()

    def _load_default_rules(self) -> None:
        default_rules = [
            QualityRule(
                id="PY001",
                name="No print statements",
                description="Avoid using print() in production code",
                severity="minor",
                language="python",
                pattern=r"\bprint\s*\(",
            ),
            QualityRule(
                id="PY002",
                name="Line too long",
                description="Line exceeds 120 characters",
                severity="minor",
                language="python",
                pattern=r".{121,}",
            ),
            QualityRule(
                id="PY003",
                name="No TODO comments",
                description="TODO comments should be resolved",
                severity="minor",
                language="python",
                pattern=r"#\s*(TODO|FIXME|XXX)",
            ),
            QualityRule(
                id="PY004",
                name="Hardcoded secrets",
                description="Potential hardcoded secret detected",
                severity="critical",
                language="python",
                pattern=r"(password|secret|key|token)\s*=\s*[\"'][^\"']{8,}[\"']",
            ),
            QualityRule(
                id="JS001",
                name="No console.log",
                description="Avoid using console.log in production code",
                severity="minor",
                language="javascript",
                pattern=r"\bconsole\.log\s*\(",
            ),
            QualityRule(
                id="JS002",
                name="Hardcoded secrets",
                description="Potential hardcoded secret detected",
                severity="critical",
                language="javascript",
                pattern=r"(password|secret|key|token)\s*[:=]\s*[\"'][^\"']{8,}[\"']",
            ),
            QualityRule(
                id="JAVA001",
                name="No System.out.println",
                description="Avoid using System.out.println in production code",
                severity="minor",
                language="java",
                pattern=r"\bSystem\.out\.println\s*\(",
            ),
            QualityRule(
                id="GEN001",
                name="No hardcoded IP addresses",
                description="Avoid hardcoded IP addresses",
                severity="major",
                language="all",
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


class BaseAnalyzer(CodeAnalyzerProtocol, ABC):
    """分析器基类"""

    def __init__(self, rule_set: RuleSet, language: str) -> None:
        self._rule_set = rule_set
        self._language = language

    def supports_language(self, language: str) -> bool:
        return language.lower() == self._language.lower()

    def get_available_rules(self) -> List[Dict[str, Any]]:
        rules = self._rule_set.get_rules_for_language(self._language)
        return [
            {
                "id": rule.id,
                "name": rule.name,
                "description": rule.description,
                "severity": rule.severity,
            }
            for rule in rules
        ]

    @abstractmethod
    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]: ...

    def _match_patterns(
        self,
        content: str,
        file_path: str,
        rules: List[QualityRule],
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
    """Python代码分析器"""

    def __init__(self, rule_set: RuleSet) -> None:
        super().__init__(rule_set, "python")

    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            raise QualityCheckError(f"Failed to read file: {e}") from e

        applicable_rules = [
            rule for rule in self._rule_set.get_rules_for_language("python")
            if not rules or rule.id in rules
        ]

        return self._match_patterns(content, file_path, applicable_rules)


class JavaScriptAnalyzer(BaseAnalyzer):
    """JavaScript代码分析器"""

    def __init__(self, rule_set: RuleSet) -> None:
        super().__init__(rule_set, "javascript")

    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            raise QualityCheckError(f"Failed to read file: {e}") from e

        applicable_rules = [
            rule for rule in self._rule_set.get_rules_for_language("javascript")
            if not rules or rule.id in rules
        ]

        return self._match_patterns(content, file_path, applicable_rules)


class JavaAnalyzer(BaseAnalyzer):
    """Java代码分析器"""

    def __init__(self, rule_set: RuleSet) -> None:
        super().__init__(rule_set, "java")

    def analyze(self, file_path: str, rules: List[str]) -> List[QualityIssue]:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            raise QualityCheckError(f"Failed to read file: {e}") from e

        applicable_rules = [
            rule for rule in self._rule_set.get_rules_for_language("java")
            if not rules or rule.id in rules
        ]

        return self._match_patterns(content, file_path, applicable_rules)


class AnalyzerDispatcher:
    """分析器分发器 - 根据语言选择合适的分析器"""

    def __init__(self) -> None:
        self._analyzers: Dict[str, CodeAnalyzerProtocol] = {}
        self._extensions: Dict[str, str] = {
            ".py": "python",
            ".js": "javascript",
            ".jsx": "javascript",
            ".ts": "javascript",
            ".tsx": "javascript",
            ".java": "java",
        }

    def register_analyzer(self, language: str, analyzer: CodeAnalyzerProtocol) -> None:
        self._analyzers[language.lower()] = analyzer

    def get_language_for_file(self, file_path: str) -> Optional[str]:
        _, ext = os.path.splitext(file_path)
        return self._extensions.get(ext.lower())

    def get_analyzer(self, language: str) -> Optional[CodeAnalyzerProtocol]:
        return self._analyzers.get(language.lower())

    def analyze_file(
        self,
        file_path: str,
        rules: Optional[List[str]] = None,
    ) -> List[QualityIssue]:
        language = self.get_language_for_file(file_path)
        if not language:
            return []

        analyzer = self.get_analyzer(language)
        if not analyzer:
            return []

        return analyzer.analyze(file_path, rules or [])


class ReportGenerator:
    """报告生成器"""

    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._logger = logger

    def generate_text_report(self, report: QualityReport) -> str:
        lines = [
            "=" * 60,
            f"Code Quality Report: {report.project_name}",
            "=" * 60,
            f"Generated at: {report.generated_at}",
            f"Total files analyzed: {report.total_files}",
            f"Quality Score: {report.score}/100",
            f"Status: {'PASSED' if report.passed else 'FAILED'}",
            "",
            f"Issues Found: {len(report.issues)}",
            "-" * 60,
        ]

        severity_groups: Dict[str, List[QualityIssue]] = {
            "critical": [],
            "major": [],
            "minor": [],
        }
        for issue in report.issues:
            severity_groups[issue.severity].append(issue)

        for severity in ["critical", "major", "minor"]:
            issues = severity_groups[severity]
            if issues:
                lines.append(f"\n{severity.upper()} ({len(issues)}):")
                for issue in issues:
                    lines.append(
                        f"  [{issue.rule_id}] {issue.file}:{issue.line}:{issue.column}"
                        f" - {issue.message}"
                    )

        return "\n".join(lines)

    def generate_json_report(self, report: QualityReport) -> str:
        import json
        return json.dumps(
            {
                "project_name": report.project_name,
                "generated_at": report.generated_at,
                "total_files": report.total_files,
                "score": report.score,
                "passed": report.passed,
                "issues": [
                    {
                        "file": issue.file,
                        "line": issue.line,
                        "column": issue.column,
                        "severity": issue.severity,
                        "rule_id": issue.rule_id,
                        "message": issue.message,
                        "language": issue.language,
                    }
                    for issue in report.issues
                ],
            },
            indent=2,
            ensure_ascii=False,
        )

    def generate_html_report(self, report: QualityReport) -> str:
        status_color = "#28a745" if report.passed else "#dc3545"
        severity_colors = {
            "critical": "#dc3545",
            "major": "#fd7e14",
            "minor": "#ffc107",
        }

        issues_html = ""
        for issue in report.issues:
            color = severity_colors.get(issue.severity, "#6c757d")
            issues_html += f"""
            <tr>
                <td><span style="color: {color};">{issue.severity.upper()}</span></td>
                <td>{issue.rule_id}</td>
                <td>{issue.file}</td>
                <td>{issue.line}:{issue.column}</td>
                <td>{issue.message}</td>
            </tr>
            """

        return f"""
        <!DOCTYPE html>
        <html>
        <head>
            <title>Code Quality Report - {report.project_name}</title>
            <style>
                body {{ font-family: Arial, sans-serif; margin: 20px; }}
                .header {{ background: #f8f9fa; padding: 20px; border-radius: 5px; }}
                .score {{ font-size: 48px; font-weight: bold; color: {status_color}; }}
                table {{ width: 100%; border-collapse: collapse; margin-top: 20px; }}
                th, td {{ padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }}
                th {{ background: #f8f9fa; }}
            </style>
        </head>
        <body>
            <div class="header">
                <h1>Code Quality Report: {report.project_name}</h1>
                <div class="score">{report.score}/100</div>
                <p>Status: <strong style="color: {status_color};">{'PASSED' if report.passed else 'FAILED'}</strong></p>
                <p>Total files: {report.total_files} | Issues: {len(report.issues)}</p>
            </div>
            <table>
                <thead>
                    <tr>
                        <th>Severity</th>
                        <th>Rule ID</th>
                        <th>File</th>
                        <th>Location</th>
                        <th>Message</th>
                    </tr>
                </thead>
                <tbody>
                    {issues_html}
                </tbody>
            </table>
        </body>
        </html>
        """


class CodeQualityGate:
    """
    代码质量门禁 - 核心类
    整合分析、规则检查、报告生成
    """

    def __init__(
        self,
        rule_set: Optional[RuleSet] = None,
        dispatcher: Optional[AnalyzerDispatcher] = None,
        report_generator: Optional[ReportGenerator] = None,
        logger: Optional[LoggerProtocol] = None,
        threshold: int = 80,
    ) -> None:
        self._rule_set = rule_set or RuleSet()
        self._dispatcher = dispatcher or AnalyzerDispatcher()
        self._report_generator = report_generator or ReportGenerator(logger)
        self._logger = logger
        self._threshold = threshold

        self._init_default_analyzers()

    def _init_default_analyzers(self) -> None:
        self._dispatcher.register_analyzer("python", PythonAnalyzer(self._rule_set))
        self._dispatcher.register_analyzer("javascript", JavaScriptAnalyzer(self._rule_set))
        self._dispatcher.register_analyzer("java", JavaAnalyzer(self._rule_set))

    def _collect_files(self, path: str) -> List[str]:
        files = []
        if os.path.isfile(path):
            return [path]
        for root, _, filenames in os.walk(path):
            for filename in filenames:
                ext = os.path.splitext(filename)[1].lower()
                if ext in self._dispatcher._extensions:
                    files.append(os.path.join(root, filename))
        return files

    async def check_project(
        self,
        project_path: str,
        project_name: Optional[str] = None,
        rules: Optional[List[str]] = None,
    ) -> QualityReport:
        """检查项目代码质量"""
        name = project_name or os.path.basename(os.path.abspath(project_path))
        report = QualityReport(project_name=name)

        try:
            files = self._collect_files(project_path)
            report.total_files = len(files)

            if self._logger:
                self._logger.info(
                    "Starting quality check",
                    project=name,
                    files_count=len(files),
                )

            for file_path in files:
                try:
                    issues = self._dispatcher.analyze_file(file_path, rules)
                    for issue in issues:
                        report.add_issue(issue)
                except Exception as e:
                    if self._logger:
                        self._logger.warning(
                            "Failed to analyze file",
                            file=file_path,
                            error=str(e),
                        )

            report.passed = report.score >= self._threshold

            if self._logger:
                self._logger.info(
                    "Quality check completed",
                    project=name,
                    score=report.score,
                    passed=report.passed,
                    issues_count=len(report.issues),
                )

        except Exception as e:
            raise QualityCheckError(f"Quality check failed: {e}") from e

        return report

    def generate_report(
        self,
        report: QualityReport,
        format: str = "text",
    ) -> str:
        """生成指定格式的报告"""
        if format == "json":
            return self._report_generator.generate_json_report(report)
        elif format == "html":
            return self._report_generator.generate_html_report(report)
        else:
            return self._report_generator.generate_text_report(report)

    def get_rule_set(self) -> RuleSet:
        return self._rule_set
