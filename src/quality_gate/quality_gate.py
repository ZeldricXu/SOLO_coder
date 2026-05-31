import ast
import json
import os
import re
import subprocess
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import yaml

from src.config import get_settings
from src.logging_ import get_logger
from src.models import QualityGateReport, QualityGateRule, QualityGateStatus
from src.utils.errors import QualityGateError
from src.utils.helpers import generate_id

logger = get_logger(__name__)


@dataclass
class LanguageRule:
    language: str
    name: str
    pattern: str
    severity: str = "warning"
    description: str = ""
    enabled: bool = True


@dataclass
class AnalysisResult:
    file_path: str
    language: str
    issues: List[Dict[str, Any]] = field(default_factory=list)
    complexity_score: float = 0.0
    line_count: int = 0
    duplicate_lines: int = 0
    violations: List[Dict[str, Any]] = field(default_factory=list)


class RuleEngine:
    def __init__(self):
        self._rules: Dict[str, List[LanguageRule]] = defaultdict(list)
        self._load_default_rules()

    def _load_default_rules(self) -> None:
        self._rules["python"] = [
            LanguageRule(
                language="python",
                name="E501",
                pattern=r".{89,}",
                severity="warning",
                description="Line too long (>88 characters)",
            ),
            LanguageRule(
                language="python",
                name="F401",
                pattern=r"^import\s+\w+\s*$|^from\s+\S+\s+import\s+\w+\s*$",
                severity="warning",
                description="Potential unused import (requires semantic analysis)",
            ),
            LanguageRule(
                language="python",
                name="COMPLEX_FUNC",
                pattern=r"def\s+\w+\s*\([^)]*\)\s*:",
                severity="warning",
                description="Function definition (for complexity analysis)",
            ),
            LanguageRule(
                language="python",
                name="PRINT_STMT",
                pattern=r"\bprint\s*\(",
                severity="warning",
                description="Use of print statement instead of logging",
            ),
            LanguageRule(
                language="python",
                name="TODO_COMMENT",
                pattern=r"#\s*(TODO|FIXME|XXX)",
                severity="info",
                description="TODO/FIXME comment found",
            ),
            LanguageRule(
                language="python",
                name="HARDCODED_SECRET",
                pattern=r"(password|secret|token|api_key)\s*[=:]\s*['\"][^'\"]{8,}['\"]",
                severity="critical",
                description="Potential hardcoded secret",
            ),
        ]

        self._rules["javascript"] = [
            LanguageRule(
                language="javascript",
                name="MAX_LEN",
                pattern=r".{121,}",
                severity="warning",
                description="Line too long (>120 characters)",
            ),
            LanguageRule(
                language="javascript",
                name="CONSOLE_LOG",
                pattern=r"\bconsole\.log\s*\(",
                severity="warning",
                description="Use of console.log instead of proper logging",
            ),
            LanguageRule(
                language="javascript",
                name="VAR_DECL",
                pattern=r"\bvar\s+\w+",
                severity="warning",
                description="Use 'const' or 'let' instead of 'var'",
            ),
            LanguageRule(
                language="javascript",
                name="HARDCODED_SECRET",
                pattern=r"(password|secret|token|apiKey)\s*[=:]\s*['\"][^'\"]{8,}['\"]",
                severity="critical",
                description="Potential hardcoded secret",
            ),
        ]

        self._rules["java"] = [
            LanguageRule(
                language="java",
                name="MAX_LEN",
                pattern=r".{151,}",
                severity="warning",
                description="Line too long (>150 characters)",
            ),
            LanguageRule(
                language="java",
                name="SYSOUT",
                pattern=r"System\.out\.print",
                severity="warning",
                description="Use of System.out instead of logging",
            ),
            LanguageRule(
                language="java",
                name="MAGIC_NUMBER",
                pattern=r"\b\d{4,}\b",
                severity="info",
                description="Potential magic number, consider using a constant",
            ),
        ]

        self._rules["typescript"] = [
            LanguageRule(
                language="typescript",
                name="ANY_TYPE",
                pattern=r":\s*any\b",
                severity="warning",
                description="Avoid using 'any' type",
            ),
        ]

    def add_rule(self, rule: LanguageRule) -> None:
        self._rules[rule.language].append(rule)
        logger.info("Added rule %s for %s", rule.name, rule.language)

    def remove_rule(self, language: str, rule_name: str) -> bool:
        if language in self._rules:
            original_len = len(self._rules[language])
            self._rules[language] = [
                r for r in self._rules[language] if r.name != rule_name
            ]
            return len(self._rules[language]) < original_len
        return False

    def get_rules(self, language: Optional[str] = None) -> Dict[str, List[LanguageRule]]:
        if language:
            return {language: self._rules.get(language, [])}
        return dict(self._rules)

    def analyze_file(
        self,
        file_path: str,
        content: str,
        language: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        if not language:
            language = self._detect_language(file_path)

        issues: List[Dict[str, Any]] = []
        rules = self._rules.get(language, [])

        for line_num, line in enumerate(content.split("\n"), 1):
            for rule in rules:
                if not rule.enabled:
                    continue
                try:
                    if re.search(rule.pattern, line):
                        issues.append({
                            "rule": rule.name,
                            "severity": rule.severity,
                            "description": rule.description,
                            "line": line_num,
                            "column": 0,
                            "content": line.strip()[:100],
                            "language": language,
                        })
                except re.error:
                    continue

        return issues

    @staticmethod
    def _detect_language(file_path: str) -> str:
        ext = Path(file_path).suffix.lower()
        language_map = {
            ".py": "python",
            ".js": "javascript",
            ".jsx": "javascript",
            ".ts": "typescript",
            ".tsx": "typescript",
            ".java": "java",
            ".go": "go",
            ".cpp": "cpp",
            ".cc": "cpp",
            ".h": "cpp",
            ".hpp": "cpp",
            ".rs": "rust",
            ".rb": "ruby",
            ".php": "php",
            ".cs": "csharp",
            ".kt": "kotlin",
            ".swift": "swift",
        }
        return language_map.get(ext, "unknown")


class CodeAnalyzer:
    def __init__(self, rule_engine: Optional[RuleEngine] = None):
        self.rule_engine = rule_engine or RuleEngine()
        self.settings = get_settings()

    def calculate_complexity(self, content: str, language: str) -> float:
        if language == "python":
            return self._calculate_python_complexity(content)
        else:
            return self._calculate_generic_complexity(content, language)

    def _calculate_python_complexity(self, content: str) -> float:
        try:
            tree = ast.parse(content)
            complexity = 0.0

            for node in ast.walk(tree):
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    complexity += 1.0
                    for sub_node in ast.walk(node):
                        if isinstance(sub_node, (
                            ast.If, ast.For, ast.While, ast.AsyncFor,
                            ast.Try, ast.ExceptHandler,
                            ast.BoolOp, ast.IfExp,
                        )):
                            complexity += 0.5

            line_count = len(content.split("\n"))
            if line_count > 0:
                complexity = complexity * (1 + (line_count / 100))

            return round(complexity, 2)

        except SyntaxError:
            return 0.0

    def _calculate_generic_complexity(self, content: str, language: str) -> float:
        complexity_patterns = {
            "javascript": [r"\bif\s*\(", r"\bfor\s*\(", r"\bwhile\s*\(", r"\bswitch\s*\(", r"\bcatch\s*\("],
            "java": [r"\bif\s*\(", r"\bfor\s*\(", r"\bwhile\s*\(", r"\bswitch\s*\(", r"\bcatch\s*\("],
            "typescript": [r"\bif\s*\(", r"\bfor\s*\(", r"\bwhile\s*\(", r"\bswitch\s*\(", r"\bcatch\s*\("],
            "default": [r"\bif\s*\(", r"\bfor\s*\(", r"\bwhile\s*\(", r"\bcatch\s*\("],
        }

        patterns = complexity_patterns.get(language, complexity_patterns["default"])
        complexity = 0.0

        for pattern in patterns:
            complexity += len(re.findall(pattern, content)) * 0.5

        func_pattern = r"\bfunction\s+\w+|\w+\s*[:=]\s*function|\w+\s*\([^)]*\)\s*\{"
        complexity += len(re.findall(func_pattern, content)) * 1.0

        return round(complexity, 2)

    def detect_duplication(self, files: List[Tuple[str, str]], threshold: int = 5) -> Dict[str, Any]:
        line_hash_map: Dict[str, List[Tuple[str, int]]] = defaultdict(list)
        duplicates: Dict[str, List[Tuple[str, int]]] = defaultdict(list)
        total_duplicate_lines = 0

        for file_path, content in files:
            for line_num, line in enumerate(content.split("\n"), 1):
                stripped = line.strip()
                if len(stripped) < 10:
                    continue
                line_hash = hash(stripped)
                line_hash_map[str(line_hash)].append((file_path, line_num))

        for line_hash, occurrences in line_hash_map.items():
            if len(occurrences) >= 2:
                file_set = set(f for f, _ in occurrences)
                if len(file_set) >= 2:
                    for file_path, line_num in occurrences:
                        duplicates[file_path].append((file_path, line_num))
                        total_duplicate_lines += 1

        return {
            "total_duplicate_lines": total_duplicate_lines,
            "duplicate_locations": dict(duplicates),
            "duplicate_rate": total_duplicate_lines / max(sum(len(c.split("\n")) for _, c in files), 1) * 100,
        }

    def calculate_coverage(self, coverage_file: Optional[str] = None) -> float:
        if not coverage_file or not os.path.exists(coverage_file):
            return 0.0

        try:
            with open(coverage_file, "r") as f:
                content = f.read()

            json_match = re.search(r'"totals".*?"percent_covered":\s*([\d.]+)', content, re.DOTALL)
            if json_match:
                return float(json_match.group(1))

            xml_match = re.search(r'line-rate="([\d.]+)"', content)
            if xml_match:
                return float(xml_match.group(1)) * 100

        except Exception as e:
            logger.warning("Failed to parse coverage file: %s", e)

        return 0.0

    def analyze_file(
        self,
        file_path: str,
        content: Optional[str] = None,
        language: Optional[str] = None,
    ) -> AnalysisResult:
        if not language:
            language = RuleEngine._detect_language(file_path)

        if content is None:
            try:
                with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
            except Exception as e:
                raise QualityGateError(f"Failed to read file {file_path}: {e}")

        issues = self.rule_engine.analyze_file(file_path, content, language)
        complexity = self.calculate_complexity(content, language)

        return AnalysisResult(
            file_path=file_path,
            language=language,
            issues=issues,
            complexity_score=complexity,
            line_count=len(content.split("\n")),
        )

    def analyze_directory(
        self,
        directory: str,
        file_patterns: Optional[List[str]] = None,
        exclude_patterns: Optional[List[str]] = None,
    ) -> List[AnalysisResult]:
        dir_path = Path(directory)
        if not dir_path.exists():
            raise QualityGateError(f"Directory not found: {directory}")

        file_patterns = file_patterns or ["*.py", "*.js", "*.ts", "*.java"]
        exclude_patterns = exclude_patterns or ["**/node_modules/**", "**/.git/**", "**/__pycache__/**", "**/venv/**"]

        files: List[Path] = []
        for pattern in file_patterns:
            for file_path in dir_path.rglob(pattern):
                if file_path.is_file():
                    should_exclude = any(
                        file_path.match(exc) for exc in exclude_patterns
                    )
                    if not should_exclude:
                        files.append(file_path)

        results: List[AnalysisResult] = []
        for file_path in files:
            try:
                result = self.analyze_file(str(file_path))
                results.append(result)
            except Exception as e:
                logger.warning("Failed to analyze %s: %s", file_path, e)

        return results

    def run_external_tools(
        self,
        directory: str,
        language: str,
    ) -> Dict[str, Any]:
        results: Dict[str, Any] = {}

        if language == "python":
            results["flake8"] = self._run_flake8(directory)
            results["pylint"] = self._run_pylint(directory)
            results["mypy"] = self._run_mypy(directory)
            results["black"] = self._run_black_check(directory)

        return results

    def _run_flake8(self, directory: str) -> Dict[str, Any]:
        try:
            result = subprocess.run(
                ["flake8", "--format=json", directory],
                capture_output=True,
                text=True,
                timeout=60,
            )
            if result.stdout:
                try:
                    return json.loads(result.stdout)
                except json.JSONDecodeError:
                    pass
            return {"exit_code": result.returncode, "output": result.stdout[:1000]}
        except Exception as e:
            return {"error": str(e)}

    def _run_pylint(self, directory: str) -> Dict[str, Any]:
        try:
            result = subprocess.run(
                ["pylint", "--output-format=json", directory],
                capture_output=True,
                text=True,
                timeout=120,
            )
            if result.stdout:
                try:
                    return json.loads(result.stdout)
                except json.JSONDecodeError:
                    pass
            return {"exit_code": result.returncode, "output": result.stdout[:1000]}
        except Exception as e:
            return {"error": str(e)}

    def _run_mypy(self, directory: str) -> Dict[str, Any]:
        try:
            result = subprocess.run(
                ["mypy", "--json-report", "/dev/stdout", directory],
                capture_output=True,
                text=True,
                timeout=120,
            )
            if result.stdout:
                try:
                    return json.loads(result.stdout)
                except json.JSONDecodeError:
                    pass
            return {"exit_code": result.returncode, "output": result.stdout[:1000]}
        except Exception as e:
            return {"error": str(e)}

    def _run_black_check(self, directory: str) -> Dict[str, Any]:
        try:
            result = subprocess.run(
                ["black", "--check", "--diff", directory],
                capture_output=True,
                text=True,
                timeout=60,
            )
            return {
                "exit_code": result.returncode,
                "needs_formatting": result.returncode != 0,
                "diff": result.stdout[:1000],
            }
        except Exception as e:
            return {"error": str(e)}


class QualityGate:
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        self.settings = get_settings()
        self.analyzer = CodeAnalyzer()
        self._thresholds = {
            "complexity": self.settings.QUALITY_GATE_THRESHOLD_COMPLEXITY,
            "coverage": self.settings.QUALITY_GATE_THRESHOLD_COVERAGE,
            "duplication": self.settings.QUALITY_GATE_THRESHOLD_DUPLICATION,
            "critical_issues": 0,
        }
        if config:
            self._thresholds.update(config)

    def set_threshold(self, metric: str, value: float) -> None:
        self._thresholds[metric] = value
        logger.info("Set threshold %s = %s", metric, value)

    def get_thresholds(self) -> Dict[str, float]:
        return self._thresholds.copy()

    def check(
        self,
        directory: str,
        project_name: str,
        language: str = "python",
        coverage_file: Optional[str] = None,
        run_external_tools: bool = False,
    ) -> QualityGateReport:
        logger.info("Running quality gate check for: %s", project_name)

        analysis_results = self.analyzer.analyze_directory(directory)

        all_issues: List[Dict[str, Any]] = []
        total_complexity = 0.0
        total_lines = 0
        files_with_content: List[Tuple[str, str]] = []

        for result in analysis_results:
            all_issues.extend(result.issues)
            total_complexity += result.complexity_score
            total_lines += result.line_count
            try:
                with open(result.file_path, "r", encoding="utf-8", errors="replace") as f:
                    files_with_content.append((result.file_path, f.read()))
            except Exception:
                pass

        avg_complexity = total_complexity / max(len(analysis_results), 1)
        coverage = self.analyzer.calculate_coverage(coverage_file)
        duplication_info = self.analyzer.detect_duplication(files_with_content)
        duplication_rate = duplication_info.get("duplicate_rate", 0.0)

        critical_issues = [i for i in all_issues if i["severity"] == "critical"]
        high_issues = [i for i in all_issues if i["severity"] in ("high", "error")]
        warning_issues = [i for i in all_issues if i["severity"] == "warning"]

        status = QualityGateStatus.PASSED
        failed_checks: List[str] = []

        if avg_complexity > self._thresholds["complexity"]:
            failed_checks.append(
                f"Complexity {avg_complexity:.2f} exceeds threshold {self._thresholds['complexity']}"
            )
            status = QualityGateStatus.FAILED

        if coverage < self._thresholds["coverage"] and coverage > 0:
            failed_checks.append(
                f"Coverage {coverage:.1f}% below threshold {self._thresholds['coverage']}%"
            )
            status = QualityGateStatus.FAILED

        if duplication_rate > self._thresholds["duplication"]:
            failed_checks.append(
                f"Duplication {duplication_rate:.1f}% exceeds threshold {self._thresholds['duplication']}%"
            )
            status = QualityGateStatus.FAILED

        if len(critical_issues) > self._thresholds["critical_issues"]:
            failed_checks.append(
                f"{len(critical_issues)} critical issues found (threshold: {self._thresholds['critical_issues']})"
            )
            status = QualityGateStatus.FAILED

        if status == QualityGateStatus.PASSED and (warning_issues or high_issues):
            status = QualityGateStatus.WARNING

        external_results = {}
        if run_external_tools:
            external_results = self.analyzer.run_external_tools(directory, language)

        report = QualityGateReport(
            project_name=project_name,
            status=status,
            language=language,
            complexity_score=round(avg_complexity, 2),
            coverage=round(coverage, 2),
            duplication_rate=round(duplication_rate, 2),
            issues=[
                {
                    "summary": {
                        "critical": len(critical_issues),
                        "high": len(high_issues),
                        "warning": len(warning_issues),
                        "info": len(all_issues) - len(critical_issues) - len(high_issues) - len(warning_issues),
                    },
                    "details": all_issues[:100],
                }
            ],
        )

        report_data = report.model_dump() if hasattr(report, "model_dump") else report.dict()
        report_data["failed_checks"] = failed_checks
        report_data["analysis_summary"] = {
            "files_analyzed": len(analysis_results),
            "total_lines": total_lines,
            "external_tools": external_results,
            "duplication_details": duplication_info,
        }
        report_data["thresholds"] = self._thresholds

        return report

    def check_file(
        self,
        file_path: str,
        project_name: str,
        language: Optional[str] = None,
    ) -> QualityGateReport:
        if not os.path.exists(file_path):
            raise QualityGateError(f"File not found: {file_path}")

        result = self.analyzer.analyze_file(file_path, language=language)

        status = QualityGateStatus.PASSED
        if result.complexity_score > self._thresholds["complexity"]:
            status = QualityGateStatus.WARNING

        critical_issues = [i for i in result.issues if i["severity"] == "critical"]
        if critical_issues:
            status = QualityGateStatus.FAILED

        return QualityGateReport(
            project_name=project_name,
            status=status,
            language=result.language,
            complexity_score=result.complexity_score,
            coverage=0.0,
            duplication_rate=0.0,
            issues=[{"issues": result.issues}],
        )

    def generate_report_json(self, report: QualityGateReport) -> str:
        report_data = report.model_dump() if hasattr(report, "model_dump") else report.dict()
        return json.dumps(report_data, indent=2, default=str)

    def generate_report_html(self, report: QualityGateReport) -> str:
        report_data = report.model_dump() if hasattr(report, "model_dump") else report.dict()
        status_color = {
            "passed": "green",
            "warning": "orange",
            "failed": "red",
        }.get(report.status.value, "gray")

        html = f"""<!DOCTYPE html>
<html>
<head>
    <title>Quality Gate Report - {report.project_name}</title>
    <style>
        body {{ font-family: Arial, sans-serif; margin: 20px; }}
        .status {{ padding: 10px; border-radius: 5px; color: white; font-weight: bold;
                 background-color: {status_color}; display: inline-block; }}
        .metric {{ margin: 10px 0; padding: 10px; border: 1px solid #ddd; border-radius: 5px; }}
        .metric-label {{ font-weight: bold; }}
        .metric-value {{ font-size: 1.2em; }}
        .issue {{ margin: 5px 0; padding: 5px; border-left: 3px solid #ccc; }}
        .critical {{ border-left-color: red; }}
        .warning {{ border-left-color: orange; }}
        .info {{ border-left-color: blue; }}
    </style>
</head>
<body>
    <h1>Quality Gate Report</h1>
    <h2>{report.project_name}</h2>
    <p><span class="status">STATUS: {report.status.value.upper()}</span></p>
    <p>Generated at: {report.generated_at}</p>

    <h3>Metrics</h3>
    <div class="metric">
        <span class="metric-label">Complexity:</span>
        <span class="metric-value">{report.complexity_score}</span>
        (threshold: {self._thresholds['complexity']})
    </div>
    <div class="metric">
        <span class="metric-label">Coverage:</span>
        <span class="metric-value">{report.coverage}%</span>
        (threshold: {self._thresholds['coverage']}%)
    </div>
    <div class="metric">
        <span class="metric-label">Duplication:</span>
        <span class="metric-value">{report.duplication_rate}%</span>
        (threshold: {self._thresholds['duplication']}%)
    </div>

    <h3>Issues</h3>
    {self._format_issues_html(report.issues)}
</body>
</html>
"""
        return html

    def _format_issues_html(self, issues: List[Dict[str, Any]]) -> str:
        if not issues:
            return "<p>No issues found</p>"

        html_parts: List[str] = []
        for issue_group in issues:
            if isinstance(issue_group, dict) and "details" in issue_group:
                for issue in issue_group["details"]:
                    severity = issue.get("severity", "info")
                    html_parts.append(
                        f'<div class="issue {severity}">'
                        f'<strong>[{severity.upper()}]</strong> '
                        f'{issue.get("rule", "")}: {issue.get("description", "")} '
                        f'(Line {issue.get("line", 0)})'
                        f'</div>'
                    )

        return "\n".join(html_parts) if html_parts else "<p>No issues found</p>"

    def save_report(
        self,
        report: QualityGateReport,
        output_path: str,
        format: str = "json",
    ) -> None:
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)

        if format == "json":
            content = self.generate_report_json(report)
        elif format == "html":
            content = self.generate_report_html(report)
        else:
            raise ValueError(f"Unsupported format: {format}")

        with open(output_path, "w", encoding="utf-8") as f:
            f.write(content)

        logger.info("Quality gate report saved to %s", output_path)

    def check_rules(self) -> Dict[str, List[QualityGateRule]]:
        rules = self.analyzer.rule_engine.get_rules()
        result: Dict[str, List[QualityGateRule]] = {}

        for language, lang_rules in rules.items():
            result[language] = [
                QualityGateRule(
                    rule_id=generate_id("rule"),
                    name=r.name,
                    language=language,
                    severity=r.severity,
                    enabled=r.enabled,
                    parameters={"pattern": r.pattern, "description": r.description},
                )
                for r in lang_rules
            ]

        return result
