import json
import re
import threading
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Pattern

import yaml


class RuleSeverity(str, Enum):
    BLOCKER = "blocker"
    CRITICAL = "critical"
    MAJOR = "major"
    MINOR = "minor"
    INFO = "info"


@dataclass
class QualityRule:
    id: str
    name: str
    language: str
    severity: RuleSeverity
    pattern: str
    message: str
    enabled: bool = True
    category: str = "general"
    remediation_effort: str = "5min"


@dataclass
class QualityIssue:
    rule_id: str
    rule_name: str
    severity: RuleSeverity
    message: str
    file_path: str
    line: int = 0
    column: int = 0
    snippet: str = ""
    suggestion: Optional[str] = None


@dataclass
class QualityReport:
    project_name: str
    language: str
    timestamp: datetime = field(default_factory=datetime.utcnow)
    issues: List[QualityIssue] = field(default_factory=list)
    files_analyzed: int = 0
    total_lines: int = 0
    passed: bool = False

    def get_severity_counts(self) -> Dict[str, int]:
        counts = {s.value: 0 for s in RuleSeverity}
        for issue in self.issues:
            counts[issue.severity.value] += 1
        return counts

    def to_dict(self) -> Dict[str, Any]:
        return {
            "project_name": self.project_name,
            "language": self.language,
            "timestamp": self.timestamp.isoformat(),
            "files_analyzed": self.files_analyzed,
            "total_lines": self.total_lines,
            "passed": self.passed,
            "severity_counts": self.get_severity_counts(),
            "issues": [
                {
                    "rule_id": i.rule_id,
                    "rule_name": i.rule_name,
                    "severity": i.severity,
                    "message": i.message,
                    "file_path": i.file_path,
                    "line": i.line,
                    "column": i.column,
                    "snippet": i.snippet,
                    "suggestion": i.suggestion
                }
                for i in self.issues
            ]
        }


class RuleEngine:
    def __init__(self):
        self._rules: Dict[str, List[QualityRule]] = {}
        self._compiled_patterns: Dict[str, Pattern] = {}
        self._lock = threading.Lock()

    def load_rules(self, rules_path: Optional[str] = None) -> None:
        if rules_path and Path(rules_path).exists():
            self._load_rules_from_file(rules_path)
        else:
            self._load_default_rules()

    def _load_rules_from_file(self, rules_path: str) -> None:
        path = Path(rules_path)
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        rules = data.get("rules", [])
        with self._lock:
            self._rules.clear()
            self._compiled_patterns.clear()
            for rule_data in rules:
                rule = QualityRule(
                    id=rule_data.get("id", ""),
                    name=rule_data.get("name", ""),
                    language=rule_data.get("language", "python"),
                    severity=RuleSeverity(rule_data.get("severity", "minor")),
                    pattern=rule_data.get("pattern", ""),
                    message=rule_data.get("message", ""),
                    enabled=rule_data.get("enabled", True),
                    category=rule_data.get("category", "general"),
                    remediation_effort=rule_data.get("remediation_effort", "5min")
                )
                self._add_rule(rule)

    def _load_default_rules(self) -> None:
        default_rules = [
            QualityRule(
                id="PY001",
                name="No hardcoded secrets",
                language="python",
                severity=RuleSeverity.CRITICAL,
                pattern=r"password\s*=\s*['\"][^'\"]+['\"]|api[_-]?key\s*=\s*['\"][^'\"]+['\"]|secret\s*=\s*['\"][^'\"]+['\"]",
                message="检测到硬编码的敏感信息，请使用环境变量或配置管理",
                category="security"
            ),
            QualityRule(
                id="PY002",
                name="Avoid eval usage",
                language="python",
                severity=RuleSeverity.BLOCKER,
                pattern=r"\beval\s*\(",
                message="避免使用 eval() 函数，可能存在安全风险",
                category="security"
            ),
            QualityRule(
                id="PY003",
                name="Avoid exec usage",
                language="python",
                severity=RuleSeverity.BLOCKER,
                pattern=r"\bexec\s*\(",
                message="避免使用 exec() 函数，可能存在安全风险",
                category="security"
            ),
            QualityRule(
                id="PY004",
                name="No SQL injection risk",
                language="python",
                severity=RuleSeverity.CRITICAL,
                pattern=r"(?:execute|cursor\.(?:execute|callproc))\s*\(\s*f?[\"'].*?%(?:s|d|\()",
                message="检测到潜在的SQL注入风险，请使用参数化查询",
                category="security"
            ),
            QualityRule(
                id="PY005",
                name="Use proper imports",
                language="python",
                severity=RuleSeverity.MINOR,
                pattern=r"from\s+\*\s+import|import\s+\*",
                message="避免使用通配符导入，明确导入需要的内容",
                category="style"
            ),
            QualityRule(
                id="PY006",
                name="Too long line",
                language="python",
                severity=RuleSeverity.MINOR,
                pattern=r".{121,}",
                message="行长度超过120字符，考虑换行",
                category="style"
            ),
            QualityRule(
                id="PY007",
                name="TODO or FIXME comment",
                language="python",
                severity=RuleSeverity.INFO,
                pattern=r"#\s*(TODO|FIXME|XXX|HACK)",
                message="代码中存在待处理的标记",
                category="maintainability"
            ),
            QualityRule(
                id="PY008",
                name="Exception too broad",
                language="python",
                severity=RuleSeverity.MAJOR,
                pattern=r"except\s*:\s*$|except\s+Exception\s*:",
                message="捕获异常过于宽泛，建议指定具体的异常类型",
                category="error-handling"
            ),
            QualityRule(
                id="JS001",
                name="No eval in JS",
                language="javascript",
                severity=RuleSeverity.BLOCKER,
                pattern=r"\beval\s*\(",
                message="避免在JavaScript中使用 eval()",
                category="security"
            ),
            QualityRule(
                id="JS002",
                name="No innerHTML",
                language="javascript",
                severity=RuleSeverity.MAJOR,
                pattern=r"\.innerHTML\s*=",
                message="使用 innerHTML 可能存在XSS风险，建议使用 textContent",
                category="security"
            ),
            QualityRule(
                id="GO001",
                name="No panic in Go",
                language="go",
                severity=RuleSeverity.MAJOR,
                pattern=r"\bpanic\s*\(",
                message="避免使用 panic，建议返回 error",
                category="error-handling"
            ),
            QualityRule(
                id="JAVA001",
                name="No printStackTrace",
                language="java",
                severity=RuleSeverity.MINOR,
                pattern=r"\.printStackTrace\s*\(",
                message="使用日志框架替代 printStackTrace()",
                category="logging"
            )
        ]
        with self._lock:
            self._rules.clear()
            self._compiled_patterns.clear()
            for rule in default_rules:
                self._add_rule(rule)

    def _add_rule(self, rule: QualityRule) -> None:
        if rule.language not in self._rules:
            self._rules[rule.language] = []
        self._rules[rule.language].append(rule)
        try:
            self._compiled_patterns[rule.id] = re.compile(rule.pattern, re.MULTILINE)
        except re.error:
            pass

    def get_rules(self, language: Optional[str] = None) -> List[QualityRule]:
        with self._lock:
            if language:
                return [r for r in self._rules.get(language, []) if r.enabled]
            all_rules: List[QualityRule] = []
            for rules in self._rules.values():
                all_rules.extend([r for r in rules if r.enabled])
            return all_rules

    def add_rule(self, rule: QualityRule) -> None:
        with self._lock:
            self._add_rule(rule)

    def disable_rule(self, rule_id: str) -> bool:
        with self._lock:
            for rules in self._rules.values():
                for rule in rules:
                    if rule.id == rule_id:
                        rule.enabled = False
                        return True
        return False

    def enable_rule(self, rule_id: str) -> bool:
        with self._lock:
            for rules in self._rules.values():
                for rule in rules:
                    if rule.id == rule_id:
                        rule.enabled = True
                        return True
        return False


class StaticAnalyzer:
    def __init__(self, rule_engine: Optional[RuleEngine] = None):
        self.rule_engine = rule_engine or RuleEngine()
        self._language_extensions: Dict[str, List[str]] = {
            "python": [".py", ".pyw"],
            "javascript": [".js", ".jsx", ".ts", ".tsx"],
            "go": [".go"],
            "java": [".java"],
            "typescript": [".ts", ".tsx"]
        }

    def detect_language(self, file_path: str) -> Optional[str]:
        ext = Path(file_path).suffix.lower()
        for lang, exts in self._language_extensions.items():
            if ext in exts:
                return lang
        return None

    def analyze_file(self, file_path: str, language: Optional[str] = None) -> List[QualityIssue]:
        path = Path(file_path)
        if not path.exists():
            return []
        detected_lang = language or self.detect_language(str(path))
        if not detected_lang:
            return []

        rules = self.rule_engine.get_rules(detected_lang)
        if not rules:
            return []

        issues: List[QualityIssue] = []
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                lines = f.readlines()
        except Exception:
            return []

        for line_num, line in enumerate(lines, 1):
            for rule in rules:
                if rule.id not in self.rule_engine._compiled_patterns:
                    continue
                pattern = self.rule_engine._compiled_patterns[rule.id]
                if match := pattern.search(line):
                    snippet = line.strip()[:100]
                    issues.append(QualityIssue(
                        rule_id=rule.id,
                        rule_name=rule.name,
                        severity=rule.severity,
                        message=rule.message,
                        file_path=str(path),
                        line=line_num,
                        column=match.start() + 1,
                        snippet=snippet
                    ))
        return issues

    def analyze_directory(
        self,
        directory: str,
        languages: Optional[List[str]] = None,
        exclude_patterns: Optional[List[str]] = None
    ) -> QualityReport:
        import fnmatch
        path = Path(directory)
        if not path.is_dir():
            raise ValueError(f"不是有效的目录: {directory}")

        exclude = exclude_patterns or [
            "__pycache__", "node_modules", ".git", ".venv", "venv",
            "build", "dist", "*.pyc", "*.pyo", "*.class"
        ]

        all_issues: List[QualityIssue] = []
        files_analyzed = 0
        total_lines = 0

        for root, dirs, files in path.walk():
            dirs[:] = [d for d in dirs if not any(fnmatch.fnmatch(d, p) for p in exclude)]
            for file_name in files:
                if any(fnmatch.fnmatch(file_name, p) for p in exclude):
                    continue
                file_path = Path(root) / file_name
                lang = self.detect_language(str(file_path))
                if not lang:
                    continue
                if languages and lang not in languages:
                    continue
                try:
                    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                        total_lines += sum(1 for _ in f)
                except Exception:
                    pass
                issues = self.analyze_file(str(file_path), lang)
                all_issues.extend(issues)
                files_analyzed += 1

        report = QualityReport(
            project_name=path.name,
            language="multi" if not languages else ",".join(languages),
            issues=all_issues,
            files_analyzed=files_analyzed,
            total_lines=total_lines
        )
        return report


class QualityGate:
    def __init__(self, rules_path: Optional[str] = None):
        self.rule_engine = RuleEngine()
        self.rule_engine.load_rules(rules_path)
        self.analyzer = StaticAnalyzer(self.rule_engine)
        self._thresholds: Dict[str, int] = {
            RuleSeverity.BLOCKER.value: 0,
            RuleSeverity.CRITICAL.value: 0,
            RuleSeverity.MAJOR.value: 10,
            RuleSeverity.MINOR.value: 50,
            RuleSeverity.INFO.value: 100
        }

    def set_threshold(self, severity: RuleSeverity, max_allowed: int) -> None:
        self._thresholds[severity.value] = max_allowed

    def check_quality(
        self,
        target: str,
        languages: Optional[List[str]] = None,
        custom_thresholds: Optional[Dict[str, int]] = None
    ) -> QualityReport:
        path = Path(target)
        if path.is_file():
            issues = self.analyzer.analyze_file(target)
            report = QualityReport(
                project_name=path.name,
                language=self.analyzer.detect_language(target) or "unknown",
                issues=issues,
                files_analyzed=1
            )
        else:
            report = self.analyzer.analyze_directory(target, languages)

        thresholds = custom_thresholds or self._thresholds
        severity_counts = report.get_severity_counts()
        passed = True
        for severity, count in severity_counts.items():
            if count > thresholds.get(severity, 0):
                passed = False
                break
        report.passed = passed
        return report

    def generate_html_report(self, report: QualityReport) -> str:
        severity_colors = {
            RuleSeverity.BLOCKER.value: "#d73a49",
            RuleSeverity.CRITICAL.value: "#d73a49",
            RuleSeverity.MAJOR.value: "#e36209",
            RuleSeverity.MINOR.value: "#ffd33d",
            RuleSeverity.INFO.value: "#28a745"
        }
        counts = report.get_severity_counts()

        html = f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Quality Report - {report.project_name}</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; }}
        .header {{ text-align: center; margin-bottom: 30px; }}
        .status {{ font-size: 24px; font-weight: bold; padding: 10px 20px; border-radius: 8px; display: inline-block; }}
        .status.pass {{ background: #dcffe4; color: #22863a; }}
        .status.fail {{ background: #ffeef0; color: #d73a49; }}
        .summary {{ display: flex; gap: 15px; justify-content: center; margin: 20px 0; flex-wrap: wrap; }}
        .metric {{ padding: 15px 25px; border-radius: 8px; text-align: center; min-width: 100px; }}
        .issues {{ max-width: 1000px; margin: 30px auto; }}
        .issue {{ border-left: 4px solid; margin: 10px 0; padding: 15px; background: #fafbfc; }}
        .issue-header {{ font-weight: bold; margin-bottom: 8px; }}
        .location {{ color: #6a737d; font-size: 0.9em; }}
        .snippet {{ background: #f6f8fa; padding: 10px; border-radius: 4px; font-family: monospace; margin-top: 8px; font-size: 0.9em; overflow-x: auto; }}
    </style>
</head>
<body>
    <div class="header">
        <h1>代码质量报告</h1>
        <h2>{report.project_name}</h2>
        <p>分析时间: {report.timestamp.strftime('%Y-%m-%d %H:%M:%S')}</p>
        <div class="status {'pass' if report.passed else 'fail'}">
            {'PASS' if report.passed else 'FAIL'}
        </div>
    </div>
    <div class="summary">
        <div class="metric"><b>分析文件</b><br>{report.files_analyzed}</div>
        <div class="metric"><b>代码行数</b><br>{report.total_lines}</div>
"""
        for severity, count in counts.items():
            color = severity_colors.get(severity, "#6a737d")
            html += f'        <div class="metric" style="border: 2px solid {color};"><b>{severity.upper()}</b><br>{count}</div>\n'

        html += """    </div>
    <div class="issues">
        <h3>问题详情 ({0})</h3>
""".format(len(report.issues))

        for issue in sorted(report.issues, key=lambda x: list(RuleSeverity).index(x.severity)):
            color = severity_colors.get(issue.severity.value, "#6a737d")
            html += f"""        <div class="issue" style="border-left-color: {color};">
            <div class="issue-header">
                [{issue.severity.value.upper()}] {issue.rule_id}: {issue.rule_name}
            </div>
            <div class="location">{issue.file_path}:{issue.line}:{issue.column}</div>
            <div>{issue.message}</div>
"""
            if issue.snippet:
                html += f'            <div class="snippet">{issue.snippet}</div>\n'
            html += "        </div>\n"

        html += """    </div>
</body>
</html>"""
        return html


_gate_instance: Optional[QualityGate] = None
_gate_lock = threading.Lock()


def get_quality_gate() -> QualityGate:
    global _gate_instance
    if _gate_instance is None:
        with _gate_lock:
            if _gate_instance is None:
                from app.config.settings import get_settings
                settings = get_settings()
                _gate_instance = QualityGate(settings.quality_rules_path)
    return _gate_instance


def run_quality_check(
    target: str,
    languages: Optional[List[str]] = None
) -> QualityReport:
    gate = get_quality_gate()
    return gate.check_quality(target, languages)
